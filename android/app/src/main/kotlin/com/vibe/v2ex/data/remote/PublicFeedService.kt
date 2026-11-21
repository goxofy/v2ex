package com.vibe.v2ex.data.remote

import com.vibe.v2ex.data.model.Topic
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

/** Public feeds must not inherit login cookies or reuse a previous HTTP response on refresh. */
internal class PublicFeedService(
    client: OkHttpClient,
    private val base: HttpUrl = "https://www.v2ex.com/".toHttpUrl(),
) {
    enum class Ranking(val tab: String) { HOT("hot"), R2("r2") }

    private class FeedFailure(message: String) : IllegalStateException(message)
    private data class Page(val code: Int, val html: String, val location: String?, val challenge: Boolean)

    private val http: OkHttpClient = client.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES).cache(null)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .callTimeout(15, TimeUnit.SECONDS)
        .also { it.interceptors().clear(); it.networkInterceptors().clear() }
        .build()

    suspend fun topics(nodeName: String?, page: Int): Result<PublicTopicPage> = feedResult {
        if (page < 1 || page == Int.MAX_VALUE) fail("话题列表页码无效")
        val node = nodeName?.trim()?.also {
            if (!NODE_NAME.matches(it)) fail("节点名称无效")
        }
        fetch(node?.let { "/go/$it" } ?: "/recent", page, node, null)
    }

    suspend fun rankedTopics(ranking: Ranking): Result<List<Topic>> = feedResult {
        fetch("/", 1, null, ranking.tab).topics
    }

    private suspend fun fetch(path: String, page: Int, node: String?, tab: String?): PublicTopicPage {
        val initial = base.newBuilder().encodedPath(path).query(null).fragment(null)
            .apply {
                if (tab == null) addQueryParameter("p", page.toString()) else addQueryParameter("tab", tab)
                addQueryParameter("_", UUID.randomUUID().toString())
            }.build()
        var url = initial
        repeat(3) {
            currentCoroutineContext().ensureActive()
            val response = get(url)
            if (response.challenge) fail("V2EX 要求网页验证，请稍后重试或到网页查看")
            if (response.code in REDIRECTS) {
                val target = response.location?.let(url::resolve) ?: fail("话题列表返回了异常跳转")
                if (!sameFeed(target, initial, page, tab)) fail("话题列表返回了异常跳转，请稍后重试")
                // Keep this fetch's unique cache key even when the server canonicalizes the URL.
                url = target.newBuilder().setQueryParameter("_", initial.queryParameter("_")).build()
            } else {
                if (response.code !in 200..299) fail("话题列表加载失败（HTTP ${response.code}），请稍后重试")
                return parse(response.html, url, path, page, node, tab)
            }
        }
        fail("话题列表跳转过多，请稍后重试")
    }

    private fun sameFeed(target: HttpUrl, initial: HttpUrl, page: Int, tab: String?): Boolean {
        if (!target.sameOrigin(base) || target.encodedPath != initial.encodedPath || target.fragment != null) return false
        if (target.queryParameterNames.any { it !in setOf("p", "tab", "_") }) return false
        val pages = target.queryParameterValues("p")
        val tabs = target.queryParameterValues("tab")
        return if (tab == null) {
            tabs.isEmpty() && (pages == listOf(page.toString()) || (page == 1 && pages.isEmpty()))
        } else {
            tabs == listOf(tab) && pages.isEmpty()
        }
    }

    private fun parse(html: String, url: HttpUrl, path: String, page: Int, node: String?, tab: String?): PublicTopicPage {
        val document = Jsoup.parse(html, url.toString())
        document.select("script,style,template,noscript,.topic_content,.reply_content,.topic_append,.subtle").remove()
        if (document.select("#challenge-form").isNotEmpty() || document.title().contains("just a moment", true) ||
            document.title().contains("attention required", true)
        ) fail("V2EX 要求网页验证，请稍后重试或到网页查看")
        if (document.select("input[type=password],form[action='/signin'],form[action='/2fa']").isNotEmpty()) {
            fail("官网未返回公开话题列表，请到网页检查访问状态")
        }
        val mains = document.select("#Main")
        val main = if (mains.isNotEmpty()) mains.singleOrNull() else {
            document.select("div#Wrapper").singleOrNull()?.children()
                ?.filter { it.tagName() == "div" && it.hasClass("content") }?.singleOrNull()
        } ?: fail("未识别话题列表结构，请稍后重试")
        if (main.select(".problem,#problem").isNotEmpty()) fail("官网未能返回话题列表，请稍后重试")
        val markup = main.outerHtml()
        val topics = WebsitePageParser.publicTopics(markup, url.toString(), node)
        if (topics.isEmpty()) fail("网页没有返回话题，请稍后重试")
        return PublicTopicPage(
            topics = topics,
            hasMore = tab == null && WebsitePageParser.hasMorePublicTopics(markup, url.toString(), path, page),
        )
    }

    private suspend fun get(url: HttpUrl): Page = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Cache-Control", "no-cache, no-store")
            .header("Pragma", "no-cache")
            .build()
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException): Unit = continuation.resumeWith(Result.failure(e))
            override fun onResponse(call: Call, response: Response): Unit {
                continuation.resumeWith(runCatching {
                    response.use { Page(it.code, it.body.string(), it.header("Location"), it.header("cf-mitigated").equals("challenge", true)) }
                })
            }
        })
    }

    private suspend fun <T> feedResult(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            currentCoroutineContext().ensureActive()
            Result.success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (error: FeedFailure) {
            Result.failure(error)
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            Result.failure(FeedFailure("无法读取话题列表，请检查网络后重试"))
        }
    }

    private fun fail(message: String): Nothing = throw FeedFailure(message)

    private companion object {
        val NODE_NAME = Regex("[A-Za-z0-9_-]+")
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
        const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/18.5 Safari/605.1.15"
    }
}
