package com.vibe.v2ex.feature.imageupload

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.*
import org.junit.Test

class ImageTextInsertionTest {
    private val url = "https://i.imgur.com/v3lpwO1.jpeg"
    private val insertion = "\n$url\n"

    @Test fun insertsAtBeginningMiddleAndEnd(): Unit {
        for (position in listOf(0, 2, 4)) {
            val result = ImageTextInsertion.insert(url, TextFieldValue("中文内容", TextRange(position)))
            assertEquals("中文内容".take(position) + insertion + "中文内容".drop(position), result.text)
            assertEquals(TextRange(position + insertion.length), result.selection)
        }
    }

    @Test fun replacesSelectionAndCommitsComposition(): Unit {
        val result = ImageTextInsertion.insert(url, TextFieldValue("甲乙丙丁", TextRange(3, 1), TextRange(1, 3)))
        assertEquals("甲${insertion}丁", result.text)
        assertEquals(TextRange(1 + insertion.length), result.selection)
        assertNull(result.composition)
    }

    @Test fun preservesExistingNewlinesAndAllowsRepeatedImages(): Unit {
        val first = ImageTextInsertion.insert(url, TextFieldValue("\n\n", TextRange(1)))
        val second = ImageTextInsertion.insert(url, first)
        assertEquals("\n$insertion$insertion\n", second.text)
        assertEquals(insertion, ImageTextInsertion.insert(url, TextFieldValue()).text)
    }

    @Test fun doesNotSplitSurrogatePairsOrCombiningMarks(): Unit {
        assertEquals("a${insertion}😀b", ImageTextInsertion.insert(url, TextFieldValue("a😀b", TextRange(2))).text)
        assertEquals("a${insertion}b", ImageTextInsertion.insert(url, TextFieldValue("a😀b", TextRange(2, 3))).text)
        assertEquals("${insertion}é", ImageTextInsertion.insert(url, TextFieldValue("é", TextRange(1))).text)
        assertEquals(TextRange(3), ImageTextInsertion.normalized(TextRange(Int.MAX_VALUE), "abc"))
    }

    @Test fun synchronizesLatestTextWithoutRetainingStaleComposition(): Unit {
        val editor = TextFieldValue("旧正文", TextRange(1), TextRange(0, 2))
        assertSame(editor, ImageTextInsertion.synchronize(editor, editor.text))
        val latest = ImageTextInsertion.synchronize(editor, "新的中文正文")
        assertNull(latest.composition)
        assertEquals("新${insertion}的中文正文", ImageTextInsertion.insert(url, latest).text)
    }
}
