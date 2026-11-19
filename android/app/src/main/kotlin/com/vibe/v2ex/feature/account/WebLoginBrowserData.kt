package com.vibe.v2ex.feature.account

import android.webkit.CookieManager
import android.webkit.WebStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Shared by logout and explicit browser reset; ordinary login never clears storage. */
internal object WebLoginBrowserData {
    private val cleanup = Mutex()

    suspend fun awaitReady(): Unit = cleanup.withLock { }

    suspend fun clear(): Unit = withContext(NonCancellable + Dispatchers.Main.immediate) {
        cleanup.withLock {
            WebStorage.getInstance().deleteAllData()
            suspendCancellableCoroutine<Unit> { continuation ->
                val cookies = CookieManager.getInstance()
                cookies.removeAllCookies {
                    cookies.flush()
                    continuation.resumeWith(Result.success(Unit))
                }
            }
        }
    }
}
