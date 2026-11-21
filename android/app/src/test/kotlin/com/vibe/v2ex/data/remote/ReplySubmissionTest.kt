package com.vibe.v2ex.data.remote

import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReplySubmissionTest {
    private lateinit var server: MockWebServer
    private val requests: MutableList<RecordedRequest> = mutableListOf()
    private val waits: MutableList<Long> = mutableListOf()
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(1, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp(): Unit {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown(): Unit {
        collectRequests()
        server.close()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
        assertTrue("A reply must never be submitted twice", requests.count { it.method == "POST" } <= 1)
    }

    @Test
    fun mobileLayoutWithoutMainCanSubmitAndConfirm(): Unit = runBlocking {
        enqueue(mobilePage())
        enqueue(mobilePage(reply(101, "alice", "hello")))
        val result = submit()
        assertTrue("Mobile reply failed: ${result.exceptionOrNull()}", result.isSuccess)
        collectRequests()
        assertEquals(listOf("GET", "POST"), requests.map { it.method })
    }

    @Test
    fun mobilePaginationAndReplyConfirmationUseTheSameContentRegion(): Unit = runBlocking {
        enqueue(mobilePage("<input class='page_input' max='2'>"))
        enqueue(mobilePage(reply(200, "bob", "old last page") + "<input class='page_input' max='2'>"))
        redirect(302, "/t/42#reply3")
        enqueue(mobilePage("<a href='/t/42?p=3'>3</a>"))
        enqueue(mobilePage(reply(201, "alice", "hello") + "<input class='page_input' max='3'>"))
        val result = submit()
        assertTrue("Mobile pagination failed: ${result.exceptionOrNull()}", result.isSuccess)
        collectRequests()
        assertEquals(listOf("GET", "GET", "POST", "GET", "GET"), requests.map { it.method })
        assertEquals("/t/42?p=2", requests[1].target)
        assertEquals("/t/42?p=3", requests[4].target)
    }

    @Test
    fun savedAndroidUserAgentIsPreservedForFormPostAndConfirmation(): Unit = runBlocking {
        val browserAgent = "Mozilla/5.0 (Linux; Android 16; fixture; wv) AppleWebKit/537.36 Mobile Safari/537.36"
        enqueue(mobilePage())
        redirect(302, "/t/42#reply2")
        enqueue(mobilePage(reply(101, "alice", "hello")))
        assertTrue(submit(userAgent = browserAgent).isSuccess)
        collectRequests()
        assertEquals(listOf("GET", "POST", "GET"), requests.map { it.method })
        requests.forEach {
            assertEquals(browserAgent, it.headers["User-Agent"])
            assertEquals("A=session", it.headers["Cookie"])
        }
    }

    @Test
    fun legacySessionWithoutSavedUserAgentKeepsPreviousIdentity(): Unit = runBlocking {
        enqueue(mobilePage())
        enqueue(mobilePage(reply(101, "alice", "hello")))
        assertTrue(submit(userAgent = null).isSuccess)
        collectRequests()
        assertTrue(requests.all { it.headers["User-Agent"].orEmpty().contains("iPhone") })
    }

    @Test
    fun malformedUserAgentNeverSendsCredentials(): Unit = runBlocking {
        for (userAgent in listOf("", "invalid\r\nInjected: value", "non-ascii-中文")) {
            assertTrue(submit(userAgent = userAgent).isFailure)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun mobileOldMatchingReplyCannotConfirmANewSubmission(): Unit = runBlocking {
        val old = mobilePage(reply(500, "alice", "hello"))
        enqueue(old)
        repeat(4) { enqueue(old) }
        assertTrue(submit().exceptionOrNull() is ReplyUnconfirmedException)
        collectRequests()
        assertEquals(listOf("GET", "POST", "GET", "GET", "GET"), requests.map { it.method })
    }

    @Test
    fun htmlChallengeIsReportedInsteadOfMissingReplyForm(): Unit = runBlocking {
        for (html in listOf("<title>Just a moment...</title>", "<title>Attention Required!</title>", "<form id='challenge-form'></form>")) {
            enqueue(html)
            val message = submit().exceptionOrNull()?.message.orEmpty()
            assertTrue(message.contains("网页验证"))
            assertFalse(message.contains("回复表单"))
        }
        collectRequests()
        assertTrue(requests.all { it.method == "GET" })
    }

    @Test
    fun challengeHeaderRejectsOtherwiseValidReplyFormBeforePost(): Unit = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(mobilePage()).addHeader("cf-mitigated", "challenge").build())
        assertTrue(submit().exceptionOrNull()?.message.orEmpty().contains("网页验证"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun challengeAfterPostNeverRetriesSubmission(): Unit = runBlocking {
        enqueue(mobilePage())
        enqueue("<title>Just a moment...</title>")
        val message = submit().exceptionOrNull()?.message.orEmpty()
        assertTrue(message.contains("避免重复发送"))
        collectRequests()
        assertEquals(listOf("GET", "POST"), requests.map { it.method })
    }

    @Test
    fun direct200WithNewReplySucceeds(): Unit = runBlocking {
        enqueue(page())
        enqueue(page(reply(101, "alice", "hello")))
        assertTrue(submit().isSuccess)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun secondReadCanConfirmDelayedReply(): Unit = runBlocking {
        enqueue(page())
        enqueue(page())
        enqueue(page())
        enqueue(page(reply(101, "alice", "hello")))
        assertTrue(submit().isSuccess)
        collectRequests()
        assertEquals(listOf("GET", "POST", "GET", "GET"), requests.map { it.method })
        assertEquals(listOf(1000L), waits)
    }

    @Test
    fun thirdReadCanConfirmDelayedReply(): Unit = runBlocking {
        enqueue(page())
        enqueue(page())
        enqueue(page())
        enqueue(page())
        enqueue(page(reply(101, "alice", "hello")))
        assertTrue(submit().isSuccess)
        assertEquals(listOf(1000L, 2000L), waits)
        assertEquals(5, server.requestCount)
    }

    @Test
    fun forbiddenResponseNeverResubmits(): Unit = runBlocking {
        enqueue(page())
        enqueue("Forbidden", 403)
        enqueue(page())
        enqueue("Forbidden", 403)
        enqueue("<html>settings</html>")
        assertTrue(submit().isFailure)
        collectRequests()
        assertEquals(1, requests.count { it.method == "POST" })
        assertEquals(2, requests.size)
    }

    @Test
    fun temporaryReadFailureCanRecover(): Unit = runBlocking {
        enqueue(page())
        enqueue(page())
        enqueue("Unavailable", 503)
        enqueue(page(reply(101, "alice", "hello")))
        assertTrue(submit().isSuccess)
        assertEquals(listOf(1000L), waits)
    }

    @Test
    fun temporaryReadDisconnectCanRecover(): Unit = runBlocking {
        enqueue(page())
        enqueue(page())
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        enqueue(page(reply(101, "alice", "hello")))
        assertTrue(submit().isSuccess)
        assertEquals(listOf(1000L), waits)
    }

    @Test
    fun lostPostResponseIsConfirmedWithoutResubmission(): Unit = runBlocking {
        enqueue(page())
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        enqueue(page(reply(101, "alice", "hello")))
        assertTrue(submit().isSuccess)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun postTimeoutIsConfirmedWithoutResubmission(): Unit = runBlocking {
        enqueue(page())
        server.enqueue(MockResponse.Builder().headersDelay(1500, TimeUnit.MILLISECONDS).body(page()).build())
        enqueue(page(reply(101, "alice", "hello")))
        assertTrue(submit().isSuccess)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun unknownOutcomeKeepsDraftAndStopsAfterThreeReads(): Unit = runBlocking {
        assertUnconfirmed(page())
        assertEquals(listOf(1000L, 2000L), waits)
        assertEquals(5, server.requestCount)
    }

    @Test
    fun redirect301UsesGet(): Unit = runBlocking { assertRedirect(301) }

    @Test
    fun redirect302UsesGet(): Unit = runBlocking { assertRedirect(302) }

    @Test
    fun redirect303UsesGet(): Unit = runBlocking { assertRedirect(303) }

    @Test
    fun redirect307UsesGet(): Unit = runBlocking { assertRedirect(307) }

    @Test
    fun redirect308UsesGet(): Unit = runBlocking { assertRedirect(308) }

    @Test
    fun requestTimeoutStatusDoesNotReplayPost(): Unit = runBlocking { assertReadOnlyRecovery(408) }

    @Test
    fun retryAfterZeroDoesNotReplayPost(): Unit = runBlocking { assertReadOnlyRecovery(503) }

    @Test
    fun serverErrorCanStillBeConfirmed(): Unit = runBlocking { assertReadOnlyRecovery(500) }

    @Test
    fun missingRedirectLocationFallsBackToReadOnlyConfirmation(): Unit = runBlocking {
        enqueue(page())
        enqueue("", 302)
        enqueue(page(reply(101, "alice", "hello")))
        assertTrue(submit().isSuccess)
    }

    @Test
    fun crossOriginRedirectNeverReceivesCookies(): Unit = runBlocking {
        assertUnsafeRedirect("http://127.0.0.1:1/t/42")
    }

    @Test
    fun otherTopicRedirectIsNotFollowed(): Unit = runBlocking { assertUnsafeRedirect("/t/43") }

    @Test
    fun loginRedirectStopsConfirmation(): Unit = runBlocking { assertUnsafeRedirect("/signin") }

    @Test
    fun twoFactorRedirectStopsConfirmation(): Unit = runBlocking { assertUnsafeRedirect("/2fa/verify") }

    @Test
    fun readRedirectCannotEscapeTheTopic(): Unit = runBlocking {
        enqueue(page())
        enqueue(page())
        redirect(302, "https://example.invalid/t/42")
        assertTrue(submit().isFailure)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun redirectLoopIsBoundedAndNeverResubmits(): Unit = runBlocking {
        enqueue(page())
        redirect(302, "/t/42")
        repeat(18) { redirect(302, "/t/42") }
        val result = submit()
        assertTrue(result.exceptionOrNull() is ReplyUnconfirmedException)
        assertEquals(20, server.requestCount)
    }

    @Test
    fun authenticationChallengeDoesNotInvokeAuthenticator(): Unit = runBlocking {
        enqueue(page())
        server.enqueue(MockResponse.Builder().code(401).addHeader("WWW-Authenticate", "Basic realm=test").build())
        var authentications = 0
        val authenticatedClient = client.newBuilder().authenticator(Authenticator { _, response ->
            authentications++
            response.request.newBuilder().header("Authorization", "secret").build()
        }).build()
        assertTrue(submit(httpClient = authenticatedClient).isFailure)
        assertEquals(0, authentications)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun explicitProblemStopsReadOnlyConfirmation(): Unit = runBlocking {
        enqueue(page())
        enqueue(page("<div class='problem'>你上一次回复是在 10 秒前</div>"))
        val result = submit()
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("频繁"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun captchaDuringConfirmationStopsFurtherReads(): Unit = runBlocking {
        enqueue(page())
        enqueue(page())
        enqueue("<form><input name='captcha'></form>")
        assertTrue(submit().exceptionOrNull()?.message.orEmpty().contains("网页验证"))
        assertEquals(3, server.requestCount)
        assertTrue(waits.isEmpty())
    }

    @Test
    fun loginHtmlDuringConfirmationStopsFurtherReads(): Unit = runBlocking {
        enqueue(page())
        enqueue(page())
        enqueue("<form action='/signin'><input name='password'></form>")
        assertTrue(submit().isFailure)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun rateLimitStopsWithoutResubmission(): Unit = runBlocking {
        enqueue(page())
        enqueue("Too many requests", 429)
        assertTrue(submit().isFailure)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun cookieSnapshotHeadersAndSpecialCharactersArePreserved(): Unit = runBlocking {
        val text = "中文 + & = % <tag>\n@bob *literal* _value_"
        enqueue(page().replace("value=\"123\"", "value=\"token+&amp;=\""))
        enqueue(page(reply(101, "alice", "中文 + &amp; = % &lt;tag&gt;<br>@<a href='/member/bob'>bob</a> *literal* _value_")))
        var jarReads = 0
        var jarWrites = 0
        var intercepted = false
        val staleClient = client.newBuilder().cookieJar(object : CookieJar {
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                jarReads++
                return listOf(Cookie.Builder().name("A").value("stale").hostOnlyDomain(url.host).build())
            }
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>): Unit { jarWrites++ }
        }).addInterceptor { chain ->
            intercepted = true
            chain.proceed(chain.request())
        }.build()
        assertTrue(submit(text, httpClient = staleClient).isSuccess)
        collectRequests()
        assertEquals(0, jarReads)
        assertEquals(0, jarWrites)
        assertFalse(intercepted)
        for (request in requests) {
            assertEquals("A=session", request.headers["Cookie"])
            assertEquals(server.url("/").toString().trimEnd('/'), request.headers["Origin"])
            assertEquals(server.url("/t/42").toString(), request.headers["Referer"])
            assertTrue(request.headers["User-Agent"].orEmpty().contains("Mozilla"))
            assertTrue(request.headers["Cache-Control"].orEmpty().contains("no-cache"))
        }
        val body = requireNotNull(requests.single { it.method == "POST" }.body).utf8()
        val fields = body.split('&').associate { field ->
            val parts = field.split('=', limit = 2)
            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
        }
        assertEquals(mapOf("once" to "token+&=", "content" to text), fields)
    }

    @Test
    fun actualFormTokenAndActionQueryAreUsed(): Unit = runBlocking {
        enqueue(page().replace("<div id=\"Main\">", "<input name='once' value='decoy'><div id=\"Main\">")
            .replace("action=\"/t/42\"", "action=\"/t/42?p=2\""))
        enqueue(page(reply(101, "alice", "hello")))
        assertTrue(submit().isSuccess)
        collectRequests()
        val post = requests.single { it.method == "POST" }
        assertEquals("2", post.url.queryParameter("p"))
        assertTrue(post.body?.utf8().orEmpty().contains("once=123"))
    }

    @Test
    fun foreignFormActionIsRejectedBeforePosting(): Unit = runBlocking {
        assertInvalidForm(page().replace("action=\"/t/42\"", "action=\"https://example.invalid/t/42\""))
    }

    @Test
    fun duplicateOnceIsRejectedBeforePosting(): Unit = runBlocking {
        assertInvalidForm(page().replace("</form>", "<input name='once' value='456'></form>"))
    }

    @Test
    fun getFormIsRejectedBeforePosting(): Unit = runBlocking {
        assertInvalidForm(page().replace("method=\"post\"", "method=\"get\""))
    }

    @Test
    fun missingOnceIsRejectedBeforePosting(): Unit = runBlocking {
        assertInvalidForm(page().replace("name=\"once\"", "name=\"decoy\""))
    }

    @Test
    fun duplicateReplyFormsAreRejectedBeforePosting(): Unit = runBlocking {
        assertInvalidForm(page("<form method='post'><textarea name='content'></textarea><input name='once' value='456'></form>"))
    }

    @Test
    fun missingUsernamePreventsSubmission(): Unit = runBlocking {
        assertTrue(submit(username = null).isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun invalidSessionCookiePreventsSubmission(): Unit = runBlocking {
        assertTrue(submit(cookie = "A=session\r\nInjected: header").isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun emptyContentPreventsSubmission(): Unit = runBlocking {
        assertTrue(submit(" \n\t ").isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun invalidTopicPreventsSubmission(): Unit = runBlocking {
        assertTrue(ReplySubmission(client, server.url("/")).postReply(0, "hello", "A=session", "alice").isFailure)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun oldMatchingReplyCannotConfirm(): Unit = runBlocking {
        assertUnconfirmed(page(reply(99, "alice", "hello")))
    }

    @Test
    fun anotherAuthorCannotConfirm(): Unit = runBlocking {
        assertUnconfirmed(page(reply(101, "bob", "hello")))
    }

    @Test
    fun authorHrefMustAgreeWithVisibleName(): Unit = runBlocking {
        assertUnconfirmed(page(reply(101, "alice", "hello").replace("/member/alice", "/member/bob")))
    }

    @Test
    fun partialTextCannotConfirm(): Unit = runBlocking {
        assertUnconfirmed(page(reply(101, "alice", "hello extra")))
    }

    @Test
    fun literalMarkdownSymbolsAreNotDiscarded(): Unit = runBlocking {
        assertUnconfirmed(page(reply(101, "alice", "hello")), "*hello*")
    }

    @Test
    fun shorterUrlCannotConfirm(): Unit = runBlocking {
        assertUnconfirmed(page(reply(101, "alice", "https://example.com/a")), "https://example.com/ab")
    }

    @Test
    fun imageUrlSubstringCannotConfirm(): Unit = runBlocking {
        assertUnconfirmed(page(reply(101, "alice", "<img src='https://example.com/a'>")), "![](https://example.com/ab)")
    }

    @Test
    fun topicDraftAndScriptCannotFabricateAReply(): Unit = runBlocking {
        val fake = reply(101, "alice", "hello")
        assertUnconfirmed(page("<div class='topic_content'>$fake</div><script>$fake</script><textarea>$fake</textarea>"))
    }

    @Test
    fun nestedUserContentCannotFabricateAReply(): Unit = runBlocking {
        assertUnconfirmed(page(reply(102, "bob", reply(101, "alice", "hello"))))
    }

    @Test
    fun duplicateReplyIdsCannotConfirm(): Unit = runBlocking {
        assertUnconfirmed(page(reply(101, "alice", "hello") + reply(101, "bob", "other")))
    }

    @Test
    fun unparseableOldRowStillRaisesBaseline(): Unit = runBlocking {
        enqueue(page("<div class='cell' id='r_500'>unparseable old row</div>"))
        val candidate = page(reply(400, "alice", "hello"))
        repeat(4) { enqueue(candidate) }
        assertTrue(submit().exceptionOrNull() is ReplyUnconfirmedException)
    }

    @Test
    fun userContentCannotFabricateErrorsOrPagination(): Unit = runBlocking {
        val noise = "<div class='problem'>你上一次回复是在</div><input name='captcha'><a href='/t/42?p=999'>last</a>"
        enqueue(page(reply(99, "bob", noise)))
        enqueue(page(reply(99, "bob", noise) + reply(101, "alice", "hello")))
        assertTrue(submit().isSuccess)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun literalCooldownMessageInReplyIsNotAnError(): Unit = runBlocking {
        enqueue(page())
        enqueue(page(reply(101, "alice", "你上一次回复是在")))
        assertTrue(submit("你上一次回复是在").isSuccess)
    }

    @Test
    fun usernameMatchingIsCaseInsensitive(): Unit = runBlocking {
        enqueue(page())
        enqueue(page(reply(101, "Alice", "hello")))
        assertTrue(submit().isSuccess)
    }

    @Test
    fun htmlEntitiesWhitespaceAndMentionsMatch(): Unit = runBlocking {
        enqueue(page())
        enqueue(page(reply(101, "alice", "@<a href='/member/bob'>bob</a>&nbsp;&amp; &#x4f60;&#x597d;<br>next")))
        assertTrue(submit("@bob & 你好\nnext").isSuccess)
    }

    @Test
    fun newestPageIsReadBeforePostAndAgainAfterPageCountChanges(): Unit = runBlocking {
        enqueue(page("<input class='page_input' max='2'>"))
        enqueue(page(reply(200, "bob", "old last page") + "<input class='page_input' max='2'>"))
        enqueue(page())
        enqueue(page("<input class='page_input' max='3'>"))
        enqueue(page(reply(201, "alice", "hello") + "<input class='page_input' max='3'>"))
        assertTrue(submit().isSuccess)
        collectRequests()
        assertEquals(listOf(null, "2", null, null, "3"), requests.map { it.url.queryParameter("p") })
        assertEquals(listOf("GET", "GET", "POST", "GET", "GET"), requests.map { it.method })
    }

    @Test
    fun otherTopicsPaginationDoesNotAffectBaseline(): Unit = runBlocking {
        enqueue(page("<a href='/t/43?p=999'>other</a>"))
        enqueue(page(reply(101, "alice", "hello")))
        assertTrue(submit().isSuccess)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun cancellingInFlightPostCancelsCallAndPropagates(): Unit = runBlocking {
        enqueue(page())
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
        val cancelled = CountDownLatch(1)
        val cancellableClient = client.newBuilder().eventListener(object : EventListener() {
            override fun canceled(call: Call): Unit { cancelled.countDown() }
        }).build()
        val job = async(start = CoroutineStart.UNDISPATCHED) { submit(httpClient = cancellableClient) }
        requests.add(requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)))
        requests.add(requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)))
        withTimeout(1000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertTrue(cancelled.await(1, TimeUnit.SECONDS))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun cancellationDuringRetryWaitDoesNotBecomeFailureResult(): Unit = runBlocking {
        enqueue(page())
        enqueue(page())
        enqueue(page())
        val waiting = CountDownLatch(1)
        val submission = ReplySubmission(client, server.url("/")) {
            waiting.countDown()
            awaitCancellation()
        }
        val job = async(start = CoroutineStart.UNDISPATCHED) { submission.postReply(42, "hello", "A=session", "alice") }
        repeat(3) { requests.add(requireNotNull(server.takeRequest(2, TimeUnit.SECONDS))) }
        assertTrue(waiting.await(1, TimeUnit.SECONDS))
        withTimeout(1000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun explicitCancellationIsNotSwallowed(): Unit = runBlocking {
        enqueue(page())
        enqueue(page())
        enqueue(page())
        val submission = ReplySubmission(client, server.url("/")) { throw CancellationException("test") }
        var cancelled = false
        try {
            submission.postReply(42, "hello", "A=session", "alice")
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun matchingLinkLabelWithDifferentTargetCannotConfirm(): Unit = runBlocking {
        assertUnconfirmed(
            page(reply(101, "alice", "<a href='https://example.com/other'>https://example.com/a</a>")),
            "https://example.com/a",
        )
    }

    @Test
    fun completePlainUrlLinkCanConfirm(): Unit = runBlocking {
        enqueue(page())
        enqueue(page(reply(101, "alice", "<a href='https://example.com/a?b=1&amp;c=2'>https://example.com/a?b=1&amp;c=2</a>")))
        assertTrue(submit("https://example.com/a?b=1&c=2").isSuccess)
    }

    @Test
    fun confirmationRequestHasFiveSecondDeadline(): Unit = runBlocking {
        enqueue(page())
        enqueue(page())
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).build())
        enqueue(page(reply(101, "alice", "hello")))
        val patientClient = client.newBuilder().readTimeout(30, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
        withTimeout(8000) { assertTrue(submit(httpClient = patientClient).isSuccess) }
        assertEquals(listOf(1000L), waits)
    }

    @Test
    fun transportMetadataNeverLeaksIntoDisplayedErrors(): Unit = runBlocking {
        enqueue(page())
        repeat(4) { server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build()) }
        val error = submit("private draft").exceptionOrNull()
        assertTrue(error is ReplyUnconfirmedException)
        assertFalse(error?.message.orEmpty().contains("private draft"))
        assertFalse(error?.message.orEmpty().contains("A=session"))
        assertTrue(error?.cause == null)
    }

    private suspend fun assertUnconfirmed(response: String, content: String = "hello"): Unit {
        enqueue(page())
        repeat(4) { enqueue(response) }
        val error = submit(content).exceptionOrNull()
        assertTrue("Expected unknown outcome, got $error", error is ReplyUnconfirmedException)
        assertTrue(error?.message.orEmpty().contains("草稿已保留"))
        assertTrue(error?.message.orEmpty().contains("避免重复发送"))
    }

    private suspend fun assertInvalidForm(html: String): Unit {
        enqueue(html)
        assertTrue(submit().isFailure)
        collectRequests()
        assertEquals(listOf("GET"), requests.map { it.method })
    }

    private suspend fun assertRedirect(code: Int): Unit {
        enqueue(page())
        redirect(code, "/t/42?p=1#reply2")
        enqueue(page(reply(101, "alice", "hello")))
        assertTrue(submit().isSuccess)
        collectRequests()
        assertEquals(listOf("GET", "POST", "GET"), requests.map { it.method })
    }

    private suspend fun assertUnsafeRedirect(location: String): Unit {
        enqueue(page())
        redirect(302, location)
        assertTrue(submit().isFailure)
        assertEquals(2, server.requestCount)
    }

    private suspend fun assertReadOnlyRecovery(code: Int): Unit {
        enqueue(page())
        server.enqueue(MockResponse.Builder().code(code).addHeader("Retry-After", "0").build())
        enqueue(page(reply(101, "alice", "hello")))
        assertTrue(submit().isSuccess)
        collectRequests()
        assertEquals(listOf("GET", "POST", "GET"), requests.map { it.method })
    }

    private suspend fun submit(
        content: String = "hello",
        cookie: String? = "A=session",
        username: String? = "alice",
        httpClient: OkHttpClient = client,
        userAgent: String? = null,
    ): Result<Unit> = ReplySubmission(httpClient, server.url("/")) { waits.add(it) }
        .postReply(42, content, cookie, username, userAgent)

    private fun redirect(code: Int, location: String): Unit {
        server.enqueue(MockResponse.Builder().code(code).addHeader("Location", location).build())
    }

    private fun enqueue(html: String, code: Int = 200): Unit {
        server.enqueue(MockResponse.Builder().code(code).body(html).build())
    }

    private fun collectRequests(): Unit {
        while (requests.size < server.requestCount) {
            requests.add(requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)))
        }
    }

    private fun reply(id: Long, author: String, content: String): String =
        """<div class="cell" id="r_$id"><strong><a href="/member/$author">$author</a></strong><span class="no">1</span><div class="reply_content">$content</div></div>"""

    private fun mobilePage(extra: String = ""): String = page(extra)
        .replace("<div id=\"Main\">", "<div id=\"Wrapper\"><div class=\"content\">")
        .replace("</body>", "</div></body>")

    private fun page(extra: String = ""): String =
        """<html><body><div id="Main"><div class="box">${reply(100, "bob", "old")}$extra</div><div class="box"><form method="post" action="/t/42"><input name="once" value="123"><textarea name="content"></textarea></form></div></div></body></html>"""
}
