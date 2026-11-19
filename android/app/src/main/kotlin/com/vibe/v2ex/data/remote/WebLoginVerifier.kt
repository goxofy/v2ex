package com.vibe.v2ex.data.remote

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Deliberately not a data class: generated toString must not expose cookies. */
class WebLoginCandidate(
    val cookie: String,
    val username: String,
    val userAgent: String,
    /** Checked on the UI thread immediately before saving; never sent over the network. */
    val isCurrent: () -> Boolean = { true },
)

enum class WebSessionVerification { AUTHENTICATED, SIGNED_OUT, UNVERIFIED }

internal object WebLoginPage {
    const val BASE = "https://www.v2ex.com"
    // Restrict identity evidence to site-owned account navigation, never topic authors.
    const val ACCOUNT_LINKS = "#Top a.top[href], #Top a.avatar[href], .site-nav a[href], #site-header-menu a[href]"
    private val usernamePattern = Regex("[A-Za-z0-9_]{1,100}")

    fun isSite(url: String?): Boolean = url?.toHttpUrlOrNull()?.let {
        it.scheme == "https" && it.host == "www.v2ex.com" && it.port == 443 && it.username.isEmpty() && it.password.isEmpty()
    } == true

    fun validHeader(value: String): Boolean = value.isNotBlank() && value.all { it.code in 32..126 }

    fun validUsername(value: String): Boolean = usernamePattern.matches(value)

    fun isSettings(url: String?): Boolean = isSite(url) && url?.toHttpUrlOrNull()?.let {
        it.encodedPath == "/settings" && it.query == null && it.fragment == null
    } == true

    fun classify(html: String, base: HttpUrl, username: String): WebSessionVerification =
        when (BrowserLoginEvidence.fromHtml(html, base).reason(username)) {
            BrowserLoginReason.VERIFIED -> WebSessionVerification.AUTHENTICATED
            BrowserLoginReason.LOGIN_REQUIRED -> WebSessionVerification.SIGNED_OUT
            else -> WebSessionVerification.UNVERIFIED
        }
}

/** Read-only, cookie-isolated verification; a challenge is not proof of logout. */
internal class WebLoginVerifier(client: OkHttpClient, private val base: HttpUrl = "${WebLoginPage.BASE}/".toHttpUrl()) {
    private val http = client.newBuilder().cookieJar(CookieJar.NO_COOKIES).cache(null)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .callTimeout(15, TimeUnit.SECONDS)
        .also { it.interceptors().clear(); it.networkInterceptors().clear() }.build()

    suspend fun verify(candidate: WebLoginCandidate): WebSessionVerification {
        if (!WebLoginPage.validHeader(candidate.cookie) || !WebLoginPage.validHeader(candidate.userAgent)) return WebSessionVerification.UNVERIFIED
        var url = base.resolve("/settings") ?: return WebSessionVerification.UNVERIFIED
        try {
            repeat(3) {
                val page = get(url, candidate)
                if (page.mitigated || TopicMovePage(page.body, base).requiresVerification()) return WebSessionVerification.UNVERIFIED
                if (page.code == 200) return WebLoginPage.classify(page.body, base, candidate.username)
                if (page.code !in listOf(301, 302, 303, 307, 308)) return WebSessionVerification.UNVERIFIED
                val target = page.location?.let { url.resolve(it) } ?: return WebSessionVerification.UNVERIFIED
                if (!target.sameOrigin(base) || target.username.isNotEmpty() || target.password.isNotEmpty()) return WebSessionVerification.UNVERIFIED
                if (target.encodedPath in listOf("/signin", "/2fa")) return WebSessionVerification.SIGNED_OUT
                if (target.encodedPath != "/settings" || target.query != null || target.fragment != null) return WebSessionVerification.UNVERIFIED
                url = target
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            return WebSessionVerification.UNVERIFIED
        }
        return WebSessionVerification.UNVERIFIED
    }

    private data class Page(val code: Int, val body: String, val location: String?, val mitigated: Boolean)

    private suspend fun get(url: HttpUrl, candidate: WebLoginCandidate): Page = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(url).header("Cookie", candidate.cookie)
            .header("User-Agent", candidate.userAgent).header("Cache-Control", "no-store").build()
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
}
