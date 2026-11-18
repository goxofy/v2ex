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
        service = TopicMoveService(OkHttpClient(), server.url("/"), wait = {})
    }

    @After fun teardown(): Unit {
        val requests = (0 until server.requestCount).mapNotNull { server.takeRequest(100, TimeUnit.MILLISECONDS) }
        assertTrue("A move must never replay POST", requests.count { it.method == "POST" } <= 1)
        server.close()
    }

    private fun topic(node: String = "qna", allowed: Boolean = true, extra: String = ""): String =
        """<div id="Main"><div class="box"><div class="header"><a href="/go/$node">$node</a><h1>Fixture title</h1>
        ${if (allowed) "<small><a class='op' href='/move/topic/123'>MOVE</a></small>" else ""}</div>$extra</div></div>"""

    private fun enqueue(body: String, code: Int = 200, location: String? = null): Unit {
        server.enqueue(MockResponse.Builder().code(code).body(body).apply { if (location != null) addHeader("Location", location) }.build())
    }

    private fun failure(kind: MoveFailure.Kind, block: suspend () -> Unit): Unit = runBlocking {
        try { block(); fail("Expected $kind") } catch (error: MoveFailure) { assertEquals(kind, error.kind) }
    }

    private suspend fun move(current: String = "qna", valid: () -> Boolean = { true }): MoveNode =
        service.move(123, "session=fake", current, target, "中文 + & =\n备注", valid)

    @Test fun permissionRequiresOwnedHeader(): Unit = runBlocking {
        enqueue(topic())
        assertTrue(service.permission(123, "session=fake").canMove)
    }
    @Test fun ordinaryMemberHasNoPermission(): Unit = runBlocking {
        enqueue(topic(allowed = false))
        assertFalse(service.permission(123, "session=fake").canMove)
    }
    @Test fun forgedBodyLinkDoesNotGrantPermission(): Unit = runBlocking {
        enqueue(topic(allowed = false, extra = "<div class='topic_content'><a class='op' href='/move/topic/123'>MOVE</a></div>"))
        assertFalse(service.permission(123, "session=fake").canMove)
    }
    @Test fun forgedHeaderInContentIsRemoved(): Unit = runBlocking {
        enqueue(topic(allowed = false, extra = "<div class='topic_content'><div id='Main'><div class='box'><div class='header'><h1>x</h1><a class='op' href='/move/topic/123'>MOVE</a></div></div></div></div>"))
        assertFalse(service.permission(123, "session=fake").canMove)
    }
    @Test fun differentTopicLinkDoesNotGrantPermission(): Unit = runBlocking {
        enqueue(topic().replace("/move/topic/123", "/move/topic/456"))
        assertFalse(service.permission(123, "session=fake").canMove)
    }
    @Test fun crossOriginLinkDoesNotGrantPermission(): Unit = runBlocking {
        enqueue(topic().replace("href='/move/topic/123'", "href='https://example.invalid/move/topic/123'"))
        assertFalse(service.permission(123, "session=fake").canMove)
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
    @Test fun direct200StillVerifiesUsingGet(): Unit = runBlocking {
        enqueue(topic()); enqueue(form); enqueue(topic("sandbox")); enqueue(topic("sandbox"))
        assertEquals("sandbox", move().name)
        assertEquals(4, server.requestCount)
    }
    @Test fun redirectedPostIsFollowedOnlyWithGet(): Unit = runBlocking {
        enqueue(topic()); enqueue(form); enqueue("", 307, "/t/123"); enqueue(topic("sandbox"))
        assertEquals("sandbox", move().name)
        val requests = (0 until 4).map { server.takeRequest() }
        assertEquals(listOf("GET", "GET", "POST", "GET"), requests.map { it.method })
        assertEquals("session=fake", requests[2].headers["Cookie"])
        val fields = requests[2].body!!.utf8().split("&").associate { field ->
            val parts = field.split("=", limit = 2)
            parts[0] to java.net.URLDecoder.decode(parts[1], "UTF-8")
        }
        assertEquals("中文 + & =\n备注", fields["memo"])
        assertEquals("safe-token", fields["once"])
        assertEquals("sandbox", fields["destination"])
    }
    @Test fun delayedResultDoesNotResubmit(): Unit = runBlocking {
        enqueue(topic()); enqueue(form); enqueue("", 302, "/t/123")
        enqueue(topic()); enqueue(topic()); enqueue(topic("sandbox"))
        assertEquals("sandbox", move().name)
        assertEquals(6, server.requestCount)
    }
    @Test fun serviceUnavailableNeverReplays(): Unit = runBlocking {
        enqueue(topic()); enqueue(form); enqueue("", 503); enqueue(topic("sandbox"))
        assertEquals("sandbox", move().name)
        assertEquals(4, server.requestCount)
    }
    @Test fun timeoutStatusNeverReplays(): Unit = runBlocking {
        enqueue(topic()); enqueue(form); enqueue("", 408); enqueue(topic("sandbox"))
        assertEquals("sandbox", move().name)
    }
    @Test fun forbiddenStopsWithoutRetry(): Unit = failure(MoveFailure.Kind.DENIED) {
        enqueue(topic()); enqueue(form); enqueue("", 403)
        move()
    }
    @Test fun noPermissionNeverPosts(): Unit = failure(MoveFailure.Kind.DENIED) {
        enqueue(topic(allowed = false)); move()
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
    @Test fun crossOriginPostRedirectIsUnconfirmed(): Unit = failure(MoveFailure.Kind.UNCONFIRMED) {
        enqueue(topic()); enqueue(form); enqueue("", 302, "https://example.invalid/t/123"); move()
    }
    @Test fun unchangedTopicIsUnconfirmed(): Unit = failure(MoveFailure.Kind.UNCONFIRMED) {
        enqueue(topic()); enqueue(form); enqueue("", 302, "/t/123")
        repeat(3) { enqueue(topic(extra = "<div class='topic_content'><a href='/go/sandbox'>沙盒</a></div>")) }
        move()
    }
    @Test fun transientReadFailureCanRecover(): Unit = runBlocking {
        enqueue(topic()); enqueue(form); enqueue("", 302, "/t/123"); enqueue("", 503); enqueue(topic("sandbox"))
        assertEquals("sandbox", move().name)
    }
    @Test fun disconnectedPostCanBeConfirmedWithoutReplay(): Unit = runBlocking {
        enqueue(topic()); enqueue(form)
        server.enqueue(MockResponse.Builder().onResponseStart(mockwebserver3.SocketEffect.CloseSocket()).build())
        enqueue(topic("sandbox"))
        assertEquals("sandbox", move().name)
        assertEquals(4, server.requestCount)
    }
    @Test fun cancellationStopsReadOnlyConfirmation(): Unit = runBlocking {
        enqueue(topic()); enqueue(form); enqueue("", 302, "/t/123")
        server.enqueue(MockResponse.Builder().onResponseStart(mockwebserver3.SocketEffect.Stall).build())
        val job = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { move() }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            repeat(4) { assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)) }
        }
        job.cancel()
        try { job.await(); fail("Expected cancellation") } catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(4, server.requestCount)
    }
    @Test fun confirmationHasBoundedDeadline(): Unit = failure(MoveFailure.Kind.UNCONFIRMED) {
        enqueue(topic()); enqueue(form); enqueue("", 302, "/t/123")
        repeat(3) { server.enqueue(MockResponse.Builder().onResponseStart(mockwebserver3.SocketEffect.Stall).build()) }
        val start = System.nanoTime()
        try { move() } finally {
            assertTrue(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start) < 20)
        }
    }
    @Test fun sameNodeDoesNotPost(): Unit = runBlocking {
        enqueue(topic("sandbox"))
        assertEquals("sandbox", move("sandbox").name)
        assertEquals(1, server.requestCount)
    }
}
