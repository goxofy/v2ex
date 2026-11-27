package com.vibe.v2ex.feature.imageupload

import com.vibe.v2ex.data.remote.ImgurUploadException
import com.vibe.v2ex.data.remote.ImgurUploading
import com.vibe.v2ex.data.remote.UploadImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class ImageUploadState(val isUploading: Boolean = false, val error: String? = null)

/** Owned by one editor; methods are called on the UI thread. */
class ImageUploadCoordinator(private val scope: CoroutineScope, private val client: ImgurUploading) {
    private val mutableState = MutableStateFlow(ImageUploadState())
    val state: StateFlow<ImageUploadState> = mutableState.asStateFlow()
    var context: String? = null
        private set
    var job: Job? = null
        private set
    private var generation = UUID.randomUUID()

    fun setContext(value: String?): Unit {
        if (context == value) return
        cancel()
        context = value
    }

    fun start(
        expectedContext: String,
        clientID: String,
        prepare: suspend () -> UploadImage,
        onUploaded: (String) -> Unit,
    ): Unit {
        if (context != expectedContext || mutableState.value.isUploading) return
        val token = UUID.randomUUID()
        generation = token
        mutableState.value = ImageUploadState(isUploading = true)
        job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val image = prepare()
                currentCoroutineContext().ensureActive()
                val url = client.upload(image, clientID)
                currentCoroutineContext().ensureActive()
                if (generation == token && context == expectedContext) {
                    mutableState.value = ImageUploadState()
                    job = null
                    onUploaded(url)
                }
            } catch (error: CancellationException) {
                if (generation == token) { mutableState.value = ImageUploadState(); job = null }
                throw error
            } catch (error: Exception) {
                if (generation == token && context == expectedContext) {
                    mutableState.value = ImageUploadState(error = (error as? ImgurUploadException)?.message
                        ?: "无法读取或上传图片，请重新选择。正文未修改。")
                    job = null
                }
            }
        }
        job?.start()
    }

    fun clearError(): Unit { mutableState.value = mutableState.value.copy(error = null) }

    fun cancel(): Unit {
        generation = UUID.randomUUID()
        job?.cancel()
        job = null
        mutableState.value = ImageUploadState()
    }
}
