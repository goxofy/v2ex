package com.vibe.v2ex.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class ImgurUploadServiceTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val image = UploadImage(byteArrayOf(1, 2, 3, 4), UploadImage.Encoding.JPEG)
    private val url = "https://i.imgur.com/v3lpwO1.jpeg"

    @Before fun setUp(): Unit { server = MockWebServer(); server.start() }
    @After fun tearDown(): Unit { server.close() }

    private fun service(base: OkHttpClient = client): ImgurUploadService =
        ImgurUploadService(base, json, server.url("/3/image"))

    private fun success(link: String = url): String = """{"success":true,"data":{"link":"$link","deletehash":"not-retained"}}"""

    private suspend fun failure(block: suspend () -> Unit): ImgurUploadException {
        try { block() } catch (error: ImgurUploadException) { return error }
        throw AssertionError("Expected upload failure")
    }

    @Test fun sendsMultipartWithoutInheritedCookiesTokensOrInterceptors(): Unit = runBlocking {
        val inherited = client.newBuilder()
            .cookieJar(object : CookieJar {
                override fun loadForRequest(url: HttpUrl): List<Cookie> =
                    listOf(Cookie.Builder().name("session").value("private-cookie").domain(url.host).build())
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>): Unit = Unit
            })
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer private-token").build()) }
            .addNetworkInterceptor { throw AssertionError("Inherited logging must not run") }
            .build()
        server.enqueue(MockResponse.Builder().body(success()).build())
        assertEquals(url, service(inherited).upload(image, " abc123 "))
        val request = checkNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        assertEquals("POST", request.method)
        assertEquals("/3/image", request.target)
        assertEquals("Client-ID abc123", request.headers["Authorization"])
        assertNull(request.headers["Cookie"])
        assertTrue(request.headers["Content-Type"].orEmpty().startsWith("multipart/form-data; boundary="))
        val body = request.body?.utf8().orEmpty()
        assertTrue(body.contains("name=\"image\"; filename=\"image.jpeg\""))
        assertTrue(body.contains("Content-Type: image/jpeg"))
        assertFalse(body.contains("private-"))
    }

    @Test fun invalidInputsDoNotMakeRequests(): Unit = runBlocking {
        for (id in listOf("", "Bearer abc", "a\r\nb", "a".repeat(129), "中文")) {
            assertEquals(ImgurUploadService.INVALID_CLIENT_ID, failure { service().upload(image, id) }.message)
        }
        failure { service().upload(UploadImage(byteArrayOf(), UploadImage.Encoding.PNG), "abc") }
        failure { service().upload(UploadImage(ByteArray(UploadImage.MAXIMUM_BYTES + 1), UploadImage.Encoding.PNG), "abc") }
        assertEquals(0, server.requestCount)
    }

    @Test fun httpFailuresAreNotRetriedOrRedirected(): Unit = runBlocking {
        for (code in listOf(401, 403, 413, 429, 500, 503, 302, 307)) {
            val before = server.requestCount
            server.enqueue(MockResponse.Builder().code(code).body(success())
                .addHeader("Location", server.url("/unexpected"))
                .addHeader("Retry-After", "0").build())
            failure { service().upload(image, "abc") }
            assertEquals(before + 1, server.requestCount)
        }
    }

    @Test fun malformedAndNonRawResponsesNeverSucceed(): Unit = runBlocking {
        val invalid = listOf("not-json", "{}", """{"success":false,"data":{"link":"$url"}}""",
            success("https://imgur.com/abc"), success("http://i.imgur.com/a.jpg"),
            success("https://i.imgur.com.evil.invalid/a.jpg"), success("https://i.imgur.com/a.jpg?x=1"),
            "x".repeat(1_024 * 1_024 + 1))
        for (body in invalid) {
            server.enqueue(MockResponse.Builder().body(body).build())
            assertEquals(ImgurUploadService.INVALID_RESPONSE, failure { service().upload(image, "abc") }.message)
        }
        assertEquals(invalid.size, server.requestCount)
    }

    @Test fun cancellationStopsInFlightRequest(): Unit = runBlocking {
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
        val result = async { service().upload(image, "abc") }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
        result.cancel()
        try { result.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals(1, server.requestCount)
    }

    @Test fun transportFailuresDoNotLeakMetadata(): Unit = runBlocking {
        for (error in listOf(IOException("private transport details"), SocketTimeoutException("private timeout"))) {
            val failing = client.newBuilder().dns { throw error }.build()
            val service = ImgurUploadService(failing, json, server.url("/3/image").newBuilder().host("upload.invalid").build())
            val message = failure { service.upload(image, "abc") }.message.orEmpty()
            assertFalse(message.contains("private"))
            assertTrue(message.contains("正文未修改"))
        }
    }
}
