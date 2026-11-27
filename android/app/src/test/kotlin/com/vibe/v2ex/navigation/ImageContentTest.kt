package com.vibe.v2ex.navigation

import com.vibe.v2ex.data.remote.ImgurImageLink
import com.vibe.v2ex.designsystem.ContentBlock
import com.vibe.v2ex.designsystem.parseContentBlocks
import org.junit.Assert.*
import org.junit.Test

class ImageContentTest {
    private val raw = "https://i.imgur.com/v3lpwO1.jpeg"

    @Test fun nestedImagesPreserveTextAndSource(): Unit {
        for (wrapper in listOf("span", "div", "strong", "em", "a href='/t/123'")) {
            val tag = wrapper.substringBefore(' ')
            val blocks = parseContentBlocks("<p>前<$wrapper>中<img src='$raw'>后</$tag>末</p>")
            assertEquals(wrapper, listOf(raw), images(blocks))
            assertEquals(wrapper, "前中后末", texts(blocks).joinToString(""))
        }
        val blocks = parseContentBlocks("<A HREF='https://imgur.com/gallery/album'>前<IMG SRC='$raw'><img src=\"https://example.com/a.png\">后</A>")
        assertEquals(listOf(raw, "https://example.com/a.png"), images(blocks))
        assertEquals(listOf("前", "后"), texts(blocks))
    }

    @Test fun quotesAndListItemsKeepImageContent(): Unit {
        val quote = parseContentBlocks("<blockquote><p>前<span><img src='$raw'></span>后</p></blockquote>")
        assertTrue(quote.first() is ContentBlock.QuoteContent)
        assertEquals(listOf(raw), images(quote))
        assertEquals(listOf("前", "后"), texts(quote))
        val list = parseContentBlocks("<ul><li>一<img src='$raw'>二</li><li>三</li></ul>")
        val items = (list.first() as ContentBlock.ListContent).items
        assertEquals(2, items.size)
        assertEquals(listOf(raw), images(items[0]))
        assertEquals(listOf("一", "二"), texts(items[0]))
        assertEquals(listOf("三"), texts(items[1]))
    }

    @Test fun standaloneRawURLAndServerRenderingExpandOnce(): Unit {
        for (html in listOf(raw, "<p>$raw</p>", "<a href='$raw'>$raw</a>", "<img src='$raw'>")) {
            assertEquals(html, listOf(ContentBlock.Image(raw)), parseContentBlocks(html))
        }
        val blocks = parseContentBlocks("前\n$raw\n后<br>$raw")
        assertEquals(listOf(raw, raw), images(blocks))
        assertEquals(listOf("前", "后"), texts(blocks))
    }

    @Test fun descriptiveLinksCodeAndEscapedExamplesStayText(): Unit {
        for (html in listOf(
            "看看 $raw", "<a href='$raw'>查看图片</a>", "<a href='https://example.com/'>$raw</a>",
            "<code>$raw</code>", "<pre>$raw</pre>", "&lt;img src='$raw'&gt;", "https://imgur.com/gallery/album",
        )) {
            assertTrue(html, images(parseContentBlocks(html)).isEmpty())
        }
    }

    @Test fun mentionAndFormattingRemainAroundImages(): Unit {
        val blocks = parseContentBlocks("<strong>粗<img src='$raw'>体</strong> <a href='/member/alice'>@alice</a>")
        assertEquals(listOf(raw), images(blocks))
        assertEquals(listOf("粗", "体 @alice"), texts(blocks))
        val last = (blocks.last() as ContentBlock.Paragraph).text
        assertEquals("https://www.v2ex.com/member/alice", (last.getLinkAnnotations(0, last.length).last().item as androidx.compose.ui.text.LinkAnnotation.Url).url)
        assertTrue((blocks.first() as ContentBlock.Paragraph).text.spanStyles.any { it.item.fontWeight == androidx.compose.ui.text.font.FontWeight.Bold })
    }

    @Test fun onlyOriginalImgurURLsAreAccepted(): Unit {
        assertEquals(raw, ImgurImageLink.parse(raw))
        for (value in listOf(
            "http://i.imgur.com/a.jpg", "https://imgur.com/a.jpg", "https://i.imgur.com.evil.test/a.jpg",
            "https://user@i.imgur.com/a.jpg", "https://i.imgur.com:8443/a.jpg", "https://i.imgur.com/a.jpg?x=1",
            "https://i.imgur.com/a.jpg#x", "https://i.imgur.com/a.html", "https://i.imgur.com/dir/a.jpg",
            "https://i.imgur.com/%61.jpg", "javascript:alert(1)",
        )) assertNull(value, ImgurImageLink.parse(value))
        assertTrue(images(parseContentBlocks("<img src='javascript:alert(1)'>")).isEmpty())
    }

    private fun images(blocks: List<ContentBlock>): List<String> = blocks.flatMap {
        when (it) {
            is ContentBlock.Image -> listOf(it.url)
            is ContentBlock.QuoteContent -> images(it.blocks)
            is ContentBlock.ListContent -> it.items.flatMap(::images)
            else -> emptyList()
        }
    }

    private fun texts(blocks: List<ContentBlock>): List<String> = blocks.flatMap {
        when (it) {
            is ContentBlock.Paragraph -> listOf(it.text.text)
            is ContentBlock.Quote -> listOf(it.text.text)
            is ContentBlock.ListBlock -> it.items.map { text -> text.text }
            is ContentBlock.QuoteContent -> texts(it.blocks)
            is ContentBlock.ListContent -> it.items.flatMap(::texts)
            else -> emptyList()
        }
    }
}
