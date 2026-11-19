package com.vibe.v2ex.data.remote

import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.suspendCancellableCoroutine
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

internal object FollowingPageParser {
    fun parse(html: String, base: HttpUrl, page: Int): PublicTopicPage {
        require(page > 0 && page < Int.MAX_VALUE) { "关注列表页码无效" }
        val document = Jsoup.parse(html, base.toString())
        document.select("script,style,template,noscript,.topic_content,.reply_content,.topic_append,.subtle").remove()
        if (document.select("input[type=password],form[action='/signin'],form[action='/2fa']").isNotEmpty()) error("网页会话已失效，请重新登录 V2EX")
        if (document.select("#challenge-form").isNotEmpty() || document.title().contains("just a moment", true) || document.title().contains("attention required", true)) error("网页要求验证，请先完成网页登录验证")
        val mains = document.select("#Main")
        val main = if (mains.isNotEmpty()) mains.singleOrNull() else {
            document.select("#Wrapper").singleOrNull()?.children()?.filter { it.tagName() == "div" && it.hasClass("content") }?.singleOrNull()
        } ?: error("未识别关注列表结构")
        if (main.select(".problem,#problem").isNotEmpty()) error("官网未能返回关注列表，请稍后重试")
        // Normalize only validated main-list rows, not arbitrary links or sidebar topics.
        val rows = main.select("div.cell").filter { cell -> cell.select(".item_title").size == 1 }.mapNotNull { cell ->
            val link = cell.selectFirst(".item_title a[href]") ?: return@mapNotNull null
            val url = base.resolve(link.attr("href")) ?: return@mapNotNull null
            if (!url.sameOrigin(base) || url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || !Regex("/t/[1-9][0-9]*").matches(url.encodedPath)) return@mapNotNull null
            cell.clone().also { copy ->
                copy.addClass("item")
                copy.selectFirst(".item_title a[href]")?.apply { addClass("topic-link"); attr("href", url.encodedPath) }
            }.outerHtml()
        }
        val topics = WebsitePageParser.publicTopics(rows.joinToString("\n"), base.toString())
        if (topics.isEmpty()) {
            val navigation = main.select("a[href]").any { base.resolve(it.attr("href"))?.let { url -> url.sameOrigin(base) && url.encodedPath == "/my/following" } == true }
            val messages = listOf("还没有关注任何人", "尚未关注任何人", "还没有特别关注", "没有关注任何用户", "关注的人还没有发布", "No topics", "not following anyone")
            if (!navigation || messages.none { main.text().contains(it, true) }) error("未识别关注列表，不能确定是否为空，请稍后重试")
        }
        val more = main.select("a[href]").any {
            val url = base.resolve(it.attr("href")) ?: return@any false
            url.sameOrigin(base) && url.username.isEmpty() && url.password.isEmpty() && url.encodedPath == "/my/following" &&
                url.querySize == 1 && url.queryParameter("p")?.toIntOrNull() == page + 1
        }
        return PublicTopicPage(topics, more)
    }
}

class FollowingService internal constructor(client: OkHttpClient, private val base: HttpUrl) {
    @Inject constructor(client: OkHttpClient) : this(client, "https://www.v2ex.com/".toHttpUrl())
    private val http = client.newBuilder().cookieJar(CookieJar.NO_COOKIES).cache(null)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).callTimeout(15, TimeUnit.SECONDS)
        .also { it.interceptors().clear(); it.networkInterceptors().clear() }.build()

    suspend fun topics(page: Int, cookie: String): PublicTopicPage {
        require(cookie.isNotBlank() && cookie.all { it.code in 32..126 }) { "请先在 App 内网页登录 V2EX，再查看关注" }
        require(page > 0 && page < Int.MAX_VALUE) { "关注列表页码无效" }
        var url = base.resolve("/my/following?p=$page") ?: error("站点地址无效")
        repeat(3) {
            val response = get(url, cookie)
            if (response.code in listOf(401, 403)) error("网页会话已失效，请重新登录 V2EX")
            if (response.code == 200) return FollowingPageParser.parse(response.body, base, page)
            if (response.code !in listOf(301, 302, 303, 307, 308)) error("关注列表加载失败（HTTP ${response.code}）")
            val target = response.location?.let { url.resolve(it) } ?: error("关注列表返回了异常跳转")
            if (!target.sameOrigin(base) || target.username.isNotEmpty() || target.password.isNotEmpty()) error("关注列表返回了异常跳转")
            if (target.encodedPath in listOf("/signin", "/2fa")) error("网页会话已失效，请重新登录 V2EX")
            if (target.encodedPath != "/my/following" || target.fragment != null || (target.query != "p=$page" && !(page == 1 && target.query == null))) error("关注列表返回了异常跳转")
            url = target
        }
        error("关注列表跳转过多")
    }

    private data class Page(val code: Int, val body: String, val location: String?)
    private suspend fun get(url: HttpUrl, cookie: String): Page = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).header("Cookie", cookie).header("Cache-Control", "no-store")
            .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Safari/605.1.15").build()
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException): Unit = continuation.resumeWith(Result.failure(e))
            override fun onResponse(call: Call, response: Response): Unit {
                continuation.resumeWith(runCatching { response.use { Page(it.code, it.body.string(), it.header("Location")) } })
            }
        })
    }
}
