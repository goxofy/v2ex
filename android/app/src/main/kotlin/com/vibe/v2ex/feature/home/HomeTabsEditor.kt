package com.vibe.v2ex.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vibe.v2ex.data.datastore.HomeTab
import com.vibe.v2ex.data.datastore.HomeTabsStore
import com.vibe.v2ex.data.model.Node
import kotlinx.coroutines.CancellationException

@Composable
internal fun HomeTabsEditor(feeds: List<HomeFeed>, model: HomeViewModel, onDismiss: () -> Unit): Unit {
    var draft by remember { mutableStateOf(feeds.map { HomeTab(it.key, it.title) }) }
    var nodes by remember { mutableStateOf<List<Node>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        model.tabNodes().onSuccess { nodes = it.distinctBy { node -> node.name }; error = null }
            .onFailure { if (it is CancellationException) throw it; error = "节点目录暂时无法加载，已选标签不受影响" }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.9f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp).imePadding()) {
                Text("编辑首页标签（${draft.size}/15）", style = MaterialTheme.typography.titleLarge)
                Text("移除标签不会取消官网收藏，至少保留一项。", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(draft, key = { _, it -> it.key }) { index, tab ->
                        Column {
                            Text(tab.title)
                            Row {
                                TextButton(onClick = { draft = draft.toMutableList().apply { add(index - 1, removeAt(index)) } }, enabled = index > 0) { Text("上移") }
                                TextButton(onClick = { draft = draft.toMutableList().apply { add(index + 1, removeAt(index)) } }, enabled = index < draft.lastIndex) { Text("下移") }
                                TextButton(onClick = { draft = draft.filter { it.key != tab.key } }, enabled = draft.size > 1) { Text("移除") }
                            }
                        }
                    }
                    item { Text("添加标签", style = MaterialTheme.typography.titleMedium) }
                    items(HomeTabsStore.builtins.filter { tab -> draft.none { it.key == tab.key } }, key = { "add:${it.key}" }) { tab ->
                        TextButton(onClick = { if (draft.size < 15) draft = draft + tab }, enabled = draft.size < 15) { Text("＋ ${tab.title}") }
                    }
                    item { OutlinedTextField(query, { query = it }, label = { Text("搜索节点名称或英文标识") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                    if (query.isNotBlank()) {
                        val matches = nodes.filter { (it.name.contains(query, true) || it.title.contains(query, true)) && draft.none { tab -> tab.key == "node:${it.name}" } }
                        if (matches.isEmpty()) item { Text("没有可添加的匹配节点") }
                        items(matches, key = { "add:node:${it.name}" }) { node ->
                            TextButton(onClick = { if (draft.size < 15) draft = draft + HomeTab("node:${node.name}", node.title) }, enabled = draft.size < 15) { Text("＋ ${node.title} / ${node.name}") }
                        }
                    }
                    item {
                        if (draft.size >= 15) Text("最多 15 项，请先移除一项再添加")
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { reload++ }) { Text("重试加载节点") } }
                        TextButton(onClick = { draft = model.defaultTabs() }) { Text("恢复默认") }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(onClick = {
                        try { model.saveTabs(draft); onDismiss() }
                        catch (_: Exception) { error = "保存失败，请重试" }
                    }) { Text("保存") }
                }
            }
        }
    }
}
