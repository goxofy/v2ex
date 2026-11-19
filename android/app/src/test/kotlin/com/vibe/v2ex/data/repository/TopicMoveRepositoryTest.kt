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
    private class Fixture(parent: CoroutineScope) : AutoCloseable {
        val server = MockWebServer().apply { start() }
        val revision = MutableStateFlow(0L)
        var cookie: String? = "session=fake"
        var clock = 1L
        private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob())
        val repository = TopicMoveRepository(
            revision, { cookie }, TopicMoveService(OkHttpClient(), server.url("/"), wait = {}),
            { "{}" }, {}, scope, { clock },
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
                f.clock += 61_000_000_000L
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
