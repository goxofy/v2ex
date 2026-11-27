package com.vibe.v2ex.feature.imageupload

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.vibe.v2ex.data.remote.ImgurUploading
import com.vibe.v2ex.data.remote.UploadImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class ImageUploadCoordinatorTest {
    private val image = UploadImage(byteArrayOf(1, 2, 3), UploadImage.Encoding.JPEG)
    private val url = "https://i.imgur.com/fixture.jpeg"

    private class DeferredClient : ImgurUploading {
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<String>()
        var calls: Int = 0
        override suspend fun upload(image: UploadImage, clientID: String): String {
            calls += 1
            started.complete(Unit)
            // Deliberately emulate a transport returning success even after cancellation.
            return withContext(NonCancellable) { response.await() }
        }
    }

    @Test fun completionUsesLatestTextAndSelection(): Unit = runBlocking {
        val client = DeferredClient()
        val upload = ImageUploadCoordinator(this, client)
        upload.setContext("reply:1")
        var editor = TextFieldValue("旧正文")
        upload.start("reply:1", "abc", { image }) { editor = ImageTextInsertion.insert(it, editor) }
        client.started.await()
        val job = checkNotNull(upload.job)
        editor = TextFieldValue("新增内容", TextRange(2))
        client.response.complete(url)
        job.join()
        assertEquals("新增\n$url\n内容", editor.text)
        assertFalse(upload.state.value.isUploading)
        assertNull(upload.state.value.error)
    }

    @Test fun leavingAndReturningRejectsOldSuccess(): Unit = runBlocking {
        val client = DeferredClient()
        val upload = ImageUploadCoordinator(this, client)
        var completions = 0
        upload.setContext("A")
        upload.start("A", "abc", { image }) { completions += 1 }
        client.started.await()
        val oldJob = checkNotNull(upload.job)
        upload.setContext("B")
        upload.setContext("A")
        client.response.complete(url)
        oldJob.join()
        assertEquals(0, completions)
        assertFalse(upload.state.value.isUploading)
        assertNull(upload.state.value.error)
    }

    @Test fun duplicateClicksAndWrongContextsDoNotUpload(): Unit = runBlocking {
        val client = DeferredClient()
        val upload = ImageUploadCoordinator(this, client)
        var preparations = 0
        upload.setContext("A")
        upload.start("B", "abc", { preparations += 1; image }) { fail("Wrong context") }
        upload.start("A", "abc", { preparations += 1; image }) { }
        client.started.await()
        val job = checkNotNull(upload.job)
        upload.start("A", "abc", { preparations += 1; image }) { fail("Duplicate completion") }
        assertEquals(1, preparations)
        assertEquals(1, client.calls)
        client.response.complete(url)
        job.join()
    }

    @Test fun preparationFailurePreservesTextAndCanBeRetried(): Unit = runBlocking {
        val client = DeferredClient()
        val upload = ImageUploadCoordinator(this, client)
        upload.setContext("A")
        upload.start("A", "abc", { error("private file path") }) { fail("Unexpected insertion") }
        checkNotNull(upload.job).join()
        assertEquals(0, client.calls)
        assertFalse(upload.state.value.isUploading)
        assertNotNull(upload.state.value.error)
        assertFalse(upload.state.value.error.orEmpty().contains("private file path"))
        upload.clearError()
        assertNull(upload.state.value.error)
        upload.start("A", "abc", { image }) { }
        client.started.await()
        val job = checkNotNull(upload.job)
        client.response.complete(url)
        job.join()
        assertEquals(1, client.calls)
    }

    @Test fun cancellationWhilePreparingNeverStartsUpload(): Unit = runBlocking {
        val client = DeferredClient()
        val upload = ImageUploadCoordinator(this, client)
        val preparation = CompletableDeferred<UploadImage>()
        upload.setContext("A")
        upload.start("A", "abc", { preparation.await() }) { fail("Unexpected insertion") }
        val job = checkNotNull(upload.job)
        yield()
        upload.cancel()
        preparation.complete(image)
        job.join()
        assertEquals(0, client.calls)
        assertFalse(upload.state.value.isUploading)
    }
}
