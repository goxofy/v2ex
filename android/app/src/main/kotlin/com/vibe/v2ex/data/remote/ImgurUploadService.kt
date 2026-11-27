package com.vibe.v2ex.data.remote

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import kotlin.coroutines.resumeWithException

class ImgurUploadException(message: String) : IOException(message)

data class UploadImage(val bytes: ByteArray, val encoding: Encoding) {
    enum class Encoding(val mime: String, val extension: String) {
        JPEG("image/jpeg", "jpeg"), PNG("image/png", "png"),
    }
    companion object { const val MAXIMUM_BYTES: Int = 10 * 1_024 * 1_024 }
}

interface ImgurUploading {
    suspend fun upload(image: UploadImage, clientID: String): String
}

class ImgurUploadService internal constructor(
    baseClient: OkHttpClient,
    private val json: Json,
    private val endpoint: HttpUrl,
) : ImgurUploading {
    @Inject constructor(@Named("image") baseClient: OkHttpClient, json: Json) :
        this(baseClient, json, "https://api.imgur.com/3/image".toHttpUrl())

    private val client = baseClient.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .cache(null)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .also { it.interceptors().clear(); it.networkInterceptors().clear() }
        .build()

    @Serializable private data class Envelope(val success: Boolean, val data: ImageData? = null)
    @Serializable private data class ImageData(val link: String? = null)

    override suspend fun upload(image: UploadImage, clientID: String): String {
        val id = normalizedClientID(clientID) ?: throw ImgurUploadException(INVALID_CLIENT_ID)
        if (image.bytes.isEmpty()) throw ImgurUploadException(UNSUPPORTED_IMAGE)
        if (image.bytes.size > UploadImage.MAXIMUM_BYTES) throw ImgurUploadException(IMAGE_TOO_LARGE)
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("image", "image.${image.encoding.extension}", image.bytes.toRequestBody(image.encoding.mime.toMediaType()))
            .build()
        val request = Request.Builder().url(endpoint)
            .header("Authorization", "Client-ID $id")
            .header("Accept", "application/json")
            .post(SingleUploadBody(body)).build()
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException): Unit {
                    if (continuation.isActive) {
                        val message = if (error is SocketTimeoutException) {
                            "图片上传超时。图片可能已上传，但正文未修改，请勿连续重试。"
                        } else {
                            "无法确认图片上传结果，请检查网络。图片可能已上传，但正文未修改。"
                        }
                        continuation.resumeWithException(ImgurUploadException(message))
                    }
                }

                override fun onResponse(call: Call, response: Response): Unit {
                    response.use {
                        if (!continuation.isActive) return
                        try {
                            val url = decode(response)
                            if (continuation.isActive) continuation.resumeWith(Result.success(url))
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(
                                error as? ImgurUploadException ?: ImgurUploadException(INVALID_RESPONSE),
                            )
                        }
                    }
                }
            })
        }
    }

    private fun decode(response: Response): String {
        when (response.code) {
            in 200..299 -> Unit
            401, 403 -> throw ImgurUploadException(INVALID_CLIENT_ID)
            413 -> throw ImgurUploadException(IMAGE_TOO_LARGE)
            429 -> throw ImgurUploadException("Imgur 请求次数已达上限，请稍后再试。")
            else -> throw ImgurUploadException("Imgur 上传未成功（HTTP ${response.code}），正文未修改。")
        }
        val responseBody = response.body ?: throw ImgurUploadException(INVALID_RESPONSE)
        val output = ByteArrayOutputStream()
        responseBody.byteStream().use { input ->
            val buffer = ByteArray(8_192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > 1_024 * 1_024) throw ImgurUploadException(INVALID_RESPONSE)
                output.write(buffer, 0, count)
            }
        }
        val envelope = json.decodeFromString<Envelope>(output.toString(Charsets.UTF_8.name()))
        if (!envelope.success) throw ImgurUploadException(INVALID_RESPONSE)
        return envelope.data?.link?.let(ImgurImageLink::parse) ?: throw ImgurUploadException(INVALID_RESPONSE)
    }

    /** Also prevents HTTP follow-ups such as 503 + Retry-After: 0 replaying a POST. */
    private class SingleUploadBody(private val delegate: RequestBody) : RequestBody() {
        override fun contentType(): MediaType? = delegate.contentType()
        override fun contentLength(): Long = delegate.contentLength()
        override fun isOneShot(): Boolean = true
        override fun writeTo(sink: BufferedSink): Unit = delegate.writeTo(sink)
    }

    companion object {
        const val INVALID_CLIENT_ID: String = "请在图片上传设置中填写有效的 Imgur Client ID。"
        const val IMAGE_TOO_LARGE: String = "图片过大，请选择较小的图片（上传文件最大 10 MiB）。"
        const val UNSUPPORTED_IMAGE: String = "无法处理这张图片，请选择静态 JPEG、PNG 或 HEIC 图片。"
        const val INVALID_RESPONSE: String = "Imgur 未返回有效的原图直链，正文未修改。"

        fun normalizedClientID(raw: String): String? = raw.trim().takeIf { value ->
            value.isNotEmpty() && value.length <= 128 && value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
        }
    }
}
