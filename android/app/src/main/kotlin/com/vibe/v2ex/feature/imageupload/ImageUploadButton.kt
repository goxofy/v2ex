package com.vibe.v2ex.feature.imageupload

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibe.v2ex.data.remote.ImgurUploadService

@Composable
fun ImageUploadButton(
    model: ImgurUploadViewModel,
    contextKey: String,
    disabled: Boolean,
    onUploaded: (String) -> Unit,
    modifier: Modifier = Modifier,
): Unit {
    val identity by model.identity.collectAsStateWithLifecycle()
    val editorContext = "$contextKey:${identity.isWebSessionActive}:${identity.accountName}"
    var pickerError by rememberSaveable(editorContext) { mutableStateOf<String?>(null) }
    val upload by model.upload.state.collectAsStateWithLifecycle()
    val configuration by model.configuration.collectAsStateWithLifecycle()
    val currentCallback by rememberUpdatedState(onUploaded)
    val currentDisabled by rememberUpdatedState(disabled)
    var showSettings by rememberSaveable(editorContext) { mutableStateOf(false) }
    var showNotice by rememberSaveable(editorContext) { mutableStateOf(false) }
    var pickerContext by rememberSaveable(editorContext) { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val expected = pickerContext
        pickerContext = null
        if (uri != null && expected == editorContext && model.upload.context == expected && !currentDisabled) {
            model.uploadImage(editorContext, uri) { url -> currentCallback(url) }
        }
    }
    DisposableEffect(editorContext) {
        model.upload.setContext(editorContext)
        onDispose {
            pickerContext = null
            if (model.upload.context == editorContext) model.upload.setContext(null)
        }
    }
    IconButton(
        onClick = {
            if (ImgurUploadService.normalizedClientID(configuration.clientID) == null) showSettings = true
            else showNotice = true
        },
        enabled = !disabled && !upload.isUploading && configuration.loaded && pickerContext == null,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(9.dp)),
    ) {
        if (upload.isUploading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = "上传图片", tint = MaterialTheme.colorScheme.primary)
        }
    }
    if (showNotice) {
        AlertDialog(
            onDismissRequest = { showNotice = false },
            title = { Text("上传到 Imgur") },
            text = { Text("选中的图片会发送到第三方 Imgur，获得可公开访问的链接。请勿选择敏感图片。图片会按需缩小并移除原始元数据；取消操作不会删除已上传的图片。") },
            confirmButton = {
                TextButton(onClick = {
                    showNotice = false
                    if (!currentDisabled && !model.upload.state.value.isUploading && model.upload.context == editorContext) {
                        pickerContext = editorContext
                        try {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        } catch (_: Exception) {
                            pickerContext = null
                            pickerError = "无法打开系统图片选择器，请稍后重试。"
                        }
                    }
                }) { Text("选择图片并上传") }
            },
            dismissButton = { TextButton(onClick = { showNotice = false }) { Text("取消") } },
        )
    }
    if (showSettings) ImgurSettingsDialog(model, onDismiss = { showSettings = false })
    (upload.error ?: pickerError)?.let { message ->
        val dismiss: () -> Unit = { model.upload.clearError(); pickerError = null }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("图片未插入") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = dismiss) { Text("好") } },
        )
    }
}

@Composable
fun ImgurSettingsDialog(model: ImgurUploadViewModel, onDismiss: () -> Unit): Unit {
    val state by model.configuration.collectAsStateWithLifecycle()
    var clientID by rememberSaveable(state.clientID) { mutableStateOf(state.clientID) }
    var linkError by rememberSaveable { mutableStateOf<String?>(null) }
    val uriHandler = LocalUriHandler.current
    val valid = ImgurUploadService.normalizedClientID(clientID) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("图片上传 · Imgur") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = clientID,
                    onValueChange = { clientID = it },
                    label = { Text("Client ID") },
                    singleLine = true,
                    enabled = state.loaded && !state.saving,
                    isError = clientID.isNotEmpty() && !valid,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                )
                Spacer(Modifier.height(12.dp))
                Text("在 Imgur 注册应用后填写 Client ID，不要填写 Client Secret。配置保存在本机，仅用于上传你主动选择的图片。", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text("图片获得可公开访问的原图链接，请勿上传敏感内容。取消请求、删除草稿或清除此配置不会删除已经上传的图片。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = {
                    runCatching { uriHandler.openUri("https://api.imgur.com/oauth2/addclient") }
                        .onFailure { linkError = "无法打开浏览器，请访问 api.imgur.com 注册应用。" }
                }) { Text("注册 Imgur 应用") }
                TextButton(onClick = {
                    runCatching { uriHandler.openUri("https://imgur.com/privacy") }
                        .onFailure { linkError = "无法打开浏览器，请访问 imgur.com/privacy。" }
                }) { Text("Imgur 隐私政策") }
                if (clientID.isNotEmpty() && !valid) Text("Client ID 只能包含英文字母和数字。", color = MaterialTheme.colorScheme.error)
                (state.error ?: linkError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { model.saveClientID("", onDismiss) }, enabled = state.loaded && !state.saving && state.clientID.isNotEmpty()) {
                    Text("清除配置")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { model.saveClientID(clientID, onDismiss) }, enabled = state.loaded && !state.saving && valid) {
                Text(if (state.saving) "保存中…" else "保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
