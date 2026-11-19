package com.vibe.v2ex.data.remote

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FollowingServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var service: FollowingService

    @Before fun setup(): Unit {
        server = MockWebServer()
        server.start()
        service = FollowingService(OkHttpClient(), server.url("/"))
    }

    @After fun teardown(): Unit { server.close() }

    private fun row(id: Int): String = """<div class="cell item"><table><tr><td><img class="avatar" src="/avatar.png"></td><td>
        <span class="item_title"><a href="/t/$id#reply2">Test topic $id</a></span>
        <span><a class="node" href="/go/apple">Apple</a> · <strong><a href="/member/testuser">testuser</a></strong></span>
        <a class="count_livid" href="/t/$id">2</a></td></tr></table></div>"""

    private fun page(rows: String, next: Int? = null): String =
        "<div id='Main'><div class='box'><a href='/my/following'>特别关注</a>$rows${next?.let { "<a href='/my/following?p=$it'>Next</a>" }.orEmpty()}</div></div>"

    private fun parse(html: String): PublicTopicPage = FollowingPageParser.parse(html, server.url("/"), 1)
    private fun enqueue(body: String = "", code: Int = 200, location: String? = null): Unit {
        server.enqueue(MockResponse.Builder().code(code).body(body).apply { location?.let { addHeader("Location", it) } }.build())
    }

    @Test fun desktopRowsWithoutTopicLinkKeepWebsiteOrder(): Unit {
        val result = parse(page(row(9) + row(3) + row(9), 2))
        assertEquals(listOf(9L, 3L), result.topics.map { it.id })
        assertEquals("testuser", result.topics.first().member?.username)
        assertEquals("apple", result.topics.first().node?.name)
        assertEquals(2, result.topics.first().replies)
        assertTrue(result.hasMore)
    }

    @Test fun mobileRowsIgnoreSidebarAndUntrustedContent(): Unit {
        val html = "<div id='Rightbar'>${row(99)}</div><div id='Wrapper'><div class='content'><div class='box'>${row(4)}<div class='reply_content'>${row(98)}</div><script>${row(97)}</script></div></div></div>"
        assertEquals(listOf(4L), parse(html).topics.map { it.id })
    }

    @Test fun explicitEmptyIsDistinctFromUnknownLoginAndChallenge(): Unit {
        assertTrue(parse(page("关注的人还没有发布话题")).topics.isEmpty())
        listOf("", page("Unexpected page"), "<input type='password'>", "<form id='challenge-form'></form>", "<div id='Main'>No topics</div>", page("<div class='problem'>Error</div>")).forEach {
            assertTrue(it, runCatching { parse(it) }.isFailure)
        }
    }

    @Test fun malformedAndForeignRowsAreRejected(): Unit {
        listOf("https://other.invalid/t/1", "/t/+1", "/t/1?next=x", "/t/1/extra").forEach {
            assertTrue(it, runCatching { parse(page(row(1).replace("/t/1#reply2", it))) }.isFailure)
        }
    }

    @Test fun paginationOnlyAcceptsExactNextFollowingPage(): Unit {
        listOf("https://other.invalid/my/following?p=2", "/my/following?p=3", "/my/following?p=2&amp;other=1", "/my/nodes?p=2").forEach {
            assertFalse(it, parse(page(row(1) + "<a href='$it'>Next</a>")).hasMore)
        }
    }

    @Test fun requestUsesCurrentCookieAndAllowsCanonicalReadOnlyRedirect(): Unit = runBlocking {
        enqueue(code = 302, location = "/my/following")
        enqueue(page(row(5)))
        assertEquals(listOf(5L), service.topics(1, "PB3_SESSION=fixture").topics.map { it.id })
        assertEquals(2, server.requestCount)
        repeat(2) {
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("GET", request.method)
            assertEquals("PB3_SESSION=fixture", request.headers["Cookie"])
        }
    }

    @Test fun unsafeAndLoginRedirectsDoNotStartSecondRequest(): Unit = runBlocking {
        listOf("https://other.invalid/my/following?p=1", "/signin", "/2fa", "/t/1", "/my/following?p=2").forEachIndexed { index, target ->
            enqueue(code = 302, location = target)
            assertTrue(target, runCatching { service.topics(1, "PB3_SESSION=fixture") }.isFailure)
            assertEquals(index + 1, server.requestCount)
        }
    }

    @Test fun invalidCredentialsDoNotMakeNetworkRequest(): Unit = runBlocking {
        listOf("", "session=fixture\r\nInjected: value").forEach {
            assertTrue(runCatching { service.topics(1, it) }.isFailure)
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun cancellationStopsInFlightGet(): Unit = runBlocking {
        server.enqueue(MockResponse.Builder().onResponseStart(mockwebserver3.SocketEffect.Stall).build())
        val job = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { service.topics(1, "fixture") }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        }
        job.cancel()
        try { job.await(); fail("Expected cancellation") } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(1, server.requestCount)
    }

    @Test fun forbiddenResponseAndUnknownBodyAreNotEmptyFeeds(): Unit = runBlocking {
        enqueue(code = 403)
        assertTrue(runCatching { service.topics(1, "fixture") }.isFailure)
        enqueue("<html>Unexpected page</html>")
        assertTrue(runCatching { service.topics(1, "fixture") }.isFailure)
    }
}
