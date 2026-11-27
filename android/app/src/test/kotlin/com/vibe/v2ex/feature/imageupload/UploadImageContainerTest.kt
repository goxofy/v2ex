package com.vibe.v2ex.feature.imageupload

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

class UploadImageContainerTest {
    private fun withFile(bytes: ByteArray, block: (File) -> Unit): Unit {
        val directory = System.getenv("CLAUDE_JOB_DIR")?.let { File(it, "tmp") }
        val file = File.createTempFile("image-container-", ".fixture", directory)
        try { file.writeBytes(bytes); block(file) } finally { file.delete() }
    }

    private fun png(animated: Boolean): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeLong(-8552249625308161526L)
            output.writeInt(4); output.writeBytes("IHDR"); output.writeInt(0); output.writeInt(0)
            output.writeInt(0); output.writeBytes(if (animated) "acTL" else "IDAT"); output.writeInt(0)
        }
        return bytes.toByteArray()
    }

    private fun heif(major: String, compatible: List<String>, extended: Boolean = false): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(8); output.writeBytes("free")
            val size = 16 + compatible.size * 4 + if (extended) 8 else 0
            output.writeInt(if (extended) 1 else size); output.writeBytes("ftyp")
            if (extended) output.writeLong(size.toLong())
            output.writeBytes(major); output.writeInt(0)
            compatible.forEach(output::writeBytes)
        }
        return bytes.toByteArray()
    }

    @Test fun distinguishesStaticPNGFromAPNG(): Unit {
        withFile(png(false)) { assertFalse(UploadImageContainer.isAnimatedPNG(it)) }
        withFile(png(true)) { assertTrue(UploadImageContainer.isAnimatedPNG(it)) }
    }

    @Test fun rejectsHEIFSequenceMajorAndCompatibleBrands(): Unit {
        withFile(heif("heic", listOf("mif1", "heic"))) { assertFalse(UploadImageContainer.isHEIFSequence(it)) }
        withFile(heif("msf1", listOf("hevc"))) { assertTrue(UploadImageContainer.isHEIFSequence(it)) }
        withFile(heif("hevc", listOf("mif1", "msf1"))) { assertTrue(UploadImageContainer.isHEIFSequence(it)) }
    }

    @Test fun acceptsExtendedSizeBoxesWithoutTruncatingLengths(): Unit {
        withFile(heif("heic", listOf("mif1"), extended = true)) { assertFalse(UploadImageContainer.isHEIFSequence(it)) }
        withFile(heif("hevc", listOf("msf1"), extended = true)) { assertTrue(UploadImageContainer.isHEIFSequence(it)) }
    }

    @Test fun truncatedAndInvalidContainersFailClosed(): Unit {
        for (bytes in listOf(byteArrayOf(), png(true).copyOf(18), ByteArray(20))) {
            withFile(bytes) {
                try { UploadImageContainer.isAnimatedPNG(it); fail("Expected invalid PNG") } catch (_: IOException) { }
            }
        }
        for (bytes in listOf(byteArrayOf(), heif("heic", listOf("mif1")).copyOf(20), ByteArray(8))) {
            withFile(bytes) {
                try { UploadImageContainer.isHEIFSequence(it); fail("Expected invalid HEIF") } catch (_: IOException) { }
            }
        }
    }
}
