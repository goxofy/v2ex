package com.vibe.v2ex.feature.account

/** Owns one WebView lifetime. Navigation and reset invalidate delayed page callbacks. */
internal class WebLoginBrowserState {
    private var initialized = false
    private var released = false
    private var resetting = false
    private var generation = 0L

    fun initialize(load: () -> Unit): Unit {
        if (initialized || released || resetting) return
        initialized = true
        load()
    }

    fun navigated(): Long {
        if (!resetting) generation++
        return generation
    }

    fun current(): Long = generation
    fun accepts(token: Long): Boolean = initialized && !released && !resetting && generation == token

    fun beginReset(): Long {
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
    fun begin(): Unit { generation++; active = true }
    fun invalidate(): Unit { generation++; active = false }
    fun current(): Long = generation
    fun accepts(token: Long): Boolean = active && generation == token
}
