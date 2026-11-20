package com.vibe.v2ex.feature.home

import com.vibe.v2ex.data.model.Topic
import org.junit.Assert.*
import org.junit.Test

class HomeRefreshStateTest {
    @Test fun existingAllAndR2RowsDoNotHideRefreshFailures(): Unit {
        for (key in listOf("all", "r2")) {
            val rows = listOf(Topic(id = 1, title = "Previous topic"))
            val before = HomeUiState(
                topicsByFeed = mapOf(key to rows),
                loadingFeeds = setOf(key),
                hasMoreByFeed = mapOf(key to true),
                paginationTokensByFeed = mapOf(key to 3L),
            )
            val after = before.refreshFailed(key, "HTTP 503", liveHasMore = true)
            assertEquals("HTTP 503", after.errorsByFeed[key])
            assertEquals(rows, after.topicsByFeed[key])
            assertFalse(key in after.loadingFeeds)
            assertEquals(true, after.hasMoreByFeed[key])
            assertEquals(3L, after.paginationTokensByFeed[key])
        }
    }

    @Test fun previouslyEmptyListStillReportsRefreshFailure(): Unit {
        val before = HomeUiState(topicsByFeed = mapOf("r2" to emptyList()), loadingFeeds = setOf("r2"))
        val after = before.refreshFailed("r2", "网页要求验证", liveHasMore = false)
        assertEquals("网页要求验证", after.errorsByFeed["r2"])
        assertTrue(after.topicsByFeed.getValue("r2").isEmpty())
        assertEquals(false, after.hasMoreByFeed["r2"])
    }

    @Test fun diskSnapshotRemainsMarkedAsCachedWithoutALiveCursor(): Unit {
        val before = HomeUiState(
            topicsByFeed = mapOf("all" to listOf(Topic(id = 1))),
            cachedAtByFeed = mapOf("all" to 123L),
            hasMoreByFeed = mapOf("all" to true),
        )
        val after = before.refreshFailed("all", "连接失败", liveHasMore = null)
        assertEquals("连接失败", after.errorsByFeed["all"])
        assertEquals(123L, after.cachedAtByFeed["all"])
        assertFalse("all" in after.hasMoreByFeed)
        assertEquals(before.topicsByFeed, after.topicsByFeed)
    }

    @Test fun firstLoadFailureDoesNotInventAnEmptySuccessfulFeed(): Unit {
        val after = HomeUiState(loadingFeeds = setOf("all"))
            .refreshFailed("all", "连接失败", liveHasMore = null)
        assertEquals("连接失败", after.errorsByFeed["all"])
        assertNull(after.topicsByFeed["all"])
        assertTrue(after.loadingFeeds.isEmpty())
    }

    @Test fun failedFeedDoesNotChangeOtherTabs(): Unit {
        val before = HomeUiState(
            topicsByFeed = mapOf("all" to listOf(Topic(id = 1)), "node:programmer" to listOf(Topic(id = 2))),
            loadingFeeds = setOf("all", "node:programmer"),
            errorsByFeed = mapOf("r2" to "Previous error"),
            hasMoreByFeed = mapOf("node:programmer" to true),
        )
        val after = before.refreshFailed("all", "New error", liveHasMore = false)
        assertEquals(before.topicsByFeed, after.topicsByFeed)
        assertEquals(setOf("node:programmer"), after.loadingFeeds)
        assertEquals("Previous error", after.errorsByFeed["r2"])
        assertEquals(true, after.hasMoreByFeed["node:programmer"])
    }
}
