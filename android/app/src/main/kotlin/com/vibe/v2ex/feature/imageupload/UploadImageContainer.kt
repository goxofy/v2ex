package com.vibe.v2ex.feature.imageupload

import java.io.DataInputStream
import java.io.File
import java.io.IOException

/** Reject animation containers before BitmapFactory can silently decode only the first frame. */
internal object UploadImageContainer {
    fun isAnimatedPNG(file: File): Boolean = DataInputStream(file.inputStream().buffered()).use { input ->
        if (input.readLong() != -8552249625308161526L) throw IOException("Invalid PNG signature")
        var remaining = file.length() - 8
        while (remaining >= 12) {
            val length = input.readInt()
            val type = input.readInt()
            if (length < 0 || length.toLong() + 12 > remaining) throw IOException("Invalid PNG chunk")
            if (type == 0x6163544c) return@use true // acTL must precede IDAT.
            if (type == 0x49444154 || type == 0x49454e44) return@use false
            skipFully(input, length.toLong() + 4)
            remaining -= length.toLong() + 12
        }
        throw IOException("Incomplete PNG container")
    }

    fun isHEIFSequence(file: File): Boolean = DataInputStream(file.inputStream().buffered()).use { input ->
        var remaining = file.length()
        while (remaining >= 8) {
            var size = input.readInt().toLong() and 0xffff_ffffL
            val type = input.readInt()
            var header = 8L
            if (size == 1L) { size = input.readLong(); header = 16L }
            if (size == 0L) size = remaining
            if (size < header || size > remaining) throw IOException("Invalid HEIF box")
            if (type == 0x66747970) { // ftyp: major brand, minor version, compatible brands.
                val payload = size - header
                if (payload < 8 || payload % 4 != 0L) throw IOException("Invalid HEIF brands")
                var sequence = input.readInt() == 0x6d736631 // msf1: HEIF image sequence.
                input.readInt()
                var brands = (payload - 8) / 4
                while (brands-- > 0) {
                    if (input.readInt() == 0x6d736631) sequence = true
                }
                return@use sequence
            }
            skipFully(input, size - header)
            remaining -= size
        }
        throw IOException("Missing HEIF file type")
    }

    private fun skipFully(input: DataInputStream, count: Long): Unit {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) throw IOException("Incomplete image container")
            remaining -= skipped
        }
    }
}
