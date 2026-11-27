package com.vibe.v2ex.feature.imageupload

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import androidx.core.graphics.scale
import android.net.Uri
import com.vibe.v2ex.data.remote.ImgurUploadException
import com.vibe.v2ex.data.remote.ImgurUploadService
import com.vibe.v2ex.data.remote.UploadImage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import javax.inject.Inject

class UploadImagePreparation @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun prepare(uri: Uri): UploadImage = withContext(Dispatchers.IO) {
        if (uri.scheme != "content") throw ImgurUploadException(ImgurUploadService.UNSUPPORTED_IMAGE)
        val file = File.createTempFile("imgur-upload-", ".image", context.cacheDir)
        var bitmap: Bitmap? = null
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(8_192)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > 40L * 1_024 * 1_024) throw ImgurUploadException(ImgurUploadService.IMAGE_TOO_LARGE)
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw ImgurUploadException(ImgurUploadService.UNSUPPORTED_IMAGE)
            currentCoroutineContext().ensureActive()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outMimeType !in setOf("image/jpeg", "image/png", "image/heic", "image/heif") ||
                bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                bounds.outWidth > 60_000 || bounds.outHeight > 60_000 ||
                bounds.outWidth.toLong() * bounds.outHeight > 120_000_000L
            ) throw ImgurUploadException(ImgurUploadService.UNSUPPORTED_IMAGE)
            if ((bounds.outMimeType == "image/png" && UploadImageContainer.isAnimatedPNG(file)) ||
                (bounds.outMimeType in setOf("image/heic", "image/heif") && UploadImageContainer.isHEIFSequence(file))
            ) throw ImgurUploadException(ImgurUploadService.UNSUPPORTED_IMAGE)
            var sample = 1
            while ((bounds.outWidth + sample - 1) / sample > 4_096 || (bounds.outHeight + sample - 1) / sample > 4_096) sample *= 2
            bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: throw ImgurUploadException(ImgurUploadService.UNSUPPORTED_IMAGE)
            val orientation = try {
                ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } catch (_: IOException) { ExifInterface.ORIENTATION_NORMAL }
            val oriented = orient(bitmap, orientation)
            if (oriented !== bitmap) { bitmap.recycle(); bitmap = oriented }
            val encoding = if (bounds.outMimeType == "image/png") UploadImage.Encoding.PNG else UploadImage.Encoding.JPEG
            while (true) {
                currentCoroutineContext().ensureActive()
                val current = bitmap ?: throw ImgurUploadException(ImgurUploadService.UNSUPPORTED_IMAGE)
                val output = LimitedImageOutput()
                val success = try {
                    current.compress(if (encoding == UploadImage.Encoding.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 86, output)
                } catch (error: IOException) {
                    if (!output.limitExceeded) throw error
                    false
                }
                if (success && !output.limitExceeded) {
                    // Encoding a fresh bitmap, without copying EXIF, removes GPS and source metadata.
                    return@withContext UploadImage(output.bytes(), encoding)
                }
                if (!output.limitExceeded) throw ImgurUploadException(ImgurUploadService.UNSUPPORTED_IMAGE)
                val width = (current.width * 3 / 4).coerceAtLeast(1)
                val height = (current.height * 3 / 4).coerceAtLeast(1)
                if (width == current.width && height == current.height) throw ImgurUploadException(ImgurUploadService.IMAGE_TOO_LARGE)
                val smaller = current.scale(width, height, true)
                if (smaller !== current) current.recycle()
                bitmap = smaller
            }
            @Suppress("UNREACHABLE_CODE")
            throw ImgurUploadException(ImgurUploadService.UNSUPPORTED_IMAGE)
        } catch (_: OutOfMemoryError) {
            throw ImgurUploadException(ImgurUploadService.IMAGE_TOO_LARGE)
        } finally {
            bitmap?.recycle()
            file.delete()
        }
    }

    private fun orient(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(270f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            else -> return source
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private class LimitedImageOutput : OutputStream() {
        private val output = ByteArrayOutputStream()
        var limitExceeded: Boolean = false
            private set

        override fun write(value: Int): Unit {
            checkCapacity(1)
            output.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int): Unit {
            checkCapacity(length)
            output.write(bytes, offset, length)
        }

        private fun checkCapacity(length: Int): Unit {
            if (length > UploadImage.MAXIMUM_BYTES - output.size()) {
                limitExceeded = true
                throw IOException("Image encoding exceeds size limit")
            }
        }

        fun bytes(): ByteArray = output.toByteArray()
    }
}
