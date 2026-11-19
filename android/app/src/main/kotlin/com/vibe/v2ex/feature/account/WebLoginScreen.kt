package com.vibe.v2ex.feature.account

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.vibe.v2ex.data.remote.WebLoginCandidate
import com.vibe.v2ex.data.remote.WebLoginPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val SCRAPE_USERNAME_JS = """
(function() {
  if (location.origin !== '${WebLoginPage.BASE}') return '';
  var links = document.querySelectorAll('${WebLoginPage.ACCOUNT_LINKS}');
  var names = [];
  for (var i = 0; i < links.length; i++) {
    if (links[i].closest('.topic_content,.reply_content,.topic_append')) continue;
    var url = new URL(links[i].getAttribute('href'), location.href);
    if (url.origin !== location.origin || url.username || url.password) continue;
    var match = url.pathname.match(/^\/member\/([A-Za-z0-9_]{1,100})$/);
    if (match && names.indexOf(match[1]) < 0) names.push(match[1]);
  }
  return names.length === 1 ? names[0] : '';
})()
"""

/** A real WebView browser session; challenges and two-factor authentication remain interactive. */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebLoginScreen(
    onCancel: () -> Unit,
    onConfirm: suspend (WebLoginCandidate) -> WebLoginConfirmation,
    onReset: suspend () -> Unit,
): Unit {
    var candidate by remember { mutableStateOf<WebLoginCandidate?>(null) }
    var browser by remember { mutableStateOf<WebView?>(null) }
    var verifying by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    var browserReady by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var help by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val confirm by rememberUpdatedState(onConfirm)
    BackHandler { onCancel() }

    LaunchedEffect(candidate) {
        val pending = candidate ?: return@LaunchedEffect
        verifying = true
        message = null
        try {
            when (confirm(pending)) {
                WebLoginConfirmation.ACCEPTED -> Unit
                WebLoginConfirmation.NOT_SIGNED_IN -> message = "尚未完成登录，请继续完成网页登录或两步验证。"
                WebLoginConfirmation.UNVERIFIED -> message = "暂时无法确认登录，可能仍需网站验证或网络受限；不会保存未确认的会话。可手动刷新后重试。"
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            message = "暂时无法确认登录，请检查网络后重试。"
        } finally {
            verifying = false
            if (candidate === pending) candidate = null
        }
    }

    if (help) AlertDialog(
        onDismissRequest = { help = false },
        title = { Text("网页登录帮助") },
        text = { Text("请更新 Android System WebView 和 Chrome。验证过程中保持网络稳定，避免切换 VPN、代理或网络。\n\n内嵌浏览器可能受到网站限制，App 无法保证通过 Cloudflare 验证。系统浏览器可以辅助排查，但其中的登录状态不会自动同步回 App。\n\n如果验证码持续异常，可以重置网页登录后再试。") },
        confirmButton = { TextButton(onClick = { help = false }) { Text("知道了") } },
        dismissButton = { TextButton(enabled = browserReady && !resetting, onClick = { help = false; confirmReset = true }) { Text("重置网页登录") } },
    )
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("重置网页登录？") },
        text = { Text("将退出当前 App 网页账号，并清除 App 内网页的 Cookie 和站点存储，包括已完成的验证状态。不会删除 Personal Access Token。") },
        confirmButton = {
            TextButton(onClick = {
                confirmReset = false
                val view = browser ?: return@TextButton
                val state = view.tag as WebLoginBrowserState
                val token = state.beginReset()
                candidate = null
                resetting = true
                message = null
                view.stopLoading()
                scope.launch {
                    try {
                        onReset()
                        state.finishReset(token) {
                            view.clearHistory()
                            view.loadUrl("${WebLoginPage.BASE}/signin")
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        message = "重置失败，请关闭登录页后重试。"
                    } finally { resetting = false }
                }
            }) { Text("重置") }
        },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } },
    )

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (resetting) "正在重置…" else if (verifying) "正在确认登录…" else "网页登录") },
            actions = {
                TextButton(enabled = browserReady && !resetting, onClick = {
                    val view = browser ?: return@TextButton
                    candidate = null
                    message = null
                    (view.tag as WebLoginBrowserState).navigated()
                    // Explicit GET, never WebView.reload() of a previously submitted login form.
                    view.loadUrl(view.url?.takeIf(WebLoginPage::isSite) ?: "${WebLoginPage.BASE}/signin")
                }) { Text("刷新") }
                TextButton(onClick = { help = true }) { Text("帮助") }
                IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "关闭") }
            },
        )
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    val state = WebLoginBrowserState()
                    WebView(context).apply {
                        tag = state
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        // Preserve the actual Android WebView UA, including its WebView identity.
                        val loginView = this
                        CookieManager.getInstance().apply {
                            setAcceptCookie(true)
                            setAcceptThirdPartyCookies(loginView, true)
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?): Unit {
                                state.navigated()
                                candidate = null
                                super.onPageStarted(view, url, favicon)
                            }

                            override fun onPageFinished(view: WebView, url: String?): Unit {
                                super.onPageFinished(view, url)
                                val token = state.current()
                                if (!state.accepts(token) || candidate != null || !WebLoginPage.isSite(url) || view.url != url) return
                                view.evaluateJavascript(SCRAPE_USERNAME_JS) { raw ->
                                    if (!state.accepts(token) || browser !== view || view.url != url || candidate != null) return@evaluateJavascript
                                    val username = WebLoginPage.usernameFromJavascript(raw) ?: return@evaluateJavascript
                                    val cookie = CookieManager.getInstance().getCookie(WebLoginPage.BASE).orEmpty()
                                    if (!WebLoginPage.validHeader(cookie)) return@evaluateJavascript
                                    CookieManager.getInstance().flush()
                                    candidate = WebLoginCandidate(cookie, username, view.settings.userAgentString) {
                                        state.accepts(token) && browser === view && view.url == url
                                    }
                                }
                            }

                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError): Unit {
                                super.onReceivedError(view, request, error)
                                if (request.isForMainFrame && state.accepts(state.current())) message = "网页加载失败，请检查网络或查看登录帮助。"
                            }
                        }
                        browser = this
                        scope.launch {
                            WebLoginBrowserData.awaitReady()
                            state.initialize {
                                browserReady = true
                                loadUrl("${WebLoginPage.BASE}/signin")
                            }
                        }
                    }
                },
                // No update-time navigation: recomposition must never restart a challenge.
                onRelease = { view ->
                    (view.tag as WebLoginBrowserState).release()
                    if (browser === view) {
                        browser = null
                        browserReady = false
                    }
                    view.stopLoading()
                    view.destroy()
                },
            )
            Column(Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
                if (verifying || resetting) LinearProgressIndicator(Modifier.fillMaxWidth())
                message?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(8.dp))
                }
            }
        }
    }
}
