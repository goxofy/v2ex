package com.vibe.v2ex.feature.account

import com.vibe.v2ex.data.remote.BrowserLoginEvidence
import com.vibe.v2ex.data.remote.BrowserLoginReason
import com.vibe.v2ex.data.remote.WebLoginPage

internal sealed interface BrowserLoginAction {
    data object OpenSettings : BrowserLoginAction
    data class Confirm(val username: String) : BrowserLoginAction
    data class Wait(val reason: BrowserLoginReason?) : BrowserLoginAction
}

/** Owns one WebView lifetime. Navigation and reset invalidate delayed page callbacks. */
internal class WebLoginBrowserState {
    private var initialized = false
    private var released = false
    private var resetting = false
    private var generation = 0L
    private var documentFailed = false
    private var expectedUsername: String? = null

    fun initialize(load: () -> Unit): Unit {
        if (initialized || released || resetting) return
        initialized = true
        load()
    }

    fun navigated(): Long {
        if (!resetting) {
            generation++
            documentFailed = false
        }
        return generation
    }

    fun current(): Long = generation
    fun accepts(token: Long): Boolean = initialized && !released && !resetting && !documentFailed && generation == token

    fun failed(): Boolean {
        if (!initialized || released || resetting) return false
        documentFailed = true
        generation++
        return true
    }

    fun refreshUrl(current: String?): String = if (expectedUsername != null) {
        "${WebLoginPage.BASE}/settings"
    } else current?.takeIf(WebLoginPage::isSite) ?: "${WebLoginPage.BASE}/signin"

    /** One automatic, cache-free settings GET per account candidate; never a navigation loop. */
    fun inspect(evidence: BrowserLoginEvidence, url: String?): BrowserLoginAction {
        if (!accepts(current()) || !WebLoginPage.isSite(url)) return BrowserLoginAction.Wait(null)
        if (evidence.challenge) return BrowserLoginAction.Wait(BrowserLoginReason.CHALLENGE)
        if (evidence.loginForm || evidence.path in listOf("/signin", "/2fa")) {
            expectedUsername = null
            return BrowserLoginAction.Wait(BrowserLoginReason.LOGIN_REQUIRED)
        }
        val username = evidence.names.singleOrNull()?.takeIf(WebLoginPage::validUsername)
            ?: return BrowserLoginAction.Wait(if (expectedUsername == null) null else BrowserLoginReason.ACCOUNT_NAVIGATION)
        val expected = expectedUsername
        if (expected == null) {
            expectedUsername = username
            return BrowserLoginAction.OpenSettings
        }
        val reason = evidence.reason(expected)
        return if (WebLoginPage.isSettings(url) && reason == BrowserLoginReason.VERIFIED) {
            BrowserLoginAction.Confirm(expected)
        } else BrowserLoginAction.Wait(if (reason == BrowserLoginReason.VERIFIED) BrowserLoginReason.STRUCTURE else reason)
    }

    fun beginReset(): Long {
        expectedUsername = null
        resetting = true
        generation++
        return generation
    }

    fun finishReset(token: Long, load: () -> Unit): Unit {
        if (released || !resetting || generation != token) return
        resetting = false
        initialized = true
        load()
    }

    fun release(): Unit {
        released = true
        generation++
    }
}

/** Invalidates confirmation work when a login is closed, reset, or replaced. */
internal class WebLoginAttempt {
    private var generation = 0L
    private var active = false
    private var credentialsRevision = 0L
    fun begin(revision: Long = 0L): Unit { generation++; active = true; credentialsRevision = revision }
    fun invalidate(): Unit { generation++; active = false }
    fun current(): Long = generation
    fun accepts(token: Long, revision: Long = credentialsRevision): Boolean = active && generation == token && revision == credentialsRevision
}
