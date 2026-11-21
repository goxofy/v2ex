package com.vibe.v2ex.data.datastore

import org.junit.Assert.*
import org.junit.Test

class HomeTabsStoreTest {
    @Test fun migrationRunsOnlyOnceAndPersistsOrder(): Unit {
        var saved: String? = null
        val store = HomeTabsStore({ saved }, { saved = it })
        store.initialize(listOf(HomeTab("node:apple", "Apple")))
        assertEquals(listOf("all", "hot", "r2", "following", "node:apple"), store.items.value!!.map { it.key })
        store.save(listOf(HomeTab("following", "关注"), HomeTab("node:apple", "Apple")))
        store.initialize(listOf(HomeTab("node:other", "Other")))
        assertEquals(listOf("following", "node:apple"), HomeTabsStore({ saved }, {}).items.value!!.map { it.key })
    }

    @Test fun limitIncludesBuiltinsAndRejectsInvalidOrDuplicateEntries(): Unit {
        val values = HomeTabsStore.builtins + HomeTabsStore.builtins + listOf(HomeTab("node:../bad", "Bad"), HomeTab("node:blank", " ")) + (0..20).map { HomeTab("node:n$it", "Node $it") }
        val clean = HomeTabsStore.normalize(values)
        assertEquals(15, clean.size)
        assertEquals("node:n10", clean.last().key)
        assertEquals(listOf(HomeTab("all", "全部")), HomeTabsStore.normalize(emptyList()))
    }

    @Test fun corruptPreferencesRecoverAndDefaultsCanBeRestored(): Unit {
        val store = HomeTabsStore({ "invalid json" }, {})
        store.initialize(emptyList())
        assertEquals(HomeTabsStore.builtins, store.items.value)
        store.save(HomeTabsStore.defaults(listOf(HomeTab("node:qna", "问与答"))))
        assertEquals("node:qna", store.items.value!!.last().key)
    }

    @Test fun FailedStorageDoesNotBlockInitialHomeAndDoesNotPublishFailedEdit(): Unit {
        val store = HomeTabsStore({ null }, { error("Storage unavailable") })
        store.initialize(emptyList())
        assertEquals(HomeTabsStore.builtins, store.items.value)
        assertTrue(runCatching { store.save(listOf(HomeTab("hot", "最热"))) }.isFailure)
        assertEquals(HomeTabsStore.builtins, store.items.value)
    }
}
