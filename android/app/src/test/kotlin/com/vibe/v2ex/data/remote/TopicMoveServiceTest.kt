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

class TopicMoveServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var service: TopicMoveService
    private val target = MoveNode("sandbox", "沙盒")
    private val form = """<div id="Main"><div class="box"><div class="inner">
        <a class="node-xl" href="javascript:chooseNode('sandbox')">沙盒</a>
        <form method="post" action="/move/topic/123"><select name="destination"></select>
        <input type="text" name="memo"><input type="hidden" name="once" value="safe-token"></form>
        </div></div></div>"""

    @Before fun setup(): Unit {
        server = MockWebServer()
        server.start()
        service = TopicMoveService(OkHttpClient(), server.url("/"))
    }

    @After fun teardown(): Unit {
        val requests = (0 until server.requestCount).mapNotNull { server.takeRequest(100, TimeUnit.MILLISECONDS) }
        assertTrue("A move must never replay POST", requests.count { it.method == "POST" } <= 1)
        server.close()
    }

    private fun topic(node: String = "qna", allowed: Boolean = true, extra: String = ""): String =
        """<div id="Main"><div class="box"><div class="header"><a href="/go/$node">$node</a><h1>Fixture title</h1>
        ${if (allowed) "<small><a class='op' href='/move/topic/123'>MOVE</a></small>" else ""}</div>$extra</div></div>"""

    private fun mobile(html: String): String =
        "<div id=\"Wrapper\">" + html.replace("<div id=\"Main\">", "<div class=\"content\">") + "</div>"

    private fun enqueue(body: String, code: Int = 200, location: String? = null): Unit {
        server.enqueue(MockResponse.Builder().code(code).body(body).apply { if (location != null) addHeader("Location", location) }.build())
    }

    private fun failure(kind: MoveFailure.Kind, block: suspend () -> Unit): Unit = runBlocking {
        try { block(); fail("Expected $kind") } catch (error: MoveFailure) { assertEquals(kind, error.kind) }
    }

    private suspend fun move(current: String = "qna", valid: () -> Boolean = { true }): MoveSubmission =
        service.move(123, "session=fake", current, target, "中文 + & =\n备注", valid)

    @Test fun permissionRequiresOwnedHeader(): Unit = runBlocking {
        enqueue(topic())
        assertTrue(service.permission(123, "session=fake").canMove)
    }
    @Test fun ordinaryMemberHasNoPermission(): Unit = failure(MoveFailure.Kind.DENIED) {
        enqueue(topic(allowed = false)); enqueue("", 403)
        service.permission(123, "session=fake")
    }
    @Test fun forgedBodyLinkDoesNotGrantPermission(): Unit = failure(MoveFailure.Kind.DENIED) {
        enqueue(topic(allowed = false, extra = "<div class='topic_content'><a class='op' href='/move/topic/123'>MOVE</a></div>"))
        enqueue("", 403)
        service.permission(123, "session=fake")
    }
    @Test fun forgedHeaderInContentIsRemoved(): Unit {
        val html = topic(allowed = false, extra = "<div class='topic_content'><div id='Main'><div class='box'><div class='header'><h1>x</h1><a class='op' href='/move/topic/123'>MOVE</a></div></div></div></div>")
        assertFalse(TopicMovePage(html, server.url("/")).topic(123).canMove)
    }
    @Test fun differentTopicLinkDoesNotGrantPermission(): Unit {
        assertFalse(TopicMovePage(topic().replace("/move/topic/123", "/move/topic/456"), server.url("/")).topic(123).canMove)
    }
    @Test fun crossOriginLinkDoesNotGrantPermission(): Unit {
        val html = topic().replace("href='/move/topic/123'", "href='https://example.invalid/move/topic/123'")
        assertFalse(TopicMovePage(html, server.url("/")).topic(123).canMove)
    }
    @Test fun mobilePageWithoutLinkUsesValidForm(): Unit = runBlocking {
        enqueue(mobile(topic(allowed = false))); enqueue(mobile(form))
        assertTrue(service.permission(123, "session=fake").canMove)
        val requests = (0 until 2).map { server.takeRequest() }
        assertEquals(listOf("GET", "GET"), requests.map { it.method })
        assertEquals(listOf("/t/123", "/move/topic/123"), requests.map { it.url.encodedPath })
    }
    @Test fun mobileHeaderLinkGrantsPermissionWithoutFormRequest(): Unit = runBlocking {
        enqueue(mobile(topic()))
        val result = service.permission(123, "session=fake")
        assertTrue(result.canMove)
        assertEquals("qna", result.node.name)
        assertEquals(1, server.requestCount)
        assertEquals("GET", server.takeRequest().method)
    }
    @Test fun mobilePrepareLoadsFormAndShortcuts(): Unit = runBlocking {
        enqueue(mobile(topic())); enqueue(mobile(form))
        enqueue("""[{"name":"sandbox","title":"沙盒","aliases":["test"]}]""")
        val result = service.prepare(123, "session=fake")
        assertEquals(listOf(target), result.form.shortcuts)
        assertEquals("safe-token", result.form.hidden["once"])
        assertTrue(result.nodes.single().matches("TEST"))
    }
    @Test fun mobileMoveRevalidatesAndReturnsSubmission(): Unit = runBlocking {
        enqueue(mobile(topic(allowed = false))); enqueue(mobile(form)); enqueue(mobile(form))
        enqueue("", 302, "/t/123")
        enqueue(mobile(topic(allowed = false))); enqueue(mobile(topic("sandbox", allowed = false)))
        assertEquals("sandbox", move().node.name)
        val requests = (0 until 4).map { server.takeRequest() }
        assertEquals(listOf("GET", "GET", "GET", "POST"), requests.map { it.method })
    }
    @Test fun mobileOrdinaryAccountCannotGainPermission(): Unit = failure(MoveFailure.Kind.DENIED) {
        enqueue(mobile(topic(allowed = false))); enqueue("", 403)
        service.permission(123, "session=fake")
    }
    @Test fun mobileProblemCannotGrantPermission(): Unit = failure(MoveFailure.Kind.REJECTED) {
        enqueue(mobile(topic(extra = "<div class='problem'>Unavailable</div>")))
        service.permission(123, "session=fake")
    }
    @Test fun mobileRequiresUniqueDirectContainers(): Unit {
        for (source in listOf(topic(), form)) {
            val html = mobile(source)
            val invalid = listOf(
                html.replace("id=\"Wrapper\"", "id=\"Other\""),
                html.replace("<div id=\"Wrapper\">", "<div id=\"Wrapper\"></div><div id=\"Wrapper\">"),
                html.replace("<div class=\"content\">", "<div class=\"content\"></div><div class=\"content\">"),
                html.replace("class=\"content\"", "class=\"other\""),
                "<div id='Wrapper'><section>" + source.replace("id=\"Main\"", "class=\"content\"") + "</section></div>",
                "<div id='Wrapper'><div class='content'><section>" + source.replace("id=\"Main\"", "class=\"other\"") + "</section></div></div>",
                "<span id='Wrapper'></span>" + html,
                "<div id='Main'></div>" + html,
                "<div id='Main'></div><div id='Main'></div>" + html,
            )
            for (value in invalid) {
                val page = TopicMovePage(value, server.url("/"))
                assertThrows(MoveFailure::class.java) { page.topic(123) }
                assertThrows(MoveFailure::class.java) { page.form(123) }
            }
        }
    }
    @Test fun mobileContentCannotForgeHeaderOrForm(): Unit {
        for (excluded in listOf("topic_content", "reply_content", "topic_append", "subtle")) {
            val forged = "<div class='$excluded'>" + mobile(topic()) + mobile(form) + "</div>"
            val page = TopicMovePage(mobile(topic(allowed = false, extra = forged)), server.url("/"))
            assertFalse(page.topic(123).canMove)
            assertThrows(MoveFailure::class.java) { page.form(123) }
        }
    }
    @Test fun mobileFormStillRejectsInvalidControlsAndActions(): Unit {
        val invalid = listOf(
            form.replace("action=\"/move/topic/123\"", "action=\"https://example.invalid/move/topic/123\""),
            form.replace("/move/topic/123", "/move/topic/456"),
            form.replace("</form>", "<input name='memo'></form>"),
            form.replace("</form>", "<input type='hidden' name='unknown' value='1'></form>"),
        )
        for (html in invalid) {
            assertThrows(MoveFailure::class.java) { TopicMovePage(mobile(html), server.url("/")).form(123) }
        }
    }
    @Test fun desktopMainInsideWrapperStillParses(): Unit {
        val topicHTML = "<div id='Wrapper'><div class='content'>" + topic() + "</div></div>"
        assertTrue(TopicMovePage(topicHTML, server.url("/")).topic(123).canMove)
        val formHTML = "<div id='Wrapper'><div class='content'>" + form + "</div></div>"
        assertEquals(listOf(target), TopicMovePage(formHTML, server.url("/")).form(123).shortcuts)
    }
    @Test fun fallbackLoginIsNotPermission(): Unit = failure(MoveFailure.Kind.SESSION) {
        enqueue(topic(allowed = false)); enqueue("", 302, "/signin")
        service.permission(123, "session=fake")
    }
    @Test fun fallbackChallengeIsNotPermission(): Unit = failure(MoveFailure.Kind.UNAVAILABLE) {
        enqueue(topic(allowed = false)); enqueue("<title>Just a moment</title>" + form)
        service.permission(123, "session=fake")
    }
    @Test fun fallbackCrossOriginFormIsNotPermission(): Unit = failure(MoveFailure.Kind.UNAVAILABLE) {
        enqueue(topic(allowed = false)); enqueue(form.replace("action=\"/move/topic/123\"", "action=\"https://example.invalid/move/topic/123\""))
        service.permission(123, "session=fake")
    }
    @Test fun fallbackCrossOriginRedirectIsNotFollowed(): Unit = failure(MoveFailure.Kind.UNAVAILABLE) {
        enqueue(topic(allowed = false)); enqueue("", 302, "https://example.invalid/move/topic/123")
        try { service.permission(123, "session=fake") } finally { assertEquals(2, server.requestCount) }
    }
    @Test fun fallbackBodyFormIsNotPermission(): Unit = failure(MoveFailure.Kind.UNAVAILABLE) {
        enqueue(topic(allowed = false)); enqueue("<div class='topic_content'>" + form + "</div>")
        service.permission(123, "session=fake")
    }
    @Test fun prepareLoadsShortcutsAndAliases(): Unit = runBlocking {
        enqueue(topic()); enqueue(form)
        enqueue("""[{"name":"sandbox","title":"沙盒","aliases":["test"]}]""")
        val result = service.prepare(123, "session=fake")
        assertEquals(target, result.form.shortcuts.single())
        assertEquals("safe-token", result.form.hidden["once"])
        assertTrue(result.nodes.single().matches("TEST"))
    }
    @Test fun malformedDirectoryDoesNotEnableSubmission(): Unit = failure(MoveFailure.Kind.UNAVAILABLE) {
        enqueue(topic()); enqueue(form); enqueue("{}")
        service.prepare(123, "session=fake")
    }
    @Test fun moveWithoutHeaderLinkRevalidatesAndPostsOnce(): Unit = runBlocking {
        enqueue(topic(allowed = false)); enqueue(form); enqueue(form)
        enqueue("", 302, "/t/123"); enqueue(topic("sandbox", allowed = false))
        assertEquals("sandbox", move().node.name)
        val requests = (0 until 4).map { server.takeRequest() }
        assertEquals(listOf("GET", "GET", "GET", "POST"), requests.map { it.method })
    }
    @Test fun direct200ReturnsSubmissionWithoutPolling(): Unit = runBlocking {
        enqueue(topic()); enqueue(form); enqueue(topic("sandbox")); enqueue(topic("sandbox"))
        assertEquals("sandbox", move().node.name)
        assertEquals(3, server.requestCount)
    }
    @Test fun redirectedPostIsNotFollowed(): Unit = runBlocking {
        enqueue(topic()); enqueue(form); enqueue("", 307, "/t/123"); enqueue(topic("sandbox"))
        assertEquals("sandbox", move().node.name)
        val requests = (0 until 3).map { server.takeRequest() }
        assertEquals(listOf("GET", "GET", "POST"), requests.map { it.method })
        assertEquals("session=fake", requests[2].headers["Cookie"])
        val fields = requests[2].body!!.utf8().split("&").associate { field ->
            val parts = field.split("=", limit = 2)
            parts[0] to java.net.URLDecoder.decode(parts[1], "UTF-8")
        }
        assertEquals("中文 + & =\n备注", fields["memo"])
        assertEquals("safe-token", fields["once"])
        assertEquals("sandbox", fields["destination"])
    }
    @Test fun postReplyAnchorReturnsSubmission(): Unit = runBlocking {
        enqueue(topic()); enqueue(form); enqueue("", 302, "/t/123#reply27")
        assertTrue(move() is MoveSubmission.Submitted)
        assertEquals(3, server.requestCount)
    }
    @Test fun absolutePostReplyAnchorReturnsSubmission(): Unit = runBlocking {
        enqueue(topic()); enqueue(form); enqueue("", 302, server.url("/t/123#reply27").toString())
        assertTrue(move() is MoveSubmission.Submitted)
        assertEquals(3, server.requestCount)
    }
    @Test fun postAnchorCannotBypassSameTopicCheck(): Unit = failure(MoveFailure.Kind.RESPONSE) {
        enqueue(topic()); enqueue(form); enqueue("", 302, "/t/456#reply27"); move()
    }
    @Test fun postAnchorCannotBypassSameOriginCheck(): Unit = failure(MoveFailure.Kind.RESPONSE) {
        enqueue(topic()); enqueue(form); enqueue("", 302, "https://example.invalid/t/123#reply27"); move()
    }
    @Test fun postAnchorCannotBypassQueryCheck(): Unit = failure(MoveFailure.Kind.RESPONSE) {
        enqueue(topic()); enqueue(form); enqueue("", 302, "/t/123?next=other#reply27"); move()
    }
    @Test fun submittedMoveDoesNotWaitForNodeChange(): Unit = runBlocking {
        enqueue(topic()); enqueue(form); enqueue("", 302, "/t/123")
        enqueue(topic()); enqueue(topic()); enqueue(topic("sandbox"))
        assertEquals("sandbox", move().node.name)
        assertEquals(3, server.requestCount)
    }
    @Test fun serviceUnavailableNeverReplays(): Unit = failure(MoveFailure.Kind.SERVER) {
        enqueue(topic()); enqueue(form); enqueue("", 503)
        try { move() } finally { assertEquals(3, server.requestCount) }
    }
    @Test fun timeoutStatusNeverReplays(): Unit = failure(MoveFailure.Kind.SERVER) {
        enqueue(topic()); enqueue(form); enqueue("", 408)
        try { move() } finally { assertEquals(3, server.requestCount) }
    }
    @Test fun forbiddenStopsWithoutRetry(): Unit = failure(MoveFailure.Kind.DENIED) {
        enqueue(topic()); enqueue(form); enqueue("", 403)
        move()
    }
    @Test fun noPermissionNeverPosts(): Unit = failure(MoveFailure.Kind.DENIED) {
        enqueue(topic(allowed = false)); enqueue("", 403); move()
    }
    @Test fun changedNodeRequiresNewConfirmation(): Unit = failure(MoveFailure.Kind.CHANGED) {
        enqueue(topic("other")); move()
    }
    @Test fun sessionChangeBeforeSubmitNeverPosts(): Unit = failure(MoveFailure.Kind.SESSION) { move(valid = { false }) }
    @Test fun invalidCookieNeverMakesRequest(): Unit = failure(MoveFailure.Kind.SESSION) {
        service.permission(123, "session=bad\nCookie: injected")
    }
    @Test fun crossOriginFormNeverPosts(): Unit = failure(MoveFailure.Kind.UNAVAILABLE) {
        enqueue(topic()); enqueue(form.replace("action=\"/move/topic/123\"", "action=\"https://example.invalid/move/topic/123\"")); move()
    }
    @Test fun duplicateMemoNeverPosts(): Unit = failure(MoveFailure.Kind.UNAVAILABLE) {
        enqueue(topic()); enqueue(form.replace("</form>", "<input name='memo'></form>")); move()
    }
    @Test fun unknownTextareaNeverPosts(): Unit = failure(MoveFailure.Kind.UNAVAILABLE) {
        enqueue(topic()); enqueue(form.replace("</form>", "<textarea name='new_control'>text</textarea></form>")); move()
    }
    @Test fun duplicateMainFailsClosed(): Unit = failure(MoveFailure.Kind.UNAVAILABLE) {
        enqueue(topic() + "<div id='Main'></div>"); service.permission(123, "session=fake")
    }
    @Test fun unknownHiddenFieldNeverPosts(): Unit = failure(MoveFailure.Kind.UNAVAILABLE) {
        enqueue(topic()); enqueue(form.replace("</form>", "<input type='hidden' name='unrecognized' value='1'></form>")); move()
    }
    @Test fun missingFormNeverPosts(): Unit = failure(MoveFailure.Kind.DENIED) {
        enqueue(topic()); enqueue("<div id='Main'></div>"); move()
    }
    @Test fun crossOriginPostRedirectIsInvalid(): Unit = failure(MoveFailure.Kind.RESPONSE) {
        enqueue(topic()); enqueue(form); enqueue("", 302, "https://example.invalid/t/123"); move()
    }
    @Test fun submissionIsNotVerifiedNodeSuccess(): Unit = runBlocking {
        enqueue(topic()); enqueue(form); enqueue("", 302, "/t/123")
        val result = move()
        assertTrue(result is MoveSubmission.Submitted)
        assertEquals("移动请求已提交", result.message)
        assertEquals(3, server.requestCount)
    }
    @Test fun disconnectedPostNeverReplays(): Unit = failure(MoveFailure.Kind.CONNECTION) {
        enqueue(topic()); enqueue(form)
        server.enqueue(MockResponse.Builder().onResponseStart(mockwebserver3.SocketEffect.CloseSocket()).build())
        try { move() } finally { assertEquals(3, server.requestCount) }
    }
    @Test fun postLoginIsNotSubmissionSuccess(): Unit = failure(MoveFailure.Kind.SESSION) {
        enqueue(topic()); enqueue(form); enqueue("", 302, "/signin"); move()
    }
    @Test fun post200ChallengeIsNotSubmissionSuccess(): Unit = failure(MoveFailure.Kind.VERIFICATION) {
        enqueue(topic()); enqueue(form); enqueue("<title>Just a moment</title>"); move()
    }
    @Test fun post200BusinessErrorIsNotSubmissionSuccess(): Unit = failure(MoveFailure.Kind.REJECTED) {
        enqueue(topic()); enqueue(form); enqueue("<div id='Wrapper'><div class='content'><div class='problem'>Rejected</div></div></div>"); move()
    }
    @Test fun readOnlyNodeRefreshDoesNotProbeMoveForm(): Unit = runBlocking {
        enqueue(mobile(topic("sandbox", allowed = false)))
        assertEquals("sandbox", service.currentNode(123, "session=fake").name)
        assertEquals(1, server.requestCount)
        assertEquals("GET", server.takeRequest().method)
    }
    @Test fun cancellationStopsSubmission(): Unit = runBlocking {
        enqueue(topic()); enqueue(form)
        server.enqueue(MockResponse.Builder().onResponseStart(mockwebserver3.SocketEffect.Stall).build())
        val job = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { move() }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            repeat(3) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
        }
        job.cancel()
        try { job.await(); fail("Expected cancellation") } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(3, server.requestCount)
    }
    @Test fun sameNodeDoesNotPost(): Unit = runBlocking {
        enqueue(topic("sandbox"))
        val result = move("sandbox")
        assertTrue(result is MoveSubmission.Unchanged)
        assertEquals("sandbox", result.node.name)
        assertEquals(1, server.requestCount)
    }
}
