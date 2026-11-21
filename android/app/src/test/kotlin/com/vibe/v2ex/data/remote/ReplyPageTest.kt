package com.vibe.v2ex.data.remote

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyPageTest {
    private val topicUrl: HttpUrl = "https://www.v2ex.com/t/42".toHttpUrl()
    private val form: String = """<form method="post" action="/t/42"><input name="once" value="123"><textarea name="content"></textarea></form>"""

    @Test
    fun mobileFormStillRequiresUniqueTokenAndSameTopicAction(): Unit {
        val invalidForms = listOf(
            "",
            form.replace("<input name=\"once\" value=\"123\">", ""),
            form.replace("value=\"123\"", "value=\"\""),
            form.replace("name=\"once\"", "disabled name=\"once\""),
            form.replace("</form>", "<input name='once' value='456'></form>"),
            form.replace("/t/42", "https://other.invalid/t/42"),
            form.replace("/t/42", "/t/43"),
            form.replace("/t/42", "/t/42#reply"),
            form.replace("method=\"post\"", "method=\"get\""),
            form + form,
        )
        invalidForms.forEach { assertNull(parse(mobile(it)).form()) }
    }

    @Test
    fun ambiguousOrNestedMobileContainersAreRejected(): Unit {
        val invalidPages = listOf(
            mobile(form) + mobile(form),
            "<div id='Wrapper'><div class='content'>$form</div><div class='content'></div></div>",
            "<div id='Wrapper'><section><div class='content'>$form</div></section></div>",
            "<div id='Main'></div><div id='Main'></div>" + mobile(form),
        )
        invalidPages.forEach { assertNull(parse(it).form()) }
    }

    @Test
    fun sidebarControlsAndRepliesAreNotUsed(): Unit {
        val sidebar = "<aside>$form<a href='/t/42?p=99'>99</a>${reply(900, "hello")}</aside>"
        val page = parse(mobile("") + sidebar)
        assertNull(page.form())
        assertEquals(1, page.lastPage())
        assertEquals(0L, page.maximumReplyId)
        assertFalse(page.confirms("hello", "alice", 0))
    }

    @Test
    fun userContentCannotSupplyAMobileForm(): Unit {
        for (cssClass in listOf("topic_content", "topic_append", "reply_content", "subtle")) {
            assertNull(parse(mobile("<div class='$cssClass'>$form</div>")).form())
        }
    }

    @Test
    fun userContentCannotSupplyAMainContainer(): Unit {
        val fake = "<div id='Main'>$form${reply(999, "hello")}</div>"
        for (cssClass in listOf("topic_content", "topic_append", "reply_content", "subtle")) {
            val page = parse(mobile("<div class='$cssClass'>$fake</div>"))
            assertNull(page.form())
            assertEquals(0L, page.maximumReplyId)
            assertFalse(page.confirms("hello", "alice", 0))
        }
    }

    @Test
    fun fakeControlsInMobileUserContentDoNotOverrideTheRealPage(): Unit {
        val fake = """<div id="Main">$form<input class="page_input" max="99"><a href="/t/42?p=99">99</a><div id="challenge-form"></div><div class="problem">rejected</div>${reply(999, "forged")}</div>"""
        val content = listOf("topic_content", "topic_append", "reply_content", "subtle")
            .joinToString("") { "<div class='$it'>$fake</div>" }
        val page = parse(mobile(content + form + reply(101, "hello") + "<a href='/t/42?p=2'>2</a>"))
        assertEquals("123", page.form()?.once)
        assertEquals(2, page.lastPage())
        assertEquals(101L, page.maximumReplyId)
        assertNull(page.rejection())
        assertTrue(page.confirms("hello", "alice", 100))
        assertFalse(page.confirms("forged", "alice", 100))
    }

    @Test
    fun desktopMainTakesPrecedenceOverMobileWrapper(): Unit {
        val page = parse("<div id='Main'>$form${reply(101, "hello")}</div>" + mobile(reply(999, "forged")))
        assertEquals("123", page.form()?.once)
        assertEquals(101L, page.maximumReplyId)
        assertTrue(page.confirms("hello", "alice", 100))
        assertFalse(page.confirms("forged", "alice", 100))
    }

    @Test
    fun mobileRepliesStillRequireUniqueNewIds(): Unit {
        val page = parse(mobile(form + reply(101, "hello") + reply(101, "hello")))
        assertEquals(101L, page.maximumReplyId)
        assertFalse(page.confirms("hello", "alice", 100))
        assertFalse(parse(mobile(reply(100, "hello"))).confirms("hello", "alice", 100))
    }

    private fun parse(html: String): ReplyPage = ReplyPage(html, topicUrl, topicUrl)

    private fun mobile(content: String): String = "<div id='Wrapper'><div class='content'>$content</div></div>"

    private fun reply(id: Long, content: String): String =
        """<div class="cell" id="r_$id"><strong><a href="/member/alice">alice</a></strong><div class="reply_content">$content</div></div>"""
}
