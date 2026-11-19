package com.vibe.v2ex.data.remote

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WebLoginVerifierTest {
    private lateinit var server: MockWebServer
    private lateinit var verifier: WebLoginVerifier
    private val candidate = WebLoginCandidate("PB3_SESSION=fixture; cf_clearance=fixture", "fixture_user", "Android WebView fixture UA")
    private val settings = """<div id="Top"><a class="top" href="/member/fixture_user">fixture_user</a><a class="top" href="/signout?once=fixture">退出</a></div>
        <div id="Main"><div class="box"><form method="post" action="/settings"><input name="once" value="fixture"><input name="website"><textarea name="bio"></textarea></form></div></div>"""

    @Before fun setup(): Unit {
        server = MockWebServer()
        server.start()
        verifier = WebLoginVerifier(OkHttpClient(), server.url("/"))
    }
    @After fun teardown(): Unit { server.close() }

    private fun enqueue(html: String = "", code: Int = 200, location: String? = null, challenge: Boolean = false): Unit {
        server.enqueue(MockResponse.Builder().code(code).body(html).apply {
            location?.let { addHeader("Location", it) }
            if (challenge) addHeader("cf-mitigated", "challenge")
        }.build())
    }

    @Test fun sendsExactBrowserCookieAndUserAgentWithoutSharedJarOrInterceptors(): Unit = runBlocking {
        val client = OkHttpClient.Builder().cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>): Unit = fail("Shared jar must not be used")
            override fun loadForRequest(url: HttpUrl): List<Cookie> { fail("Shared jar must not be used"); return emptyList() }
        }).addInterceptor { error("Shared interceptor must not run") }.build()
        enqueue(settings)
        assertEquals(WebSessionVerification.AUTHENTICATED, WebLoginVerifier(client, server.url("/")).verify(candidate))
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/settings", request.target)
        assertEquals(candidate.cookie, request.headers["Cookie"])
        assertEquals(candidate.userAgent, request.headers["User-Agent"])
        assertNull(request.headers["Authorization"])
    }

    @Test fun loginAndTwoFactorRedirectsAreSignedOutWithoutFollowing(): Unit = runBlocking {
        for (path in listOf("/signin", "/2fa")) {
            enqueue(code = 302, location = path)
            assertEquals(WebSessionVerification.SIGNED_OUT, verifier.verify(candidate))
        }
        assertEquals(2, server.requestCount)
    }

    @Test fun challengeAndServerErrorsRemainInconclusive(): Unit = runBlocking {
        for (code in listOf(200, 403, 429, 500, 503)) {
            enqueue("<title>Just a moment...</title><form id='challenge-form'></form>", code)
            assertEquals(WebSessionVerification.UNVERIFIED, verifier.verify(candidate))
        }
        enqueue(settings, challenge = true)
        assertEquals(WebSessionVerification.UNVERIFIED, verifier.verify(candidate))
        enqueue("Forbidden", 403)
        assertEquals(WebSessionVerification.UNVERIFIED, verifier.verify(candidate))
    }

    @Test fun statusAndPathAloneCannotConfirmLogin(): Unit = runBlocking {
        for (html in listOf("", "Unknown page", settings.replace("/member/fixture_user", "/member/other"), settings.replace("name=\"once\"", "name=\"other\""))) {
            enqueue(html)
            assertEquals(WebSessionVerification.UNVERIFIED, verifier.verify(candidate))
        }
    }

    @Test fun formEvidenceAndNavigationMustNotComeFromUserContent(): Unit {
        val forged = "<div class='reply_content'>$settings</div>"
        assertEquals(WebSessionVerification.UNVERIFIED, WebLoginPage.classify(forged, server.url("/"), candidate.username))
        val authorOnly = settings.replace("id=\"Top\"", "id=\"Replies\"")
        assertEquals(WebSessionVerification.UNVERIFIED, WebLoginPage.classify(authorOnly, server.url("/"), candidate.username))
    }

    @Test fun mobileSettingsAndHeaderAccountAreRecognized(): Unit {
        val mobile = settings.replace("id=\"Top\"", "id=\"site-header-menu\"")
            .replace("<div id=\"Main\">", "<div id=\"Wrapper\"><div class=\"content\">") + "</div>"
        assertEquals(WebSessionVerification.AUTHENTICATED, WebLoginPage.classify(mobile, server.url("/"), candidate.username))
    }

    @Test fun standaloneMemberAvatarIsNotAccountNavigation(): Unit {
        val html = settings.replace("id=\"Top\"", "id=\"Replies\"").replace("class=\"top\"", "class=\"avatar\"")
        assertEquals(WebSessionVerification.UNVERIFIED, WebLoginPage.classify(html, server.url("/"), candidate.username))
    }

    @Test fun explicitLoginFormIsSignedOut(): Unit = runBlocking {
        enqueue("<form action='/signin' method='post'><input type='password'></form>")
        assertEquals(WebSessionVerification.SIGNED_OUT, verifier.verify(candidate))
    }

    @Test fun unsafeRedirectsNeverReceiveCredentials(): Unit = runBlocking {
        for (target in listOf("https://other.invalid/settings", "http://user:password@localhost:${server.port}/settings", "/t/1", "/settings?redirect=other")) {
            enqueue(code = 302, location = target)
            assertEquals(WebSessionVerification.UNVERIFIED, verifier.verify(candidate))
        }
        assertEquals(4, server.requestCount)
    }

    @Test fun canonicalSameOriginRedirectIsBoundedAndReadOnly(): Unit = runBlocking {
        enqueue(code = 302, location = "/settings")
        enqueue(settings)
        assertEquals(WebSessionVerification.AUTHENTICATED, verifier.verify(candidate))
        repeat(2) { assertEquals("GET", server.takeRequest().method) }
        repeat(3) { enqueue(code = 302, location = "/settings") }
        assertEquals(WebSessionVerification.UNVERIFIED, verifier.verify(candidate))
        assertEquals(5, server.requestCount)
    }

    @Test fun invalidHeadersDoNotSendRequest(): Unit = runBlocking {
        for (bad in listOf(WebLoginCandidate("", "fixture_user", "UA"), WebLoginCandidate("fixture", "fixture_user", "bad\r\nUA"))) {
            assertEquals(WebSessionVerification.UNVERIFIED, verifier.verify(bad))
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun cancellationStopsConfirmation(): Unit = runBlocking {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
        val job = async(start = CoroutineStart.UNDISPATCHED) { verifier.verify(candidate) }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
        job.cancel()
        try { job.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals(1, server.requestCount)
    }

    @Test fun browserOriginChecksExactHttpsOrigin(): Unit {
        assertTrue(WebLoginPage.isSite("https://www.v2ex.com/settings"))
        for (url in listOf(null, "http://www.v2ex.com/settings", "https://www.v2ex.com.evil.invalid/", "https://user@www.v2ex.com/", "https://www.v2ex.com:444/", "about:blank")) {
            assertFalse(url, WebLoginPage.isSite(url))
        }
    }

    @Test fun candidateDoesNotExposeCredentialsInToString(): Unit {
        assertFalse(candidate.toString().contains(candidate.cookie))
    }

    @Test fun backgroundCheckAcceptsBioOnlySettingsWithoutOrdinarySignout(): Unit = runBlocking {
        val bioOnly = settings.replace("<input name=\"website\">", "")
            .replace("href=\"/signout?once=fixture\"", "href=\"javascript:signout();\"")
        enqueue(bioOnly)
        assertEquals(WebSessionVerification.AUTHENTICATED, verifier.verify(candidate))
    }
}
