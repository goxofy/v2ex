package com.vibe.v2ex.designsystem

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import com.vibe.v2ex.data.remote.ImgurImageLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.net.URI

/** Parsed render tree for V2EX `content_rendered` HTML — mirrors the iOS HTMLText block model. */
sealed interface ContentBlock {
    data class Paragraph(val text: AnnotatedString) : ContentBlock
    data class Code(val code: String) : ContentBlock
    data class Quote(val text: AnnotatedString) : ContentBlock
    data class ListBlock(val items: List<AnnotatedString>) : ContentBlock
    data class QuoteContent(val blocks: List<ContentBlock>) : ContentBlock
    data class ListContent(val items: List<List<ContentBlock>>) : ContentBlock
    data class Image(val url: String) : ContentBlock
    data object Rule : ContentBlock
}

/** Images retain their position inside inline wrappers, lists and quotes. */
fun parseContentBlocks(html: String): List<ContentBlock> {
    if (html.isBlank()) return emptyList()
    return try {
        blockContent(Jsoup.parseBodyFragment(html).body().childNodes(), 0)
    } catch (error: Exception) {
        plainTextFallback(html)
    } catch (error: StackOverflowError) {
        plainTextFallback(html)
    }
}

/** 解析器被某段异常 HTML 绊倒时退回纯文本 —— 一条帖子不该拖垮整个详情页。 */
private fun plainTextFallback(html: String): List<ContentBlock> {
    val text = runCatching { Jsoup.parseBodyFragment(html).text() }.getOrDefault(html).trim()
    return if (text.isEmpty()) emptyList() else listOf(ContentBlock.Paragraph(AnnotatedString(text)))
}

/** `//host/…` → https, `/path` → v2ex.com-rooted; anything else passes through. */
fun absoluteContentUrl(raw: String?): String? {
    val url = raw?.trim().orEmpty()
    return when {
        url.isEmpty() -> null
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "https://www.v2ex.com$url"
        else -> url
    }
}

private data class InlineStyle(
    val span: SpanStyle = SpanStyle(),
    val link: String? = null,
) {
    fun apply(text: String): AnnotatedString = buildAnnotatedString {
        withStyle(span) {
            if (link != null) withLink(LinkAnnotation.Url(link)) { append(text) } else append(text)
        }
    }
}

private fun blockContent(nodes: List<Node>, depth: Int): List<ContentBlock> {
    val blocks = mutableListOf<ContentBlock>()
    val pending = mutableListOf<AnnotatedString>()

    fun flush(): Unit {
        blocks += imageLines(buildAnnotatedString { pending.forEach { append(it) } }.trimWhitespace())
        pending.clear()
    }

    fun walk(children: List<Node>, style: InlineStyle, level: Int): Unit {
        if (level >= 64) {
            pending += style.apply(children.filterIsInstance<TextNode>().joinToString("") { it.wholeText })
            return
        }
        children.forEach { node ->
            when (node) {
                is TextNode -> pending += style.apply(node.wholeText)
                is Element -> when (node.tagName()) {
                    "script", "style" -> Unit
                    "img" -> {
                        val url = absoluteContentUrl(node.attr("src"))
                        val scheme = url?.let { runCatching { URI(it).scheme?.lowercase() }.getOrNull() }
                        if (url != null && scheme in setOf("https", "http")) {
                            flush()
                            blocks += ContentBlock.Image(url)
                        }
                    }
                    "br" -> pending += style.apply("\n")
                    "hr" -> {
                        flush()
                        blocks += ContentBlock.Rule
                    }
                    "pre", "code" -> {
                        val text = node.wholeText()
                        if (node.tagName() == "pre" || text.contains('\n')) {
                            flush()
                            if (text.isNotBlank()) blocks += ContentBlock.Code(text.trim('\n', '\r'))
                        } else {
                            pending += style.copy(span = style.span.merge(SpanStyle(fontFamily = FontFamily.Monospace)))
                                .apply(text)
                        }
                    }
                    "blockquote" -> {
                        flush()
                        val contents = blockContent(node.childNodes(), level + 1)
                        if (contents.all { it is ContentBlock.Paragraph }) {
                            blocks += contents.map { ContentBlock.Quote((it as ContentBlock.Paragraph).text) }
                        } else if (contents.isNotEmpty()) {
                            blocks += ContentBlock.QuoteContent(contents)
                        }
                    }
                    "ul", "ol" -> {
                        flush()
                        val items = node.children().filter { it.tagName() == "li" }
                            .map { blockContent(it.childNodes(), level + 1) }.filter { it.isNotEmpty() }
                        if (items.isNotEmpty()) {
                            blocks += if (items.all { it.size == 1 && it[0] is ContentBlock.Paragraph }) {
                                ContentBlock.ListBlock(items.map { (it[0] as ContentBlock.Paragraph).text })
                            } else {
                                ContentBlock.ListContent(items)
                            }
                        }
                    }
                    "p", "div", "section", "article", "h1", "h2", "h3", "h4", "h5", "h6" -> {
                        flush()
                        val nested = if (node.tagName().startsWith("h")) {
                            style.copy(span = style.span.merge(SpanStyle(fontWeight = FontWeight.Bold)))
                        } else style
                        walk(node.childNodes(), nested, level + 1)
                        flush()
                    }
                    else -> {
                        val nested = when (node.tagName()) {
                            "strong", "b" -> style.copy(span = style.span.merge(SpanStyle(fontWeight = FontWeight.Bold)))
                            "em", "i" -> style.copy(span = style.span.merge(SpanStyle(fontStyle = FontStyle.Italic)))
                            "a" -> style.copy(link = linkTarget(node.attr("href"), node.text()))
                            else -> style
                        }
                        walk(node.childNodes(), nested, level + 1)
                    }
                }
                else -> Unit
            }
        }
    }

    walk(nodes, InlineStyle(), depth)
    flush()
    return blocks
}

/** Only whole direct-URL lines expand; code and descriptive links stay text. */
private fun imageLines(text: AnnotatedString): List<ContentBlock> {
    if (text.isEmpty()) return emptyList()
    val blocks = mutableListOf<ContentBlock>()
    var pending = AnnotatedString.Builder()
    var start = 0
    while (start <= text.length) {
        val newline = text.text.indexOf('\n', start)
        val end = if (newline < 0) text.length else newline
        val line = text.subSequence(start, end)
        val candidate = line.trimWhitespace()
        val url = ImgurImageLink.parse(candidate.text)
        val allowed = candidate.spanStyles.none { it.item.fontFamily == FontFamily.Monospace } &&
            candidate.getLinkAnnotations(0, candidate.length).all {
                (it.item as? LinkAnnotation.Url)?.url == url
            }
        if (url != null && allowed) {
            val before = pending.toAnnotatedString().trimWhitespace()
            if (before.isNotEmpty()) blocks += ContentBlock.Paragraph(before)
            pending = AnnotatedString.Builder()
            blocks += ContentBlock.Image(url)
        } else {
            pending.append(line)
            if (newline >= 0) pending.append('\n')
        }
        if (newline < 0) break
        start = newline + 1
    }
    val remainder = pending.toAnnotatedString().trimWhitespace()
    if (remainder.isNotEmpty()) blocks += ContentBlock.Paragraph(remainder)
    return blocks
}

/** Missing href falls back to a full http(s) URL label, never a descriptive label. */
private fun linkTarget(href: String, label: String): String? {
    val trimmedHref = href.trim()
    if (trimmedHref.isNotEmpty()) return absoluteContentUrl(trimmedHref)
    val candidate = label.trim()
    if (!candidate.startsWith("http://") && !candidate.startsWith("https://")) return null
    val host = runCatching { URI(candidate).host }.getOrNull()
    return if (host.isNullOrBlank()) null else candidate
}

private fun AnnotatedString.trimWhitespace(): AnnotatedString {
    var start = 0
    var end = length
    while (start < end && text[start].isWhitespace()) start++
    while (end > start && text[end - 1].isWhitespace()) end--
    return if (start == 0 && end == length) this else subSequence(start, end)
}
