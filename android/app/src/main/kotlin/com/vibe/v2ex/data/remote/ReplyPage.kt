package com.vibe.v2ex.data.remote

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Separates website controls from user content before interpreting a reply response. */
internal class ReplyPage(html: String, private val url: HttpUrl, private val topicUrl: HttpUrl) {
    data class Form(val url: HttpUrl, val once: String)

    private val document = Jsoup.parse(html, url.toString()).apply {
        select("script, style, textarea, template, noscript, .topic_content, .subtle").forEach { element ->
            // Keep the empty textarea as a form marker, never its echoed draft.
            if (element.tagName() == "textarea") element.empty() else element.remove()
        }
    }
    private val rows: List<Element> = document.select("#Main div.cell[id^=r_]").filter { row ->
        row.parents().none { it.hasClass("reply_content") || it.tagName() == "form" || it.id().startsWith("r_") }
    }
    private val controls = document.clone().apply {
        select(".reply_content, div.cell[id^=r_], .topic_content, .subtle").remove()
    }

    val maximumReplyId: Long = rows.mapNotNull(::replyId).maxOrNull() ?: 0

    fun form(): Form? {
        val candidates = controls.select("#Main form").filter {
            it.attr("method").equals("post", ignoreCase = true) &&
                it.select("textarea[name=content]").size == 1
        }
        val form = candidates.singleOrNull() ?: return null
        val action = url.resolve(form.attr("action").ifBlank { url.toString() }) ?: return null
        if (!action.isReplyTopic(topicUrl) || action.fragment != null) return null
        val token = form.select("input[name=once]").singleOrNull() ?: return null
        if (token.hasAttr("disabled")) return null
        val once = token.attr("value").takeIf { it.isNotBlank() && it.length <= 1024 } ?: return null
        return Form(action, once)
    }

    fun lastPage(): Int {
        val inputPages = controls.select("#Main input.page_input[max]")
            .mapNotNull { it.attr("max").toIntOrNull() }
        val linkPages = controls.select("#Main a[href]").mapNotNull { link ->
            val target = url.resolve(link.attr("href")) ?: return@mapNotNull null
            if (!target.isReplyTopic(topicUrl)) return@mapNotNull null
            target.queryParameter("p")?.toIntOrNull()
        }
        return (inputPages + linkPages).filter { it in 1..100_000 }.maxOrNull() ?: 1
    }

    fun rejection(): String? {
        if (controls.select("form[action*=signin], input[name=password], input[name=two_factor_code]").isNotEmpty()) {
            return "网页会话已失效，请重新登录 V2EX"
        }
        if (controls.select(
                "input[name*=captcha], input[id*=captcha], img[src*=captcha], " +
                    "[class*=captcha], [id*=captcha]",
            ).isNotEmpty() || controls.select("form").any {
                it.text().contains("验证码") && it.select("input[name=code]").isNotEmpty()
            }
        ) return "V2EX 要求网页验证，请先到网页查看回复结果，避免重复发送；草稿已保留"
        val problems = controls.select("div.problem")
        if (problems.isNotEmpty()) {
            // Do not expose arbitrary page contents or transport metadata in errors.
            return if (problems.text().contains("你上一次回复是在")) {
                "回复过于频繁，V2EX 有回复间隔限制，请稍后再试"
            } else {
                "回复被服务器拒绝，请到网页查看具体原因；草稿已保留"
            }
        }
        return null
    }

    fun confirms(submitted: String, username: String, baseline: Long): Boolean {
        val expected = normalized(submitted)
        if (expected.isEmpty()) return false
        val idCounts = rows.mapNotNull(::replyId).groupingBy { it }.eachCount()
        return rows.any { row ->
            val id = replyId(row) ?: return@any false
            if (id <= baseline || idCounts[id] != 1) return@any false
            val content = row.select(".reply_content").singleOrNull() ?: return@any false
            val authors = row.select("strong > a[href]").filter { link ->
                link.parents().none { it.hasClass("reply_content") }
            }
            val author = authors.singleOrNull() ?: return@any false
            val authorUrl = url.resolve(author.attr("href")) ?: return@any false
            if (!authorUrl.sameOrigin(topicUrl) ||
                !authorUrl.encodedPath.equals("/member/$username", ignoreCase = true) ||
                !author.text().equals(username, ignoreCase = true)
            ) return@any false
            // Rich content that cannot be compared losslessly stays unconfirmed.
            if (content.select("img, video, audio, iframe, object, input").isNotEmpty()) return@any false
            if (!hasReliableLinks(content)) return@any false
            val text = content.clone().apply { select("textarea").remove() }.wholeText()
            normalized(text) == expected
        }
    }

    private fun hasReliableLinks(content: Element): Boolean = content.select("a[href]").all { link ->
        val target = url.resolve(link.attr("href")) ?: return@all false
        val label = link.text().trim()
        if (target.sameOrigin(topicUrl) && target.encodedPath == "/member/$label" &&
            target.query == null && target.fragment == null
        ) {
            true
        } else {
            label.toHttpUrlOrNull() == target
        }
    }

    private fun replyId(row: Element): Long? = row.id().removePrefix("r_").toLongOrNull()?.takeIf { it > 0 }

    private fun normalized(value: String): String = WHITESPACE.replace(value, " ").trim()

    private companion object {
        val WHITESPACE = Regex("[\\s\\p{Z}]+")
    }
}

internal fun HttpUrl.sameOrigin(other: HttpUrl): Boolean =
    scheme == other.scheme && host == other.host && port == other.port && username.isEmpty() && password.isEmpty()

internal fun HttpUrl.isReplyTopic(topicUrl: HttpUrl): Boolean =
    sameOrigin(topicUrl) && encodedPath == topicUrl.encodedPath
