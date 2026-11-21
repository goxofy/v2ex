package com.vibe.v2ex.data.remote

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

internal class ReplyUnconfirmedException : IllegalStateException(
    "回复结果待确认，请先到网页查看，避免重复发送；草稿已保留",
)

private class ReplyRejectedException(message: String) : IllegalStateException(message)

/** One POST per operation; all recovery is read-only, including ambiguous transport failures. */
internal class ReplySubmission(
    okHttpClient: OkHttpClient,
    private val baseUrl: HttpUrl = "https://www.v2ex.com/".toHttpUrl(),
    private val waitBeforeRetry: suspend (Long) -> Unit = { delay(it) },
) {
    private val client: OkHttpClient = okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .apply {
            // This cookie-authenticated flow needs neither API interceptors nor request logging.
            interceptors().clear()
            networkInterceptors().clear()
        }
        .build()
    private val confirmationClient: OkHttpClient = client.newBuilder()
        .callTimeout(5, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private data class HttpPage(val code: Int, val url: HttpUrl, val location: String?, val html: String, val mitigated: Boolean)

    suspend fun postReply(topicId: Long, content: String, cookie: String?, username: String?, userAgent: String? = null): Result<Unit> =
        withContext(Dispatchers.IO) {
            var submitted = false
            try {
                currentCoroutineContext().ensureActive()
                if (topicId <= 0) reject("话题编号无效")
                val text = content.trim().takeIf(String::isNotEmpty) ?: reject("回复内容不能为空")
                val sessionCookie = cookie?.trim()?.takeIf { value ->
                    value.isNotEmpty() && value.all { it.code in 32..126 }
                } ?: reject("网页会话已失效，请重新登录 V2EX")
                val author = username?.trim()?.takeIf { USERNAME.matches(it) }
                    ?: reject("无法确认网页账号，请重新登录 V2EX 后再回复")
                // Preserve this login's browser identity throughout the operation, including confirmation GETs.
                val sessionUserAgent = (userAgent ?: LEGACY_USER_AGENT).takeIf { value ->
                    value.isNotBlank() && value.all { it.code in 32..126 }
                } ?: reject("网页登录浏览器信息无效，请重新登录 V2EX 后再回复")
                val topicUrl = baseUrl.newBuilder().encodedPath("/t/$topicId").query(null).fragment(null).build()
                val before = readLatest(topicUrl, topicUrl, sessionCookie, sessionUserAgent, client)
                val form = before.last().form() ?: reject("当前话题没有可用的回复表单，请到网页检查登录状态或回复权限")
                val baseline = before.maxOf { it.maximumReplyId }
                val body = FormBody.Builder().add("content", text).add("once", form.once).build()
                val request = request(form.url, topicUrl, sessionCookie, sessionUserAgent).post(OneShotBody(body)).build()

                currentCoroutineContext().ensureActive()
                submitted = true
                val response = try {
                    execute(request, client)
                } catch (_: IOException) {
                    null // A dropped response does not mean that the server rejected the POST.
                }
                var confirmationUrl = topicUrl
                if (response != null) {
                    if (response.code in REDIRECT_CODES) {
                        confirmationUrl = try {
                            redirectTarget(response, topicUrl)
                        } catch (_: IOException) {
                            topicUrl
                        }
                    } else {
                        val page = validate(response, topicUrl)
                        if (response.code in 200..299 && page.confirms(text, author, baseline)) {
                            return@withContext Result.success(Unit)
                        }
                    }
                }

                repeat(3) { attempt ->
                    if (attempt > 0) waitBeforeRetry(attempt * 1000L)
                    currentCoroutineContext().ensureActive()
                    try {
                        val pages = readLatest(confirmationUrl, topicUrl, sessionCookie, sessionUserAgent, confirmationClient)
                        if (pages.any { it.confirms(text, author, baseline) }) return@withContext Result.success(Unit)
                    } catch (_: IOException) {
                        // Only read-only failures are retried; explicit rejections and cancellation escape.
                    }
                }
                Result.failure(ReplyUnconfirmedException())
            } catch (error: CancellationException) {
                throw error
            } catch (error: ReplyRejectedException) {
                Result.failure(error)
            } catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                Result.failure(
                    if (submitted) ReplyUnconfirmedException()
                    else ReplyRejectedException("无法读取回复页面，请检查网络后重试；草稿已保留"),
                )
            }
        }

    private suspend fun readLatest(
        startUrl: HttpUrl,
        topicUrl: HttpUrl,
        cookie: String,
        userAgent: String,
        httpClient: OkHttpClient,
    ): List<ReplyPage> {
        val pages = mutableListOf<ReplyPage>()
        var url = startUrl
        repeat(3) {
            val response = get(url, topicUrl, cookie, userAgent, httpClient)
            val page = validate(response, topicUrl)
            if (response.code !in 200..299) throw IOException("Reply page unavailable")
            pages.add(page)
            val currentPage = response.url.queryParameter("p")?.toIntOrNull() ?: 1
            val lastPage = page.lastPage()
            if (lastPage <= currentPage) return pages
            url = topicUrl.newBuilder().setQueryParameter("p", lastPage.toString()).build()
        }
        throw IOException("Reply pagination did not settle")
    }

    private suspend fun get(url: HttpUrl, topicUrl: HttpUrl, cookie: String, userAgent: String, httpClient: OkHttpClient): HttpPage {
        var next = url
        repeat(6) {
            if (!next.isReplyTopic(topicUrl)) reject("回复页面地址异常，请到网页检查；草稿已保留")
            val response = execute(request(next, topicUrl, cookie, userAgent).get().build(), httpClient)
            if (response.code !in REDIRECT_CODES) return response
            next = redirectTarget(response, topicUrl)
        }
        throw IOException("Too many reply page redirects")
    }

    private fun redirectTarget(page: HttpPage, topicUrl: HttpUrl): HttpUrl {
        val target = page.location?.let(page.url::resolve)
            ?: throw IOException("Missing reply redirect")
        if (target.sameOrigin(topicUrl) && (target.encodedPath == "/signin" || target.encodedPath.startsWith("/2fa"))) {
            reject("网页会话已失效，请重新登录 V2EX；请先在网页确认回复结果")
        }
        if (!target.isReplyTopic(topicUrl)) reject("回复页面跳转异常，请先到网页查看结果，避免重复发送；草稿已保留")
        return target
    }

    private fun validate(response: HttpPage, topicUrl: HttpUrl): ReplyPage {
        if (!response.url.isReplyTopic(topicUrl)) reject("回复页面地址异常；草稿已保留")
        val page = ReplyPage(response.html, response.url, topicUrl)
        page.rejection(response.mitigated)?.let(::reject)
        when (response.code) {
            401 -> reject("网页会话已失效，请重新登录 V2EX；请先在网页确认回复结果")
            403 -> reject("V2EX 拒绝了本次请求，请先到网页检查回复结果或验证要求；草稿已保留")
            429 -> reject("请求过于频繁，请稍后再试；草稿已保留")
            in 400..499 -> if (response.code != 408) reject("回复请求被拒绝（HTTP ${response.code}）；草稿已保留")
        }
        return page
    }

    private fun request(url: HttpUrl, topicUrl: HttpUrl, cookie: String, userAgent: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Cookie", cookie)
        .header("Origin", topicUrl.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/'))
        .header("Referer", topicUrl.toString())
        .header("User-Agent", userAgent)
        .header("Accept", "text/html,application/xhtml+xml")
        .header("Cache-Control", "no-cache, no-store")
        .header("Pragma", "no-cache")

    private suspend fun execute(request: Request, httpClient: OkHttpClient): HttpPage =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException): Unit {
                    continuation.resumeWith(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response): Unit {
                    val result = try {
                        response.use {
                            Result.success(HttpPage(it.code, it.request.url, it.header("Location"), it.body.string(), it.header("cf-mitigated").equals("challenge", true)))
                        }
                    } catch (error: Exception) {
                        Result.failure(error)
                    }
                    continuation.resumeWith(result)
                }
            })
        }

    /** OkHttp may otherwise replay 408/503 responses even with connection retries disabled. */
    private class OneShotBody(private val form: FormBody) : RequestBody() {
        override fun contentType(): MediaType = form.contentType()
        override fun contentLength(): Long = form.contentLength()
        override fun isOneShot(): Boolean = true
        override fun writeTo(sink: BufferedSink): Unit = form.writeTo(sink)
    }

    private fun reject(message: String): Nothing = throw ReplyRejectedException(message)

    private companion object {
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val USERNAME = Regex("[A-Za-z0-9_]+")
        const val LEGACY_USER_AGENT = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Mobile/15E148 Safari/604.1"
    }
}
