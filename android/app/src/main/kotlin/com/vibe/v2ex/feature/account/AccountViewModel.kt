package com.vibe.v2ex.feature.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibe.v2ex.data.datastore.FollowedNodesStore
import com.vibe.v2ex.data.datastore.SecureStore
import com.vibe.v2ex.data.datastore.SettingsDataStore
import com.vibe.v2ex.data.datastore.UnreadNotificationsStore
import com.vibe.v2ex.data.moderation.ModerationStore
import com.vibe.v2ex.data.remote.V2exApiV2
import com.vibe.v2ex.data.remote.WebSessionService
import com.vibe.v2ex.data.remote.WebLoginCandidate
import com.vibe.v2ex.data.remote.VerifiedBrowserLogin
import com.vibe.v2ex.data.remote.WebSessionVerification
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import com.vibe.v2ex.data.repository.FavoritesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import retrofit2.HttpException
import javax.inject.Inject

/** 浏览器已确认的会话是否仍可保存。 */
enum class WebLoginConfirmation {
    /** 当前浏览器已验证设置页，会话已落盘。 */
    ACCEPTED,

    /** 页面或账号已变化，旧确认结果不落盘。 */
    UNVERIFIED,
}

data class AccountUiState(
    val webSessionActive: Boolean = false,
    val sessionUsername: String? = null,
    /** 输入框只编辑这份内存草稿，验证成功前绝不会写入 SecureStore。 */
    val personalAccessToken: String = "",
    val hasSavedPersonalAccessToken: Boolean = false,
    val isValidatingPersonalAccessToken: Boolean = false,
    val personalAccessTokenStatus: String? = null,
    val personalAccessTokenStatusIsError: Boolean = false,
    val showWebLogin: Boolean = false,
    val clearingWebLogin: Boolean = false,
    val webLoginError: String? = null,
    /** true = 存着登录态但服务器已不认（cookie 过期）；null = 尚未校验或无法校验。 */
    val sessionExpired: Boolean? = null,
)

/**
 * Login itself happens inside an embedded WebView (see WebLoginScreen) — the iOS app's
 * scripted username/password/captcha form exists in V2EXClient but is unreachable dead
 * code in the shipping UI; WKWebView (here, android.webkit.WebView) is the real path
 * since it renders V2EX's own captcha/2FA pages without the app reimplementing them.
 */
@HiltViewModel
class AccountViewModel @Inject constructor(
    private val apiV2: V2exApiV2,
    private val webSessionService: WebSessionService,
    private val secureStore: SecureStore,
    private val favoritesRepository: FavoritesRepository,
    private val followedNodesStore: FollowedNodesStore,
    private val settingsDataStore: SettingsDataStore,
    private val moderationStore: ModerationStore,
    private val unreadNotificationsStore: UnreadNotificationsStore,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        AccountUiState(
            webSessionActive = secureStore.isWebSessionActive,
            sessionUsername = secureStore.sessionUsername,
            personalAccessToken = secureStore.personalAccessToken.orEmpty(),
            hasSavedPersonalAccessToken = secureStore.isTokenSet,
        ),
    )
    val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()

    private val loginAttempt = WebLoginAttempt()

    init {
        // 打开账号页时校验会话是否仍被服务器承认 —— cookie 过期时给出重新登录提示，
        // 而不是让「已登录」一直挂着但收藏同步/回复默默失败。
        if (secureStore.isWebSessionActive) {
            viewModelScope.launch {
                val revision = secureStore.accountCredentialsRevision.value
                val valid = webSessionService.verifySession()
                if (revision == secureStore.accountCredentialsRevision.value && valid != WebSessionVerification.UNVERIFIED) {
                    _uiState.value = _uiState.value.copy(sessionExpired = valid == WebSessionVerification.SIGNED_OUT)
                }
            }
        }
    }

    fun openWebLogin(): Unit {
        if (_uiState.value.clearingWebLogin) return
        loginAttempt.begin(secureStore.accountCredentialsRevision.value)
        _uiState.value = _uiState.value.copy(showWebLogin = true)
    }

    fun dismissWebLogin(): Unit {
        loginAttempt.invalidate()
        _uiState.value = _uiState.value.copy(showWebLogin = false)
    }

    /** Browser proof is the first-login gate; another HTTP client must not re-challenge it. */
    suspend fun confirmWebLogin(confirmed: VerifiedBrowserLogin): WebLoginConfirmation {
        currentCoroutineContext().ensureActive()
        val candidate = confirmed.session
        if (!loginAttempt.accepts(loginAttempt.current(), secureStore.accountCredentialsRevision.value) || !candidate.isCurrent()) {
            return WebLoginConfirmation.UNVERIFIED
        }
        // No suspension between the lifecycle checks and persistence on the UI thread.
        onWebLoginSuccess(candidate)
        return WebLoginConfirmation.ACCEPTED
    }

    private fun onWebLoginSuccess(candidate: WebLoginCandidate): Unit {
        loginAttempt.invalidate()
        webSessionService.saveWebSession(candidate)
        _uiState.value = _uiState.value.copy(
            webSessionActive = true,
            sessionUsername = candidate.username,
            showWebLogin = false,
            sessionExpired = false,
            webLoginError = null,
        )
        // 登录成功立即把网页收藏（话题 + 关注节点）拉回本地，不用等下次启动。
        viewModelScope.launch { favoritesRepository.syncFromRemote() }
        viewModelScope.launch {
            if (!settingsDataStore.autoSyncFollowedNodes.first()) return@launch
            followedNodesStore.syncFromRemote()
        }
        moderationStore.syncSessionIdentity()
        viewModelScope.launch { moderationStore.refreshWebsiteBlocks() }
        viewModelScope.launch { unreadNotificationsStore.refresh() }
    }

    private fun clearLocalWebSession(): Unit {
        loginAttempt.invalidate()
        webSessionService.signOutWebSession()
        _uiState.value = _uiState.value.copy(webSessionActive = false, sessionUsername = null, sessionExpired = null)
        moderationStore.syncSessionIdentity()
        viewModelScope.launch { unreadNotificationsStore.refresh() }
    }

    suspend fun resetWebLogin(): Unit {
        clearLocalWebSession()
        _uiState.value = _uiState.value.copy(clearingWebLogin = true, webLoginError = null)
        try {
            WebLoginBrowserData.clear()
            currentCoroutineContext().ensureActive()
            if (_uiState.value.showWebLogin) loginAttempt.begin(secureStore.accountCredentialsRevision.value)
        } finally {
            _uiState.value = _uiState.value.copy(clearingWebLogin = false)
        }
    }

    fun signOutWebSession(): Unit {
        if (_uiState.value.clearingWebLogin) return
        dismissWebLogin()
        viewModelScope.launch {
            try { resetWebLogin() }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                _uiState.value = _uiState.value.copy(webLoginError = "本地账号已退出，但网页登录数据清理失败，请进入登录页手动重置。")
            }
        }
    }

    fun onPatChange(value: String) {
        _uiState.value = _uiState.value.copy(
            personalAccessToken = value,
            personalAccessTokenStatus = null,
            personalAccessTokenStatusIsError = false,
        )
    }

    fun verifyAndSavePat() {
        val current = _uiState.value
        if (current.isValidatingPersonalAccessToken) return

        val candidate = current.personalAccessToken.filterNot(Char::isWhitespace)
        if (candidate.isBlank()) {
            _uiState.value = current.copy(
                personalAccessTokenStatus = "请先输入 Personal Access Token",
                personalAccessTokenStatusIsError = true,
            )
            return
        }

        _uiState.value = current.copy(
            isValidatingPersonalAccessToken = true,
            personalAccessTokenStatus = "正在验证 Token…",
            personalAccessTokenStatusIsError = false,
        )

        viewModelScope.launch {
            val result = runCatching { apiV2.me(authorization = "Bearer $candidate") }

            // 验证期间如果草稿已被编辑，旧请求的结果不得覆盖新草稿或已存 Token。
            if (_uiState.value.personalAccessToken.filterNot(Char::isWhitespace) != candidate) {
                _uiState.value = _uiState.value.copy(
                    isValidatingPersonalAccessToken = false,
                    personalAccessTokenStatus = "Token 已更改，请重新验证",
                    personalAccessTokenStatusIsError = true,
                )
                return@launch
            }

            result.onSuccess { envelope ->
                val member = envelope.result
                if (envelope.success == true && member != null && member.username.isNotBlank()) {
                    // 这是候选 Token 唯一的持久化点：服务端验证通过后才替换旧值。
                    secureStore.personalAccessToken = candidate
                    _uiState.value = _uiState.value.copy(
                        personalAccessToken = candidate,
                        hasSavedPersonalAccessToken = true,
                        isValidatingPersonalAccessToken = false,
                        personalAccessTokenStatus = "已验证并保存，连接为 ${member.username}",
                        personalAccessTokenStatusIsError = false,
                    )
                    unreadNotificationsStore.refresh()
                } else {
                    _uiState.value = _uiState.value.copy(
                        isValidatingPersonalAccessToken = false,
                        personalAccessTokenStatus = "Token 无效或已失效，未更改已保存的 Token",
                        personalAccessTokenStatusIsError = true,
                    )
                }
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isValidatingPersonalAccessToken = false,
                    personalAccessTokenStatus = patValidationErrorMessage(error),
                    personalAccessTokenStatusIsError = true,
                )
            }
        }
    }

    fun clearSavedPat() {
        if (_uiState.value.isValidatingPersonalAccessToken) return
        secureStore.clearToken()
        _uiState.value = _uiState.value.copy(
            personalAccessToken = "",
            hasSavedPersonalAccessToken = false,
            personalAccessTokenStatus = "已清除保存在本机的 Token",
            personalAccessTokenStatusIsError = false,
        )
        viewModelScope.launch { unreadNotificationsStore.refresh() }
    }

    /** 只返回可控文案，不向 UI 传递可能包含请求细节的底层异常信息。 */
    private fun patValidationErrorMessage(error: Throwable): String = when (error) {
        is IOException -> "网络连接失败，未更改已保存的 Token，请检查网络后重试"
        is HttpException -> when (error.code()) {
            401, 403 -> "Token 无效或权限不足，未更改已保存的 Token"
            else -> "服务器暂时无法验证 Token，未更改已保存的 Token"
        }
        else -> "Token 验证失败，未更改已保存的 Token，请稍后重试"
    }
}
