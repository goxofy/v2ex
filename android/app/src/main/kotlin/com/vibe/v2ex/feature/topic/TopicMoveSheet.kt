package com.vibe.v2ex.feature.topic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.vibe.v2ex.data.model.Topic
import com.vibe.v2ex.data.remote.MoveFailure
import com.vibe.v2ex.data.remote.MoveNode
import com.vibe.v2ex.data.remote.MovePreparation
import com.vibe.v2ex.data.repository.TopicMoveRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface TopicMoveEntryPoint {
    fun moves(): TopicMoveRepository
}

@Composable
internal fun topicMoves(): TopicMoveRepository {
    val context = LocalContext.current.applicationContext
    return remember(context) { EntryPointAccessors.fromApplication(context, TopicMoveEntryPoint::class.java).moves() }
}

@Composable
internal fun WatchTopicMovePermission(id: Long, repository: TopicMoveRepository): Unit {
    val revision by repository.revision.collectAsState()
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(id, revision, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { repository.watch(id) }
    }
}

@Composable
internal fun movedTopic(topic: Topic): Topic {
    val nodes by topicMoves().movedNodes.collectAsState()
    val node = nodes[topic.id] ?: return topic
    return topic.copy(node = com.vibe.v2ex.data.model.Node(name = node.name, title = node.title))
}

/** A sibling of the navigation click target, never a nested clickable in its label. */
@Composable
internal fun TopicMoveAction(topic: Topic, modifier: Modifier = Modifier): Unit {
    val repository = topicMoves()
    val revision by repository.revision.collectAsState()
    val permissions by repository.permissions.collectAsState()
    var presented by remember(topic.id, revision) { mutableStateOf(false) }
    WatchTopicMovePermission(topic.id, repository)
    if (permissions.containsKey(topic.id) && repository.allowed(topic.id)) {
        Row(modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { presented = true }) { Text("移动") }
        }
    }
    if (presented) TopicMoveSheet(topic, repository, revision) { presented = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TopicMoveSheet(topic: Topic, repository: TopicMoveRepository, generation: Long, onDismiss: () -> Unit): Unit {
    val revision by repository.revision.collectAsState()
    var preparation by remember { mutableStateOf<MovePreparation?>(null) }
    var selected by remember { mutableStateOf<MoveNode?>(null) }
    var query by remember { mutableStateOf("") }
    var memo by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var reload by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(revision) { if (revision != generation) onDismiss() }
    LaunchedEffect(reload) {
        busy = true
        error = null
        try { preparation = repository.prepare(topic.id, generation) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            error = (failure as? MoveFailure)?.message ?: MoveFailure.Kind.UNAVAILABLE.message
            repository.invalidate(topic.id)
        } finally { busy = false }
    }
    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != androidx.compose.material3.SheetValue.Hidden || !busy }),
    ) {
        LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("移动主题 #${topic.id}", style = MaterialTheme.typography.titleLarge)
                Text(topic.title)
                preparation?.let { Text("当前节点：${it.topic.node.title}") }
            }
            if (busy) item { CircularProgressIndicator() }
            error?.let { message -> item {
                Text(message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = {
                    try { uriHandler.openUri("https://www.v2ex.com/t/${topic.id}") }
                    catch (_: Exception) { error = "无法打开浏览器，请手动访问 https://www.v2ex.com/t/${topic.id} 核查" }
                }) { Text("到网页核查") }
                if (preparation == null && !busy && !blocked) TextButton(onClick = { reload++ }) { Text("重新读取权限与表单") }
            } }
            preparation?.let { ready ->
                item {
                    Text("常用节点")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(ready.form.shortcuts, key = { it.name }) { node ->
                            FilterChip(selected = selected?.name == node.name, onClick = { selected = node },
                                enabled = !busy && !blocked, label = { Text(node.title) })
                        }
                    }
                }
                item {
                    OutlinedTextField(query, { query = it }, label = { Text("搜索节点、英文标识或别名") }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth())
                    selected?.let { Text("已选：${it.title} / ${it.name}", color = MaterialTheme.colorScheme.primary) }
                    OutlinedTextField(memo, { memo = it }, label = { Text("操作备注（可选，${memo.length}/2000）") },
                        enabled = !busy && !blocked, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5)
                    Button(onClick = { confirm = true }, enabled = !busy && !blocked && selected != null &&
                        selected?.name != ready.topic.node.name && memo.length <= 2000) { Text("移动主题") }
                }
                items(ready.nodes.filter { query.isBlank() || it.matches(query) }.take(60), key = { it.name }) { node ->
                    TextButton(onClick = { selected = node }, enabled = !busy && !blocked) { Text("${node.title} / ${node.name}") }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false }, title = { Text("确认移动主题？") },
        text = { Text("${preparation?.topic?.node?.title} → ${selected?.title}\n${topic.title}") },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
        confirmButton = { TextButton(onClick = {
            confirm = false
            val ready = preparation
            val target = selected
            if (ready != null && target != null && !busy && !blocked) {
                busy = true
                scope.launch {
                    try {
                        repository.move(topic.id, generation, ready.topic.node.name, target, memo)
                        onDismiss()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        error = (failure as? MoveFailure)?.message ?: MoveFailure.Kind.UNCONFIRMED.message
                        blocked = true
                        repository.invalidate(topic.id)
                    } finally { busy = false }
                }
            }
        }) { Text("确认移动") } },
    )
}
