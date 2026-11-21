package com.vibe.v2ex.data.repository

import com.vibe.v2ex.data.remote.PublicTopicPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FollowingSessionTest {
    @Test fun missingWebSessionDoesNotFetch(): Unit = runBlocking {
        var calls = 0
        val session = FollowingSession(MutableStateFlow(0L), { null }) { _, _ ->
            calls++
            PublicTopicPage(emptyList(), false)
        }
        assertTrue(runCatching { session.topics(1) }.isFailure)
        assertEquals(0, calls)
    }

    @Test fun currentCookieAndPageArePassedWithoutFallback(): Unit = runBlocking {
        val calls = mutableListOf<Pair<Int, String>>()
        val session = FollowingSession(MutableStateFlow(1L), { "fixture" }) { page, cookie ->
            calls += page to cookie
            if (page == 2) error("Fixture failure")
            PublicTopicPage(emptyList(), true)
        }
        assertTrue(session.topics(1).hasMore)
        assertTrue(runCatching { session.topics(2) }.isFailure)
        assertEquals(listOf(1 to "fixture", 2 to "fixture"), calls)
    }

    @Test fun switchedAccountOrLogoutRejectsDelayedResponse(): Unit = runBlocking {
        for (logout in listOf(false, true)) {
            val revision = MutableStateFlow(0L)
            var cookie: String? = "first"
            val response = CompletableDeferred<PublicTopicPage>()
            val session = FollowingSession(revision, { cookie }) { _, _ -> response.await() }
            val job = async(start = CoroutineStart.UNDISPATCHED) { runCatching { session.topics(1) } }
            cookie = if (logout) null else "second"
            revision.value++
            response.complete(PublicTopicPage(emptyList(), false))
            assertTrue(job.await().isFailure)
        }
    }

    @Test fun cancellationIsPropagated(): Unit = runBlocking {
        var cancelled = false
        val response = CompletableDeferred<PublicTopicPage>()
        val session = FollowingSession(MutableStateFlow(0L), { "fixture" }) { _, _ ->
            try { response.await() } catch (error: CancellationException) { cancelled = true; throw error }
        }
        val job = async(start = CoroutineStart.UNDISPATCHED) { session.topics(1) }
        job.cancelAndJoin()
        assertTrue(cancelled)
    }
}
