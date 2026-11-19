package com.vibe.v2ex.feature.account

import com.vibe.v2ex.data.remote.BrowserLoginEvidence
import com.vibe.v2ex.data.remote.BrowserLoginReason
import com.vibe.v2ex.data.remote.VerifiedBrowserLogin
import com.vibe.v2ex.data.remote.WebLoginPage
import org.junit.Assert.*
import org.junit.Test

class WebLoginBrowserStateTest {
    private val settingsUrl = "${WebLoginPage.BASE}/settings"
    private val evidence = BrowserLoginEvidence(1, "/settings", false, false, listOf("fixture_user"), 1, 1)

    @Test fun automaticSettingsNavigationHappensOnlyOnceBeforeProof(): Unit {
        val state = WebLoginBrowserState()
        state.initialize { }
        assertEquals(BrowserLoginAction.OpenSettings, state.inspect(evidence.copy(path = "/"), WebLoginPage.BASE))
        repeat(3) {
            assertEquals(BrowserLoginAction.Wait(BrowserLoginReason.STRUCTURE), state.inspect(evidence.copy(path = "/"), WebLoginPage.BASE))
        }
        state.navigated()
        assertEquals(BrowserLoginAction.Confirm("fixture_user"), state.inspect(evidence, settingsUrl))
    }

    @Test fun alreadyVisibleSettingsStillRequiresOneFreshNavigation(): Unit {
        val state = WebLoginBrowserState()
        state.initialize { }
        assertEquals(BrowserLoginAction.OpenSettings, state.inspect(evidence, settingsUrl))
        state.navigated()
        assertEquals(BrowserLoginAction.Confirm("fixture_user"), state.inspect(evidence, settingsUrl))
    }

    @Test fun unknownSettingsDoesNotLoopAndManualRefreshTargetsSettings(): Unit {
        val state = WebLoginBrowserState()
        state.initialize { }
        state.inspect(evidence.copy(path = "/"), WebLoginPage.BASE)
        state.navigated()
        repeat(5) {
            assertEquals(BrowserLoginAction.Wait(BrowserLoginReason.STRUCTURE), state.inspect(evidence.copy(settingsForms = 0), settingsUrl))
        }
        assertEquals(settingsUrl, state.refreshUrl("${WebLoginPage.BASE}/"))
    }

    @Test fun challengesAndTwoFactorRemainInteractiveWithoutAutomaticNavigation(): Unit {
        val state = WebLoginBrowserState()
        state.initialize { }
        repeat(3) {
            assertEquals(BrowserLoginAction.Wait(BrowserLoginReason.CHALLENGE), state.inspect(evidence.copy(challenge = true), settingsUrl))
            assertEquals(BrowserLoginAction.Wait(BrowserLoginReason.LOGIN_REQUIRED), state.inspect(evidence.copy(path = "/2fa"), "${WebLoginPage.BASE}/2fa"))
        }
        assertEquals(BrowserLoginAction.OpenSettings, state.inspect(evidence.copy(path = "/"), WebLoginPage.BASE))
    }

    @Test fun explicitLoginRedirectAllowsNewUserLoginWithoutConfirmationLoop(): Unit {
        val state = WebLoginBrowserState()
        state.initialize { }
        state.inspect(evidence, settingsUrl)
        assertEquals(BrowserLoginAction.Wait(BrowserLoginReason.LOGIN_REQUIRED), state.inspect(evidence.copy(loginForm = true), settingsUrl))
        assertEquals(BrowserLoginAction.OpenSettings, state.inspect(evidence, settingsUrl))
    }

    @Test fun mainDocumentErrorInvalidatesOldProofEvenIfDomStillLooksLoggedIn(): Unit {
        val state = WebLoginBrowserState()
        state.initialize { }
        state.inspect(evidence, settingsUrl)
        val token = state.navigated()
        val proof = VerifiedBrowserLogin.confirm(evidence, "fixture_user", settingsUrl, "fixture-cookie", "fixture-UA") { state.accepts(token) }!!
        state.failed()
        assertFalse(proof.session.isCurrent())
        assertFalse(state.accepts(state.current()))
        assertEquals(BrowserLoginAction.Wait(null), state.inspect(evidence, settingsUrl))
        state.navigated()
        assertEquals(BrowserLoginAction.Confirm("fixture_user"), state.inspect(evidence, settingsUrl))
        assertFalse(proof.session.isCurrent())
    }

    @Test fun accountChangesAndForeignSettingsCannotConfirm(): Unit {
        val state = WebLoginBrowserState()
        state.initialize { }
        state.inspect(evidence, settingsUrl)
        state.navigated()
        assertEquals(BrowserLoginAction.Wait(BrowserLoginReason.ACCOUNT_MISMATCH), state.inspect(evidence.copy(names = listOf("other")), settingsUrl))
        assertEquals(BrowserLoginAction.Wait(null), state.inspect(evidence, "https://other.invalid/settings"))
    }

    @Test fun resetClearsExpectedAccountAndInvalidatesProof(): Unit {
        val state = WebLoginBrowserState()
        state.initialize { }
        state.inspect(evidence, settingsUrl)
        val page = state.navigated()
        val reset = state.beginReset()
        assertFalse(state.accepts(page))
        state.finishReset(reset) { }
        assertEquals(BrowserLoginAction.OpenSettings, state.inspect(evidence.copy(names = listOf("other")), settingsUrl))
    }

    @Test fun accountCredentialRevisionChangeInvalidatesAttempt(): Unit {
        val attempt = WebLoginAttempt()
        attempt.begin(41L)
        val token = attempt.current()
        assertTrue(attempt.accepts(token, 41L))
        assertFalse(attempt.accepts(token, 42L))
        attempt.begin(42L)
        assertFalse(attempt.accepts(token, 42L))
        assertTrue(attempt.accepts(attempt.current(), 42L))
    }

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
        assertFalse(state.failed()) // Nor can an error from the cancelled document.
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
