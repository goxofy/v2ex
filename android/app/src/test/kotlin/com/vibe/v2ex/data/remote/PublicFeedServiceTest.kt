package com.vibe.v2ex.data.remote

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.Authenticator
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PublicFeedServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var service: PublicFeedService
    private val client: OkHttpClient = OkHttpClient()

    @Before fun setup(): Unit {
        server = MockWebServer()
        server.start()
        service = PublicFeedService(client, server.url("/"))
    }

    @After fun teardown(): Unit {
        server.close()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    @Test fun allRefreshFetchesNewRowsWithANewCacheKey(): Unit = runBlocking {
        enqueue(page(row(1)), cacheable = true)
        enqueue(page(row(2)), cacheable = true)
        assertEquals(listOf(1L), service.topics(null, 1).getOrThrow().topics.map { it.id })
        assertEquals(listOf(2L), service.topics(null, 1).getOrThrow().topics.map { it.id })
        val first = request()
        val second = request()
        assertEquals("/recent", first.url.encodedPath)
        assertEquals("1", first.url.queryParameter("p"))
        assertEquals("1", second.url.queryParameter("p"))
        assertFalse(first.url.queryParameter("_").isNullOrBlank())
        assertNotEquals(first.url.queryParameter("_"), second.url.queryParameter("_"))
        assertEquals("no-cache, no-store", second.headers["Cache-Control"])
        assertEquals("no-cache", second.headers["Pragma"])
    }

    @Test fun r2RefreshFetchesNewRankingWithoutSortingById(): Unit = runBlocking {
        enqueue(page(row(9) + row(3)))
        enqueue(page(row(4) + row(10)))
        assertEquals(listOf(9L, 3L), service.rankedTopics(PublicFeedService.Ranking.R2).getOrThrow().map { it.id })
        assertEquals(listOf(4L, 10L), service.rankedTopics(PublicFeedService.Ranking.R2).getOrThrow().map { it.id })
        val first = request()
        val second = request()
        assertEquals("r2", first.url.queryParameter("tab"))
        assertEquals("r2", second.url.queryParameter("tab"))
        assertEquals("/", second.url.encodedPath)
        assertNotEquals(first.url.queryParameter("_"), second.url.queryParameter("_"))
    }

    @Test fun nodePaginationAndFallbackNodeRemainAvailable(): Unit = runBlocking {
        enqueue(page(row(4) + "<a href='https://www.v2ex.com/go/programmer?p=3'>Next</a>"))
        val result = service.topics("programmer", 2).getOrThrow()
        assertEquals("programmer", result.topics.single().node?.name)
        assertTrue(result.hasMore)
        val sent = request()
        assertEquals("/go/programmer", sent.url.encodedPath)
        assertEquals("2", sent.url.queryParameter("p"))
    }

    @Test fun hotUsesTheSameAnonymousBrowserIdentity(): Unit = runBlocking {
        enqueue(page(row(1)))
        enqueue(page(row(2)))
        assertTrue(service.topics(null, 1).isSuccess)
        assertTrue(service.rankedTopics(PublicFeedService.Ranking.HOT).isSuccess)
        val all = request()
        val hot = request()
        assertEquals("hot", hot.url.queryParameter("tab"))
        assertEquals(all.headers["User-Agent"], hot.headers["User-Agent"])
        assertTrue(hot.headers["User-Agent"].orEmpty().contains("Mozilla/5.0"))
        assertNull(hot.headers["Cookie"])
    }

    @Test fun inheritedCredentialsLoggingAndAuthenticationAreNotUsed(): Unit = runBlocking {
        val inherited = client.newBuilder()
            .cookieJar(object : CookieJar {
                override fun loadForRequest(url: HttpUrl): List<Cookie> = error("Cookie jar must not be read")
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>): Unit = error("Cookie jar must not be written")
            })
            .addInterceptor { error("Authenticated interceptor must not run") }
            .addNetworkInterceptor { error("Logging interceptor must not run") }
            .authenticator(Authenticator { _, _ -> error("Authenticator must not run") })
            .build()
        val isolated = PublicFeedService(inherited, server.url("/"))
        server.enqueue(MockResponse.Builder().body(page(row(1))).addHeader("Set-Cookie", "session=fixture; Path=/").build())
        assertTrue(isolated.rankedTopics(PublicFeedService.Ranking.R2).isSuccess)
        enqueue("private server detail", 401)
        assertTrue(isolated.topics(null, 1).isFailure)
        repeat(2) {
            val sent = request()
            assertEquals("GET", sent.method)
            assertNull(sent.headers["Cookie"])
            assertNull(sent.headers["Authorization"])
        }
    }

    @Test fun failedRefreshNeverReturnsAnEarlierSuccessfulList(): Unit = runBlocking {
        enqueue(page(row(1)))
        enqueue(page(row(1)), 503)
        assertTrue(service.topics(null, 1).isSuccess)
        val failure = service.topics(null, 1)
        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull()?.message.orEmpty().contains("503"))
        assertEquals(2, server.requestCount)
    }

    @Test fun explicitChallengeAndLoginPagesCannotBecomeFeeds(): Unit = runBlocking {
        for (html in listOf(
            "<title>Just a moment...</title>",
            "<title>Attention Required!</title>",
            "<form id='challenge-form'></form>",
            "<input type='password'>",
        )) {
            enqueue(html + page(row(1)))
            assertTrue(service.rankedTopics(PublicFeedService.Ranking.R2).isFailure)
        }
        assertEquals(4, server.requestCount)
    }

    @Test fun challengeHeaderCannotBeMistakenForSuccessfulRows(): Unit = runBlocking {
        server.enqueue(MockResponse.Builder().body(page(row(1))).addHeader("cf-mitigated", "challenge").build())
        val error = service.topics(null, 1).exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("网页验证"))
    }

    @Test fun invalidStatusNeverAcceptsEvenWellFormedTopics(): Unit = runBlocking {
        for (code in listOf(401, 403, 404, 408, 429, 500, 503)) {
            enqueue(page(row(1)), code)
            assertTrue(service.topics(null, 1).isFailure)
        }
        assertEquals(7, server.requestCount)
    }

    @Test fun unknownOrAmbiguousPagesAreNotSuccessfulEmptyLists(): Unit = runBlocking {
        for (html in listOf("", "<html>unexpected response</html>", row(1), page(""), page(row(1)) + page(row(2)), page("<div class='problem'>Server error</div>" + row(1)))) {
            enqueue(html)
            assertTrue(service.topics(null, 1).isFailure)
        }
    }

    @Test fun mobileContentExcludesSidebarAndUserSuppliedRows(): Unit = runBlocking {
        enqueue("<div id='Rightbar'>${row(99)}</div><div id='Wrapper'><div class='content'>${row(1)}<div class='reply_content'>${row(98)}</div></div></div>")
        assertEquals(listOf(1L), service.rankedTopics(PublicFeedService.Ranking.R2).getOrThrow().map { it.id })
    }

    @Test fun canonicalRedirectKeepsFreshnessKey(): Unit = runBlocking {
        enqueue(code = 302, location = "/recent")
        enqueue(page(row(1)))
        assertTrue(service.topics(null, 1).isSuccess)
        val first = request()
        val second = request()
        assertEquals("/recent", second.url.encodedPath)
        assertEquals(first.url.queryParameter("_"), second.url.queryParameter("_"))
    }

    @Test fun redirectsCannotChangeFeedOrSendRequestsToAnotherOrigin(): Unit = runBlocking {
        for (target in listOf("https://other.invalid/recent", "/signin", "/2fa", "/recent?p=2", "/go/programmer?p=1")) {
            enqueue(code = 302, location = target)
            assertTrue(service.topics(null, 1).isFailure)
        }
        enqueue(code = 302, location = "/?tab=hot")
        assertTrue(service.rankedTopics(PublicFeedService.Ranking.R2).isFailure)
        assertEquals(6, server.requestCount)
    }

    @Test fun redirectLoopsAreBounded(): Unit = runBlocking {
        repeat(3) { enqueue(code = 302, location = "/recent?p=1") }
        assertTrue(service.topics(null, 1).isFailure)
        assertEquals(3, server.requestCount)
    }

    @Test fun invalidInputDoesNotMakeNetworkRequests(): Unit = runBlocking {
        for (node in listOf("", "../recent", "node?x=1", "node\r\ninjected")) {
            assertTrue(service.topics(node, 1).isFailure)
        }
        for (page in listOf(-1, 0, Int.MAX_VALUE)) assertTrue(service.topics(null, page).isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test fun cancellationStopsTheInFlightRequest(): Unit = runBlocking {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
        val job = async { service.topics(null, 1) }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
        job.cancel()
        try {
            job.await()
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(1, server.requestCount)
    }

    @Test fun networkFailuresDoNotLeakTransportMetadata(): Unit = runBlocking {
        val broken = client.newBuilder().dns { throw IOException("private transport metadata") }.build()
        val result = PublicFeedService(broken, server.url("/").newBuilder().host("feed.invalid").build()).topics(null, 1)
        assertTrue(result.isFailure)
        assertFalse(result.exceptionOrNull()?.message.orEmpty().contains("private transport metadata"))
    }

    private fun request(): RecordedRequest = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS))

    private fun row(id: Int): String = """<div class="cell item"><span class="item_title"><a class="topic-link" href="/t/$id#reply1">Topic $id</a></span><strong><a href="/member/fixture">fixture</a></strong></div>"""
    private fun page(rows: String): String = "<div id='Main'><div class='box'>$rows</div></div>"
    private fun enqueue(body: String = "", code: Int = 200, location: String? = null, cacheable: Boolean = false): Unit {
        server.enqueue(MockResponse.Builder().code(code).body(body).apply {
            location?.let { addHeader("Location", it) }
            if (cacheable) addHeader("Cache-Control", "public, max-age=86400")
        }.build())
    }
}
