package com.vibe.v2ex.data.remote

import okhttp3.HttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

@kotlinx.serialization.Serializable
internal data class MoveNode(val name: String, val title: String, val aliases: List<String> = emptyList()) {
    fun matches(query: String): Boolean = (listOf(name, title) + aliases).any { it.contains(query.trim(), true) }
}

internal data class MoveForm(val shortcuts: List<MoveNode>, val hidden: Map<String, String>)
internal data class MoveTopic(val node: MoveNode, val canMove: Boolean)

internal class MoveFailure(val kind: Kind) : IllegalStateException(kind.message) {
    enum class Kind(val message: String) {
        UNAVAILABLE("无法读取移动权限或表单，请到网页核查"),
        SESSION("网页会话已变化或失效，请重新打开话题"),
        DENIED("当前账号没有此话题的移动权限，或权限已撤销"),
        CHANGED("话题节点已变化，请核对当前节点并重新确认"),
        REJECTED("服务器未接受移动，请到网页核查"),
        UNCONFIRMED("移动结果待确认，请先到网页核查，避免重复操作"),
    }
}

internal class TopicMovePage(html: String, private val base: HttpUrl) {
    private val document = Jsoup.parse(html, base.toString()).also {
        it.select("script,style,template,noscript,.topic_content,.reply_content,.topic_append,.subtle").remove()
        it.select("textarea").empty()
    }

    fun topic(topicId: Long): MoveTopic {
        checkPage()
        val headers = mainBoxes().flatMap { it.children() }.filter { it.hasClass("header") && it.select("h1").size == 1 }
        val header = headers.singleOrNull() ?: throw MoveFailure(MoveFailure.Kind.UNAVAILABLE)
        val nodes = header.select("a[href]").mapNotNull { nodeLink(it) }.distinctBy { it.name }
        val node = nodes.singleOrNull() ?: throw MoveFailure(MoveFailure.Kind.UNAVAILABLE)
        val links = header.select("a.op[href]").filter {
            val url = base.resolve(it.attr("href"))
            url != null && url.sameOrigin(base) && url.encodedPath == "/move/topic/$topicId" &&
                url.query == null && url.fragment == null && url.username.isEmpty() && url.password.isEmpty()
        }
        return MoveTopic(node, links.size == 1)
    }

    fun form(topicId: Long): MoveForm {
        checkPage()
        val forms = mainBoxes().flatMap { box ->
            box.children().filter { it.hasClass("inner") }.flatMap { it.select("form") }
        }.filter { it.select("select[name=destination]").isNotEmpty() }
        val form = forms.singleOrNull() ?: throw MoveFailure(MoveFailure.Kind.DENIED)
        val action = base.resolve(form.attr("action")) ?: throw MoveFailure(MoveFailure.Kind.UNAVAILABLE)
        if (!action.sameOrigin(base) || action.encodedPath != "/move/topic/$topicId" || action.query != null ||
            action.fragment != null || action.username.isNotEmpty() || action.password.isNotEmpty() ||
            !form.attr("method").equals("post", true) ||
            form.select("select[name=destination]").size != 1 || form.select("input[name=memo]").size != 1
        ) throw MoveFailure(MoveFailure.Kind.UNAVAILABLE)
        val hidden = mutableMapOf<String, String>()
        for (field in form.select("input[name],select[name],textarea[name],button[name]")) {
            val name = field.attr("name")
            if (name == "destination" && field.tagName() == "select") continue
            if (name == "memo" && field.tagName() == "input" && field.attr("type").lowercase() in listOf("", "text")) continue
            if (field.attr("type").equals("hidden", true) && name in setOf("once", "csrf", "csrf_token", "_csrf", "_xsrf") &&
                !hidden.containsKey(name) && field.attr("value").isNotBlank()
            ) hidden[name] = field.attr("value") else throw MoveFailure(MoveFailure.Kind.UNAVAILABLE)
        }
        val box = form.parents().firstOrNull { it.hasClass("box") } ?: throw MoveFailure(MoveFailure.Kind.UNAVAILABLE)
        val shortcuts = box.select("a.node-xl[href]").mapNotNull { link ->
            val name = Regex("^javascript:chooseNode\\(['\"]([A-Za-z0-9_]+)['\"]\\);?$")
                .matchEntire(link.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
            MoveNode(name, link.text().trim().ifEmpty { name })
        }.distinctBy { it.name }
        return MoveForm(shortcuts, hidden)
    }

    fun checkPage(): Unit {
        if (document.select("form[action='/signin'],form[action='/2fa'],input[type=password],#Top a[href='/signin']").isNotEmpty()) {
            throw MoveFailure(MoveFailure.Kind.SESSION)
        }
        if (document.select("#Main .problem,#Main #problem,#Wrapper > .content .problem,#Wrapper > .content #problem").isNotEmpty()) {
            throw MoveFailure(MoveFailure.Kind.REJECTED)
        }
        if (document.title().contains("just a moment", true) || document.title().contains("attention required", true)) {
            throw MoveFailure(MoveFailure.Kind.UNAVAILABLE)
        }
    }

    private fun mainBoxes(): List<Element> {
        val mains = document.select("#Main")
        val container = if (mains.isNotEmpty()) {
            mains.singleOrNull() ?: throw MoveFailure(MoveFailure.Kind.UNAVAILABLE)
        } else {
            // Mobile pages omit Main; do not broaden this to arbitrary descendant boxes.
            val wrapper = document.select("#Wrapper").singleOrNull()?.takeIf { it.tagName() == "div" }
                ?: throw MoveFailure(MoveFailure.Kind.UNAVAILABLE)
            wrapper.children().filter { it.tagName() == "div" && it.hasClass("content") }.singleOrNull()
                ?: throw MoveFailure(MoveFailure.Kind.UNAVAILABLE)
        }
        return container.children().filter { it.tagName() == "div" && it.hasClass("box") }
    }

    private fun nodeLink(link: Element): MoveNode? {
        val url = base.resolve(link.attr("href")) ?: return null
        if (!url.sameOrigin(base) || url.query != null || url.fragment != null || url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        val name = Regex("^/go/([A-Za-z0-9_]+)$").matchEntire(url.encodedPath)?.groupValues?.get(1) ?: return null
        return MoveNode(name, link.text().trim().ifEmpty { name })
    }
}
