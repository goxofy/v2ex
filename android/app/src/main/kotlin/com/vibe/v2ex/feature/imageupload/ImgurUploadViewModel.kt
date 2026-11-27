package com.vibe.v2ex.feature.imageupload

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibe.v2ex.data.datastore.SettingsDataStore
import com.vibe.v2ex.data.moderation.ModerationStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import com.vibe.v2ex.data.remote.ImgurUploadService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ImgurConfigurationState(
    val clientID: String = "",
    val loaded: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ImgurUploadViewModel @Inject constructor(
    private val settings: SettingsDataStore,
    client: ImgurUploadService,
    private val preparation: UploadImagePreparation,
    moderationStore: ModerationStore,
) : ViewModel() {
    val upload = ImageUploadCoordinator(viewModelScope, client)
    private val mutableConfiguration = MutableStateFlow(ImgurConfigurationState())
    val configuration: StateFlow<ImgurConfigurationState> = mutableConfiguration.asStateFlow()

    val identity = moderationStore.websiteState

    init {
        viewModelScope.launch {
            identity.map { it.isWebSessionActive to it.accountName }.distinctUntilChanged().collect {
                upload.cancel()
            }
        }
        viewModelScope.launch {
            settings.imgurClientID.catch {
                mutableConfiguration.update { state -> state.copy(error = "无法读取图片上传设置，请重新保存配置。") }
                emit("")
            }.collect { value ->
                mutableConfiguration.update { it.copy(clientID = value, loaded = true) }
            }
        }
    }

    fun saveClientID(raw: String, onSaved: () -> Unit): Unit {
        if (mutableConfiguration.value.saving) return
        val value = if (raw.isBlank()) "" else ImgurUploadService.normalizedClientID(raw)
        if (value == null) {
            mutableConfiguration.update { it.copy(error = "Client ID 只能包含英文字母和数字。") }
            return
        }
        mutableConfiguration.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                settings.setImgurClientID(value)
                mutableConfiguration.update { it.copy(clientID = value, saving = false, loaded = true) }
                onSaved()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutableConfiguration.update { it.copy(saving = false, error = "无法保存配置，请重试。") }
            }
        }
    }

    fun uploadImage(context: String, uri: Uri, onUploaded: (String) -> Unit): Unit {
        if (!mutableConfiguration.value.loaded) return
        val account = identity.value.let { it.isWebSessionActive to it.accountName }
        upload.start(context, mutableConfiguration.value.clientID, { preparation.prepare(uri) }) { url ->
            if (identity.value.let { it.isWebSessionActive to it.accountName } == account) onUploaded(url)
        }
    }

    override fun onCleared(): Unit {
        upload.cancel()
    }
}
