package com.vibe.v2ex.data.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import org.jsoup.Jsoup

/** Page identity and explicit login/challenge markers only; no settings-layout assumptions. */
@Serializable
internal data class BrowserLoginEvidence(
    val version: Int,
    val path: String,
    val challenge: Boolean,
    val loginForm: Boolean,
    val names: List<String>,
) {
    fun reason(expectedUsername: String): BrowserLoginReason = when {
        version != 1 -> BrowserLoginReason.SETTINGS_PAGE
        challenge -> BrowserLoginReason.CHALLENGE
        loginForm || path in listOf("/signin", "/2fa") -> BrowserLoginReason.LOGIN_REQUIRED
        names.size != 1 || !WebLoginPage.validUsername(names.single()) -> BrowserLoginReason.ACCOUNT_NAVIGATION
        names.single() != expectedUsername -> BrowserLoginReason.ACCOUNT_MISMATCH
        path != "/settings" -> BrowserLoginReason.SETTINGS_PAGE
        else -> BrowserLoginReason.VERIFIED
    }

    companion object {
        private const val EXCLUDED = "script,style,template,noscript,.topic_content,.reply_content,.topic_append"

        fun fromJavascript(raw: String?): BrowserLoginEvidence? = runCatching {
            if (raw == null || raw.length > 16_384) return null
            val json = Json.decodeFromString<String>(raw)
            Json.decodeFromString<BrowserLoginEvidence>(json).takeIf {
                it.version == 1 && it.names.size <= 8 && it.names.all(WebLoginPage::validUsername) &&
                    it.path in listOf("/", "/settings", "/signin", "/2fa", "other")
            }
        }.getOrNull()

        /** The caller has already checked the HTTP response and final settings URL. */
        fun fromHtml(html: String, base: HttpUrl): BrowserLoginEvidence {
            val document = Jsoup.parse(html, base.toString())
            document.select(EXCLUDED).remove()
            document.select("textarea").empty()
            fun local(value: String): HttpUrl? = base.resolve(value)?.takeIf {
                it.sameOrigin(base) && it.username.isEmpty() && it.password.isEmpty()
            }
            val names = document.select(WebLoginPage.ACCOUNT_LINKS).mapNotNull {
                val url = local(it.attr("href")) ?: return@mapNotNull null
                url.encodedPath.removePrefix("/member/").takeIf { name ->
                    url.encodedPath == "/member/$name" && WebLoginPage.validUsername(name)
                }
            }.distinct()
            return BrowserLoginEvidence(
                version = 1,
                path = "/settings",
                challenge = document.title().contains("just a moment", true) ||
                    document.title().contains("attention required", true) || document.select("#challenge-form").isNotEmpty(),
                loginForm = document.select("form[action]").any { local(it.attr("action"))?.encodedPath in listOf("/signin", "/2fa") },
                names = names,
            )
        }

        val script: String = """
            (function() {
              if (location.origin !== '${WebLoginPage.BASE}') return '';
              var doc = document.documentElement.cloneNode(true);
              doc.querySelectorAll('$EXCLUDED').forEach(function(el) { el.remove(); });
              function local(value) {
                try {
                  var url = new URL(value, location.href);
                  return url.origin === location.origin && !url.username && !url.password ? url : null;
                } catch (_) { return null; }
              }
              var names = [];
              doc.querySelectorAll('${WebLoginPage.ACCOUNT_LINKS}').forEach(function(link) {
                var url = local(link.getAttribute('href'));
                var match = url && url.pathname.match(/^\/member\/([A-Za-z0-9_]{1,100})$/);
                if (match && names.indexOf(match[1]) < 0) names.push(match[1]);
              });
              var title = doc.querySelector('title');
              var challenge = /just a moment|attention required/i.test(title ? title.textContent : '') || !!doc.querySelector('#challenge-form');
              var loginForm = Array.from(doc.querySelectorAll('form[action]')).some(function(form) {
                var url = local(form.getAttribute('action'));
                return url && (url.pathname === '/signin' || url.pathname === '/2fa');
              });
              return JSON.stringify({version: 1,
                path: ['/', '/settings', '/signin', '/2fa'].indexOf(location.pathname) >= 0 ? location.pathname : 'other',
                challenge: challenge, loginForm: loginForm, names: names});
            })()
        """.trimIndent()
    }
}

internal enum class BrowserLoginReason(val message: String) {
    VERIFIED(""),
    CHALLENGE("请在当前网页完成网站验证，再点击刷新确认。[CHALLENGE]"),
    LOGIN_REQUIRED("请继续完成网页登录或两步验证。[LOGIN_REQUIRED]"),
    ACCOUNT_NAVIGATION("未识别到唯一的当前账号导航，请刷新重试。[ACCOUNT_NAVIGATION]"),
    ACCOUNT_MISMATCH("确认期间网页账号发生变化，请关闭登录页后重新打开。[ACCOUNT_CHANGED]"),
    SETTINGS_PAGE("尚未到达设置页，请刷新重新确认。[SETTINGS_PAGE]"),
}

/** Cannot be constructed from a username alone. No generated toString exposing credentials. */
class VerifiedBrowserLogin private constructor(internal val session: WebLoginCandidate) {
    companion object {
        internal fun confirm(
            evidence: BrowserLoginEvidence,
            expectedUsername: String,
            url: String?,
            cookie: String,
            userAgent: String,
            isCurrent: () -> Boolean,
        ): VerifiedBrowserLogin? {
            if (!WebLoginPage.isSettings(url) || evidence.reason(expectedUsername) != BrowserLoginReason.VERIFIED ||
                !WebLoginPage.validHeader(cookie) || !WebLoginPage.validHeader(userAgent) || !isCurrent()) return null
            return VerifiedBrowserLogin(WebLoginCandidate(cookie, expectedUsername, userAgent, isCurrent))
        }
    }
}
