package com.vibe.v2ex.data.remote

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class BrowserLoginEvidenceTest {
    private val base = "${WebLoginPage.BASE}/".toHttpUrl()
    private val username = "fixture_user"
    private val settings = """<div id="Top"><a class="top" href="/member/fixture_user">Account</a></div>
        <div id="Main"><form method="post" action="/settings"><input name="once" value="secret-once">
        <textarea name="bio">private-profile</textarea></form></div>"""

    private fun evidence(html: String = settings): BrowserLoginEvidence = BrowserLoginEvidence.fromHtml(html, base)
    private fun javascript(value: BrowserLoginEvidence): String = Json.encodeToString(Json.encodeToString(value))
    private fun confirm(
        value: BrowserLoginEvidence = evidence(),
        url: String? = "${WebLoginPage.BASE}/settings",
        cookie: String = "PB3_SESSION=new-cookie",
        userAgent: String = "WebView fixture UA",
        isCurrent: () -> Boolean = { true },
    ): VerifiedBrowserLogin? = VerifiedBrowserLogin.confirm(value, username, url, cookie, userAgent, isCurrent)

    @Test fun generalSettingsNeedsNoFormNonceProfileFieldsOrSpecificContainer(): Unit {
        // Synthetic general-tab fixture: deliberately no #Main, form, once, bio or signout link.
        val general = """<nav class="site-nav"><a href="/member/fixture_user">Account</a></nav>
            <section><h1>设置</h1><nav>通用 个人资料 头像 密码</nav>
            <div>楼中楼显示方式</div><button type="button" aria-pressed="true">树形缩进</button></section>"""
        val parsed = evidence(general)
        assertEquals(BrowserLoginReason.VERIFIED, parsed.reason(username))
        assertEquals(WebSessionVerification.AUTHENTICATED, WebLoginPage.classify(general, base, username))
        assertNotNull(confirm(BrowserLoginEvidence.fromJavascript(javascript(parsed))!!))
    }

    @Test fun settingsQueryAndFragmentDoNotChangeTheProtectedPath(): Unit {
        for (url in listOf("${WebLoginPage.BASE}/settings?tab=general", "${WebLoginPage.BASE}/settings#display")) {
            assertNotNull(confirm(url = url))
            assertNull(confirm(value = evidence().copy(challenge = true), url = url))
            assertNull(confirm(value = evidence().copy(loginForm = true), url = url))
        }
    }

    @Test fun bioOnlyFormWithoutSignoutLinkConfirms(): Unit {
        assertEquals(BrowserLoginReason.VERIFIED, evidence().reason(username))
        assertEquals(WebSessionVerification.AUTHENTICATED, WebLoginPage.classify(settings, base, username))
        assertNotNull(confirm())
    }

    @Test fun javascriptSignoutDoesNotInvalidateProtectedSettingsForm(): Unit {
        val html = settings.replace("</a>", "</a><a class='top' href='javascript:signout();'>退出</a>")
        assertEquals(BrowserLoginReason.VERIFIED, evidence(html).reason(username))
    }

    @Test fun desktopMobileAndEmptyActionShareStructuralRules(): Unit {
        for (html in listOf(settings, settings.replace("action=\"/settings\"", ""),
            settings.replace("id=\"Top\"", "id=\"site-header-menu\"")
                .replace("<div id=\"Main\">", "<div id=\"Wrapper\"><div class=\"content\">") + "</div>",
            settings.replace("id=\"Top\"", "class=\"site-nav\""))) {
            assertEquals(BrowserLoginReason.VERIFIED, evidence(html).reason(username))
        }
    }

    @Test fun settingsFormLayoutIsNotAnAuthenticationRequirement(): Unit {
        for (html in listOf(
            settings.replace("method=\"post\"", "method=\"get\""),
            settings.replace("name=\"once\"", "name=\"not_once\""),
            settings.replace("</form>", "<input name='once'></form>"),
            settings.replace("name=\"bio\"", "name=\"not_bio\""),
            settings.replace("action=\"/settings\"", "action=\"https://other.invalid/settings\""),
            settings.replace("action=\"/settings\"", "action=\"https://user@www.v2ex.com/settings\""),
            settings.replace("action=\"/settings\"", "action=\"/settings?return=other\""),
            settings.replace("action=\"/settings\"", "action=\"/settings#other\""),
            settings.replace("action=\"/settings\"", "action=\"/other\""),
            settings + "<div id='Main'></div>",
        )) {
            assertEquals(BrowserLoginReason.VERIFIED, evidence(html).reason(username))
            assertNotNull(confirm(evidence(html)))
        }
    }

    @Test fun userContentAndSidebarsCannotSupplyIdentity(): Unit {
        for (html in listOf(
            "<div class='reply_content'>$settings</div>",
            "<div class='topic_content'>$settings</div>",
            settings.replace("id=\"Top\"", "id=\"Rightbar\""),
            settings.replace("/member/fixture_user", "https://other.invalid/member/fixture_user"),
        )) assertNull(confirm(evidence(html)))
    }

    @Test fun accountMustBeUniqueAndMatchTheNavigatingCandidate(): Unit {
        assertEquals(BrowserLoginReason.ACCOUNT_MISMATCH, evidence(settings.replace("/member/fixture_user", "/member/other")).reason(username))
        val multiple = settings.replace("</a>", "</a><a class='top' href='/member/other'>Other</a>")
        assertEquals(BrowserLoginReason.ACCOUNT_NAVIGATION, evidence(multiple).reason(username))
        val duplicate = settings.replace("</a>", "</a><a class='avatar' href='/member/fixture_user'>Avatar</a>")
        assertEquals(BrowserLoginReason.VERIFIED, evidence(duplicate).reason(username))
    }

    @Test fun challengesAndLoginFormsOverrideValidLookingEvidence(): Unit {
        for (prefix in listOf("<title>Just a moment...</title>", "<title>Attention Required!</title>", "<form id='challenge-form'></form>")) {
            assertEquals(BrowserLoginReason.CHALLENGE, evidence(prefix + settings).reason(username))
            assertNull(confirm(evidence(prefix + settings)))
        }
        for (path in listOf("/signin", "/2fa")) {
            val value = evidence("<form action='$path'></form>$settings")
            assertEquals(BrowserLoginReason.LOGIN_REQUIRED, value.reason(username))
            assertNull(confirm(value))
        }
        assertNull(confirm(evidence("Unknown page")))
    }

    @Test fun onlySameOriginHttpsSettingsPathCanProduceBrowserProof(): Unit {
        for (url in listOf(null, "about:blank", "${WebLoginPage.BASE}/", "${WebLoginPage.BASE}/settings/other",
            "${WebLoginPage.BASE}/signin", "${WebLoginPage.BASE}/2fa", "http://www.v2ex.com/settings", "https://www.v2ex.com:444/settings",
            "https://other.invalid/settings", "https://user@www.v2ex.com/settings")) assertNull(confirm(url = url))
        assertNotNull(confirm())
    }

    @Test fun emptyOrMalformedCredentialsAndStalePagesCannotProduceProof(): Unit {
        assertNull(confirm(cookie = ""))
        assertNull(confirm(cookie = "cookie\r\ninjected"))
        assertNull(confirm(userAgent = ""))
        assertNull(confirm(userAgent = "UA\nInjected"))
        assertNull(confirm(isCurrent = { false }))
    }

    @Test fun proofKeepsLatestCookieAndChecksPageAgainBeforeSaving(): Unit {
        var current = true
        val proof = confirm(cookie = "PB3_SESSION=latest", isCurrent = { current })!!
        assertEquals("PB3_SESSION=latest", proof.session.cookie)
        assertTrue(proof.session.isCurrent())
        current = false
        assertFalse(proof.session.isCurrent())
        assertFalse(proof.toString().contains("latest"))
        assertFalse(proof.session.toString().contains("latest"))
    }

    @Test fun javascriptEvidenceIsStrictBoundedJsonWithoutInputValues(): Unit {
        val value = evidence()
        val encoded = javascript(value)
        assertEquals(value, BrowserLoginEvidence.fromJavascript(encoded))
        assertFalse(encoded.contains("secret-once"))
        assertFalse(encoded.contains("private-profile"))
        for (raw in listOf(null, "null", "true", "{}", "\"broken\"", Json.encodeToString("{}"), " ".repeat(16_385),
            javascript(value.copy(version = 2)), javascript(value.copy(names = listOf("bad/name"))),
            javascript(value.copy(names = List(9) { "name$it" })), javascript(value.copy(path = "/settings?secret=value")))) {
            assertNull(BrowserLoginEvidence.fromJavascript(raw))
        }
    }

    @Test fun diagnosticsContainFixedCodesNotAccountOrPageContents(): Unit {
        for (reason in BrowserLoginReason.entries.filter { it != BrowserLoginReason.VERIFIED }) {
            assertTrue(reason.message.contains("["))
            assertFalse(reason.message.contains(username))
            assertFalse(reason.message.contains("private-profile"))
        }
    }
}
