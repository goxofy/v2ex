package com.vibe.v2ex.data.repository

import com.vibe.v2ex.data.remote.TopicMoveService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class TopicMoveRepositoryTest {
    private class Fixture(parent: CoroutineScope, initialShortcuts: String? = null) : AutoCloseable {
        val server = MockWebServer().apply { start() }
        val revision = MutableStateFlow(0L)
        var cookie: String? = "session=fake"
        var clock = 1L
        private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob())
        var savedNodes = "{}"
        var savedShortcuts = initialShortcuts
        val repository = newRepository()

        fun newRepository(): TopicMoveRepository = TopicMoveRepository(
            revision, { cookie }, TopicMoveService(OkHttpClient(), server.url("/")),
            { savedNodes }, { savedNodes = it }, scope, { clock },
            { savedShortcuts }, { savedShortcuts = it },
        )

        fun enqueue(allowed: Boolean = true, delayMillis: Long = 0, id: Long = 123): Unit {
            val link = if (allowed) "<a class='op' href='/move/topic/$id'>MOVE</a>" else ""
            server.enqueue(MockResponse.Builder().body("""<div id="Main"><div class="box"><div class="header">
                <a href="/go/qna">问与答</a><h1>Fixture</h1>$link</div></div></div>""")
                .bodyDelay(delayMillis, TimeUnit.MILLISECONDS).build())
        }

        override fun close(): Unit { scope.cancel(); server.close() }
    }

    private suspend fun settle(predicate: () -> Boolean): Unit {
        withTimeout(3000) { while (!predicate()) delay(5) }
    }

    @Test fun unknownAndExpiredPermissionsAreHidden(): Unit = runBlocking {
        Fixture(this).use { f ->
            assertFalse(f.repository.allowed(123))
            f.enqueue()
            val watch = launch { f.repository.watch(123) }
            try {
                settle { f.repository.allowed(123) }
                assertFalse(f.repository.allowed(456))
                f.clock += 61_000L
                assertFalse(f.repository.allowed(123))
            } finally { watch.cancelAndJoin() }
        }
    }

    @Test fun ordinaryAccountNeverGetsMoveEntry(): Unit = runBlocking {
        Fixture(this).use { f ->
            f.enqueue(allowed = false)
            f.server.enqueue(MockResponse.Builder().code(403).build())
            val watch = launch { f.repository.watch(123) }
            try {
                settle { f.server.requestCount == 2 }
                delay(50)
                assertTrue(f.repository.permissions.value.isEmpty())
                assertFalse(f.repository.allowed(123))
            } finally { watch.cancelAndJoin() }
        }
    }

    @Test fun duplicateConsumersSharePermissionRequest(): Unit = runBlocking {
        Fixture(this).use { f ->
            f.enqueue()
            val first = launch { f.repository.watch(123) }
            val second = launch { f.repository.watch(123) }
            try {
                settle { f.repository.allowed(123) }
                assertEquals(1, f.server.requestCount)
            } finally { first.cancelAndJoin(); second.cancelAndJoin() }
        }
    }

    @Test fun logoutImmediatelyHidesPermission(): Unit = runBlocking {
        Fixture(this).use { f ->
            f.enqueue()
            val watch = launch { f.repository.watch(123) }
            try {
                settle { f.repository.allowed(123) }
                f.cookie = null
                f.revision.value++
                assertFalse(f.repository.allowed(123))
                settle { f.repository.permissions.value.isEmpty() }
            } finally { watch.cancelAndJoin() }
        }
    }

    @Test fun oldAccountResponseCannotGrantNewAccountPermission(): Unit = runBlocking {
        Fixture(this).use { f ->
            f.enqueue(delayMillis = 200)
            val watch = launch(start = CoroutineStart.UNDISPATCHED) { f.repository.watch(123) }
            try {
                settle { f.server.requestCount == 1 }
                f.cookie = "session=other"
                f.revision.value++
                delay(350)
                assertTrue(f.repository.permissions.value.isEmpty())
                assertFalse(f.repository.allowed(123))
            } finally { watch.cancelAndJoin() }
        }
    }

    @Test fun invalidationRejectsAnEarlierProbe(): Unit = runBlocking {
        Fixture(this).use { f ->
            f.enqueue(delayMillis = 200)
            val watch = launch(start = CoroutineStart.UNDISPATCHED) { f.repository.watch(123) }
            try {
                settle { f.server.requestCount == 1 }
                f.repository.invalidate(123)
                delay(350)
                assertTrue(f.repository.permissions.value.isEmpty())
            } finally { watch.cancelAndJoin() }
        }
    }

    @Test fun noCookieDoesNotProbe(): Unit = runBlocking {
        Fixture(this).use { f ->
            f.cookie = null
            f.repository.watch(123)
            assertEquals(0, f.server.requestCount)
        }
    }

    @Test fun customShortcutsAreUnboundedOrderedAndPersistent(): Unit = runBlocking {
        Fixture(this).use { f ->
            val nodes = (0 until 80).map { com.vibe.v2ex.data.remote.MoveNode("node$it", "节点$it") }
            assertEquals(nodes, f.repository.shortcuts(nodes, emptyList()))
            val custom = nodes.reversed()
            f.repository.saveShortcuts(custom + custom.first() + com.vibe.v2ex.data.remote.MoveNode("bad/path", "Invalid"))
            val restored = f.newRepository()
            assertEquals(custom, restored.shortcuts(emptyList(), emptyList()))
            restored.saveShortcuts(custom.drop(1))
            assertEquals(custom[1], f.newRepository().customShortcuts.value!!.first())
            assertEquals(79, f.newRepository().customShortcuts.value!!.size)
        }
    }

    @Test fun emptyShortcutsDifferFromDefault(): Unit = runBlocking {
        Fixture(this).use { f ->
            val website = listOf(com.vibe.v2ex.data.remote.MoveNode("qna", "问与答"))
            f.repository.saveShortcuts(emptyList())
            assertEquals(emptyList<com.vibe.v2ex.data.remote.MoveNode>(), f.newRepository().shortcuts(website, emptyList()))
            f.repository.saveShortcuts(null)
            assertEquals(website, f.newRepository().shortcuts(website, emptyList()))
        }
    }

    @Test fun shortcutDirectoryUpdatesTitlesWithoutDroppingSavedNodes(): Unit = runBlocking {
        Fixture(this).use { f ->
            val saved = com.vibe.v2ex.data.remote.MoveNode("missing", "Saved")
            val current = com.vibe.v2ex.data.remote.MoveNode("qna", "问与答", listOf("qa"))
            f.repository.saveShortcuts(listOf(com.vibe.v2ex.data.remote.MoveNode("qna", "Old"), saved))
            assertEquals(listOf(current, saved), f.repository.shortcuts(emptyList(), listOf(current)))
        }
    }

    @Test fun corruptShortcutsFallBackToWebsiteDefaults(): Unit = runBlocking {
        Fixture(this, "invalid").use { f ->
            val website = listOf(com.vibe.v2ex.data.remote.MoveNode("qna", "问与答"))
            assertNull(f.repository.customShortcuts.value)
            assertEquals(website, f.repository.shortcuts(website, emptyList()))
        }
    }

    @Test fun submittedNodeIsTransientAndExpires(): Unit = runBlocking {
        Fixture(this).use { f ->
            f.enqueue()
            f.server.enqueue(MockResponse.Builder().body("""<div id="Main"><div class="box"><div class="inner">
                <form method="post" action="/move/topic/123"><select name="destination"></select><input name="memo"></form>
                </div></div></div>""").build())
            f.server.enqueue(MockResponse.Builder().code(302).addHeader("Location", "/t/123").build())
            val target = com.vibe.v2ex.data.remote.MoveNode("sandbox", "沙盒")
            val result = f.repository.move(123, 0, "qna", target, "")
            assertTrue(result is com.vibe.v2ex.data.remote.MoveSubmission.Submitted)
            assertEquals(target, f.repository.movedNodes.value[123])
            assertFalse(f.newRepository().movedNodes.value.containsKey(123))
            assertEquals(3, f.server.requestCount)
            f.clock += 301_000
            f.repository.expireNodes()
            assertTrue(f.repository.movedNodes.value.isEmpty())
        }
    }

    @Test fun unchangedNodeIsVerifiedButExpiresAfterRestart(): Unit = runBlocking {
        Fixture(this).use { f ->
            f.enqueue()
            val target = com.vibe.v2ex.data.remote.MoveNode("qna", "问与答")
            val result = f.repository.move(123, 0, "qna", target, "")
            assertTrue(result is com.vibe.v2ex.data.remote.MoveSubmission.Unchanged)
            assertEquals("qna", f.newRepository().movedNodes.value[123]?.name)
            f.clock += 301_000
            assertTrue(f.newRepository().movedNodes.value.isEmpty())
            assertEquals(1, f.server.requestCount)
        }
    }

    @Test fun detailProbeCompletesAndReusesCachedPermission(): Unit = runBlocking {
        Fixture(this).use { f ->
            f.enqueue()
            withTimeout(3000) { f.repository.watch(123); f.repository.watch(123) }
            assertEquals(1, f.server.requestCount)
        }
    }

    @Test fun probesHaveAtMostTwoConcurrentRequests(): Unit = runBlocking {
        Fixture(this).use { f ->
            f.server.dispatcher = object : mockwebserver3.Dispatcher() {
                override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                    val id = request.url.encodedPath.substringAfterLast('/').toLong()
                    return MockResponse.Builder().body("""<div id="Main"><div class="box"><div class="header">
                        <a href="/go/qna">问与答</a><h1>Fixture</h1><a class="op" href="/move/topic/$id">MOVE</a>
                        </div></div></div>""").bodyDelay(250, TimeUnit.MILLISECONDS).build()
                }
            }
            val watches = (123L..125L).map { id -> launch { f.repository.watch(id) } }
            try {
                settle { f.server.requestCount >= 2 }
                assertEquals(2, f.server.requestCount)
                settle { f.server.requestCount == 3 }
            } finally { watches.forEach { it.cancelAndJoin() } }
        }
    }
}
