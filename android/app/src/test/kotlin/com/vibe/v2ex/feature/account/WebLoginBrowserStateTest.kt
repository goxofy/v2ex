package com.vibe.v2ex.feature.account

import org.junit.Assert.*
import org.junit.Test

class WebLoginBrowserStateTest {
    @Test fun initializesOnceAndNeverResetsOnRepeatedInitialization(): Unit {
        val state = WebLoginBrowserState()
        var loads = 0
        repeat(5) { state.initialize { loads++ } }
        assertEquals(1, loads)
        assertTrue(state.accepts(state.current()))
    }

    @Test fun resetWaitsForCompletionAndRejectsOldCallbacks(): Unit {
        val state = WebLoginBrowserState()
        var loads = 0
        state.initialize { loads++ }
        val page = state.navigated()
        val reset = state.beginReset()
        assertEquals(1, loads)
        assertFalse(state.accepts(page))
        state.navigated() // A cancelled navigation callback cannot supersede an explicit reset.
        state.initialize { loads++ }
        assertEquals(1, loads)
        state.finishReset(reset) { loads++ }
        state.finishReset(reset) { loads++ }
        assertEquals(2, loads)
    }

    @Test fun releasedViewCannotInitializeOrFinishReset(): Unit {
        val state = WebLoginBrowserState()
        state.initialize { }
        val reset = state.beginReset()
        state.release()
        state.initialize { fail("Released view initialized") }
        state.finishReset(reset) { fail("Released view navigated") }
        assertFalse(state.accepts(state.current()))
    }

    @Test fun resetBeforeInitializationDoesNotDoubleLoad(): Unit {
        val state = WebLoginBrowserState()
        var loads = 0
        val reset = state.beginReset()
        state.finishReset(reset) { loads++ }
        state.initialize { loads++ }
        assertEquals(1, loads)
    }

    @Test fun newNavigationInvalidatesOldJavascriptResult(): Unit {
        val state = WebLoginBrowserState()
        state.initialize { }
        val previous = state.navigated()
        val current = state.navigated()
        assertFalse(state.accepts(previous))
        assertTrue(state.accepts(current))
    }

    @Test fun closedOrReplacedAttemptCannotSaveOldConfirmation(): Unit {
        val attempt = WebLoginAttempt()
        attempt.begin()
        val first = attempt.current()
        assertTrue(attempt.accepts(first))
        attempt.invalidate()
        assertFalse(attempt.accepts(first))
        attempt.begin()
        assertFalse(attempt.accepts(first))
        assertTrue(attempt.accepts(attempt.current()))
    }
}
