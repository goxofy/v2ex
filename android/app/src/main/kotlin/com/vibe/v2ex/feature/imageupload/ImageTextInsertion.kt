package com.vibe.v2ex.feature.imageupload

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.text.BreakIterator
import java.util.Locale

object ImageTextInsertion {
    fun insert(url: String, value: TextFieldValue): TextFieldValue {
        val range = normalized(value.selection, value.text)
        val insertion = "\n$url\n"
        val text = value.text.replaceRange(range.min, range.max, insertion)
        return TextFieldValue(text, TextRange(range.min + insertion.length))
    }

    fun normalized(selection: TextRange, text: String): TextRange {
        val lower = selection.min.coerceIn(0, text.length)
        val upper = selection.max.coerceIn(lower, text.length)
        val boundaries = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
        val start = if (boundaries.isBoundary(lower)) lower else boundaries.preceding(lower).coerceAtLeast(0)
        val end = if (selection.collapsed) start else {
            if (boundaries.isBoundary(upper)) upper else boundaries.following(upper).let { if (it < 0) text.length else it }
        }
        return TextRange(start, end)
    }

    /** Programmatic draft/mention updates must not retain an invalid IME composition. */
    fun synchronize(value: TextFieldValue, text: String): TextFieldValue =
        if (value.text == text) value else TextFieldValue(text, normalized(value.selection, text))
}
