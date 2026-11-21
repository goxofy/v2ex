package com.vibe.v2ex.feature.topic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vibe.v2ex.data.model.Topic
import com.vibe.v2ex.data.remote.MoveFailure
import com.vibe.v2ex.data.remote.MoveNode
import com.vibe.v2ex.data.remote.MovePreparation
import com.vibe.v2ex.data.remote.MoveSubmission
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
internal fun rememberTopicMovePermission(id: Long, repository: TopicMoveRepository): Boolean {
    val revision by repository.revision.collectAsState()
    var allowed by remember(id, revision) { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(id, revision, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            allowed = false
            repository.watch(id)
            allowed = repository.allowed(id)
        }
    }
    return allowed
}

@Composable
internal fun movedTopic(topic: Topic): Topic {
    val nodes by topicMoves().movedNodes.collectAsState()
    val node = nodes[topic.id] ?: return topic
    return topic.copy(node = com.vibe.v2ex.data.model.Node(name = node.name, title = node.title))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TopicMoveSheet(
    topic: Topic, repository: TopicMoveRepository, generation: Long,
    onSubmitted: (MoveSubmission) -> Unit, onDismiss: () -> Unit,
): Unit {
    val revision by repository.revision.collectAsState()
    val custom by repository.customShortcuts.collectAsState()
    var preparation by remember { mutableStateOf<MovePreparation?>(null) }
    var selected by remember { mutableStateOf<MoveNode?>(null) }
    var query by remember { mutableStateOf("") }
    var memo by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val focus = LocalFocusManager.current
    val select: (MoveNode) -> Unit = { selected = it; query = ""; focus.clearFocus() }
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
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
            confirmValueChange = { it != androidx.compose.material3.SheetValue.Hidden || !busy }),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).imePadding()) {
            LazyColumn(Modifier.weight(1f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                        catch (_: Exception) { error = "无法打开浏览器，请手动访问 https://www.v2ex.com/t/${topic.id}" }
                    }) { Text("到网页查看") }
                    if (preparation == null && !busy && !blocked) TextButton(onClick = { reload++ }) { Text("重新读取权限与表单") }
                } }
                preparation?.let { ready ->
                    item {
                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text("常用节点")
                            TextButton(onClick = { focus.clearFocus(); editing = true }, enabled = !busy && !blocked) { Text("编辑") }
                        }
                        val shortcuts = remember(custom, ready) { repository.shortcuts(ready.form.shortcuts, ready.nodes) }
                        if (shortcuts.isEmpty()) Text("尚未设置常用节点，点击编辑添加")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            shortcuts.forEach { node ->
                                FilterChip(selected = selected?.name == node.name, onClick = { select(node) },
                                    enabled = !busy && !blocked, label = { Text(node.title) })
                            }
                        }
                    }
                    item {
                        OutlinedTextField(query, { query = it }, label = { Text("搜索节点、英文标识或别名") },
                            enabled = !busy && !blocked, singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (query.isNotBlank()) {
                            val results = ready.nodes.filter { it.matches(query) }
                            if (results.isEmpty()) Text("没有匹配的节点")
                            else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                                items(results, key = { it.name }) { node ->
                                    TextButton(onClick = { select(node) }, enabled = !busy && !blocked,
                                        modifier = Modifier.fillMaxWidth()) { Text("${node.title} / ${node.name}") }
                                }
                            }
                        }
                        selected?.let { Text("已选：${it.title} / ${it.name}", color = MaterialTheme.colorScheme.primary) }
                    }
                    item {
                        OutlinedTextField(memo, { memo = it }, label = { Text("操作备注（可选，${memo.length}/2000）") },
                            enabled = !busy && !blocked, modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5)
                    }
                }
            }
            preparation?.let { ready ->
                Surface(shadowElevation = 4.dp) {
                    Button(onClick = { focus.clearFocus(); confirm = true },
                        enabled = !busy && !blocked && selected != null && selected?.name != ready.topic.node.name && memo.length <= 2000,
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                        Text(if (busy) "正在处理…" else "移动主题")
                    }
                }
            }
        }
    }
    if (editing) preparation?.let { ready ->
        MoveShortcutsEditor(ready, repository) { editing = false }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false }, title = { Text("确认移动主题？") },
        text = { Text("${topic.title}\n${preparation?.topic?.node?.title} → ${selected?.title}") },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("取消") } },
        confirmButton = { TextButton(onClick = {
            confirm = false
            val ready = preparation
            val target = selected
            if (ready != null && target != null && !busy && !blocked) {
                busy = true
                scope.launch {
                    try {
                        val result = repository.move(topic.id, generation, ready.topic.node.name, target, memo)
                        onSubmitted(result)
                        onDismiss()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        error = (failure as? MoveFailure)?.message ?: MoveFailure.Kind.CONNECTION.message
                        blocked = true
                        repository.invalidate(topic.id)
                    } finally { busy = false }
                }
            }
        }) { Text("确认移动") } },
    )
}

@Composable
private fun MoveShortcutsEditor(ready: MovePreparation, repository: TopicMoveRepository, onDismiss: () -> Unit): Unit {
    var draft by remember { mutableStateOf(repository.shortcuts(ready.form.shortcuts, ready.nodes)) }
    var query by remember { mutableStateOf("") }
    var restoreDefault by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.9f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp).imePadding()) {
                Text("编辑常用节点", style = MaterialTheme.typography.titleLarge)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(Modifier.weight(1f)) {
                    if (draft.isEmpty()) item { Text("常用节点为空") }
                    itemsIndexed(draft, key = { _, node -> node.name }) { index, node ->
                        Column {
                            Text("${node.title} / ${node.name}")
                            Row {
                                TextButton(onClick = {
                                    draft = draft.toMutableList().apply { add(index - 1, removeAt(index)) }
                                    restoreDefault = false
                                }, enabled = index > 0) { Text("上移") }
                                TextButton(onClick = {
                                    draft = draft.toMutableList().apply { add(index + 1, removeAt(index)) }
                                    restoreDefault = false
                                }, enabled = index < draft.lastIndex) { Text("下移") }
                                TextButton(onClick = { draft = draft.filter { it.name != node.name }; restoreDefault = false }) { Text("删除") }
                            }
                        }
                    }
                    item { OutlinedTextField(query, { query = it }, label = { Text("搜索添加节点") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                    if (query.isNotBlank()) {
                        val results = ready.nodes.filter { node -> node.matches(query) && draft.none { it.name == node.name } }
                        if (results.isEmpty()) item { Text("没有可添加的匹配节点") }
                        items(results, key = { "add:${it.name}" }) { node ->
                            TextButton(onClick = { draft = draft + node; restoreDefault = false }) { Text("＋ ${node.title} / ${node.name}") }
                        }
                    }
                    item { TextButton(onClick = { draft = ready.form.shortcuts; restoreDefault = true }) { Text("恢复官网默认") } }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(onClick = {
                        try { repository.saveShortcuts(if (restoreDefault) null else draft); onDismiss() }
                        catch (_: Exception) { error = "保存失败，请重试" }
                    }) { Text("保存") }
                }
            }
        }
    }
}
