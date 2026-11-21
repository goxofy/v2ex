package com.vibe.v2ex.data.datastore

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class HomeTab(val key: String, val title: String)

@Singleton
class HomeTabsStore internal constructor(load: () -> String?, private val persist: (String) -> Unit) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        { context.getSharedPreferences("home_tabs", Context.MODE_PRIVATE).getString("v1", null) },
        { value -> context.getSharedPreferences("home_tabs", Context.MODE_PRIVATE).edit { putString("v1", value) } },
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val _items = MutableStateFlow(runCatching { load()?.let { normalize(json.decodeFromString<List<HomeTab>>(it)) } }.getOrNull())
    val items = _items.asStateFlow()

    fun initialize(nodes: List<HomeTab>): Unit {
        if (_items.value != null) return
        val initial = defaults(nodes)
        // Storage failures must not prevent opening the home screen.
        runCatching { save(initial) }.onFailure { _items.value = initial }
    }

    fun save(values: List<HomeTab>): Unit {
        val clean = normalize(values)
        persist(json.encodeToString(kotlinx.serialization.serializer<List<HomeTab>>(), clean))
        _items.value = clean
    }

    companion object {
        const val LIMIT = 15
        val builtins: List<HomeTab> = listOf(HomeTab("all", "全部"), HomeTab("hot", "最热"), HomeTab("r2", "R2"), HomeTab("following", "关注"))
        fun defaults(nodes: List<HomeTab>): List<HomeTab> = normalize(builtins + nodes.take(8))
        fun normalize(values: List<HomeTab>): List<HomeTab> = values.mapNotNull { tab ->
            builtins.firstOrNull { it.key == tab.key } ?: tab.takeIf {
                Regex("node:[A-Za-z0-9_-]+").matches(it.key) && it.title.isNotBlank()
            }
        }.distinctBy { it.key }.take(LIMIT).ifEmpty { listOf(builtins.first()) }
    }
}
