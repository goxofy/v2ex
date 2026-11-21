package com.vibe.v2ex.data.repository

import androidx.core.content.edit
import com.vibe.v2ex.data.datastore.SecureStore
import com.vibe.v2ex.data.remote.MoveFailure
import com.vibe.v2ex.data.remote.MoveNode
import com.vibe.v2ex.data.remote.MovePreparation
import com.vibe.v2ex.data.remote.MoveSubmission
import com.vibe.v2ex.data.remote.TopicMoveService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

@Singleton
internal class TopicMoveRepository internal constructor(
    val revision: StateFlow<Long>,
    private val cookie: () -> String?,
    private val service: TopicMoveService,
    loadNodes: () -> String?,
    private val persistNodes: (String) -> Unit,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    loadShortcuts: () -> String? = { null },
    private val persistShortcuts: (String?) -> Unit = {},
) {
    @Inject constructor(
        credentials: SecureStore,
        client: OkHttpClient,
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
    ) : this(
        revision = credentials.accountCredentialsRevision,
        cookie = { if (credentials.isWebSessionActive) credentials.sessionCookieHeader else null },
        service = TopicMoveService(client),
        loadNodes = { context.getSharedPreferences("topic_move_nodes", android.content.Context.MODE_PRIVATE).getString("confirmed_v2", "{}") },
        persistNodes = { value -> context.getSharedPreferences("topic_move_nodes", android.content.Context.MODE_PRIVATE).edit { putString("confirmed_v2", value) } },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        loadShortcuts = { context.getSharedPreferences("topic_move_nodes", android.content.Context.MODE_PRIVATE).getString("shortcuts.https://www.v2ex.com", null) },
        persistShortcuts = { value -> context.getSharedPreferences("topic_move_nodes", android.content.Context.MODE_PRIVATE).edit { putString("shortcuts.https://www.v2ex.com", value) } },
    )
    data class Permission(val allowed: Boolean, val revision: Long, val expires: Long)
    @Serializable private data class CachedNode(val node: MoveNode, val expires: Long)
    private val limit = Semaphore(2)
    private val locks = mutableMapOf<Long, Mutex>()
    private val watchers = mutableMapOf<Long, Int>()
    private var nodeRevision = 0L
    private val json = Json { ignoreUnknownKeys = true }
    private val _permissions = MutableStateFlow<Map<Long, Permission>>(emptyMap())
    val permissions: StateFlow<Map<Long, Permission>> = _permissions.asStateFlow()
    private val cached = runCatching { json.decodeFromString<Map<Long, CachedNode>>(loadNodes().orEmpty()) }
        .getOrDefault(emptyMap()).filter { (id, value) ->
            id > 0 && value.expires > now() && value.expires <= now() + 300_000L && validNode(value.node)
        }.entries.sortedByDescending { it.value.expires }.take(200).associate { it.toPair() }.toMutableMap()
    private val optimistic = mutableSetOf<Long>()
    private val expiryJobs = mutableMapOf<Long, Job>()
    private val _movedNodes = MutableStateFlow(cached.mapValues { it.value.node })
    val movedNodes: StateFlow<Map<Long, MoveNode>> = _movedNodes.asStateFlow()
    private val _customShortcuts = MutableStateFlow(runCatching {
        loadShortcuts()?.let { cleanNodes(json.decodeFromString<List<MoveNode>>(it)) }
    }.getOrNull())
    val customShortcuts: StateFlow<List<MoveNode>?> = _customShortcuts.asStateFlow()

    init {
        scope.launch { revision.collect { _permissions.value = emptyMap() } }
        cached.keys.toList().forEach { scheduleExpiry(it) }
    }

    fun shortcuts(defaults: List<MoveNode>, directory: List<MoveNode>): List<MoveNode> {
        val current = cleanNodes(directory).associateBy { it.name }
        return cleanNodes(_customShortcuts.value ?: defaults).map { current[it.name] ?: it }
    }

    fun saveShortcuts(nodes: List<MoveNode>?): Unit {
        val value = nodes?.let(::cleanNodes)
        persistShortcuts(value?.let { json.encodeToString(kotlinx.serialization.serializer<List<MoveNode>>(), it) })
        _customShortcuts.value = value
    }

    fun allowed(id: Long): Boolean {
        val entry = _permissions.value[id] ?: return false
        return !cookie().isNullOrBlank() && entry.revision == revision.value && entry.allowed && entry.expires > now()
    }

    /** One probe per detail entry, sharing an in-flight request or a still-valid result. */
    suspend fun watch(id: Long): Unit {
        if (id <= 0 || cookie().isNullOrBlank()) return
        val generation = revision.value
        val lock = locks.getOrPut(id) { Mutex() }
        watchers[id] = (watchers[id] ?: 0) + 1
        try {
            lock.withLock {
                if (allowed(id)) return@withLock
                val before = nodeRevision
                try {
                    val result = limit.withPermit {
                        ensureCurrent(generation)
                        service.permission(id, cookie().orEmpty())
                    }
                    if (generation == revision.value && before == nodeRevision) {
                        if (_movedNodes.value[id] != null) storeNode(id, result.node, pending = false)
                        _permissions.update { old ->
                            val trimmed = if (old.size >= 200) old - old.minBy { it.value.expires }.key else old
                            trimmed + (id to Permission(result.canMove, generation, now() + 60_000L))
                        }
                    }
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { /* Unknown permissions never reveal an entry. */ }
            }
        } finally {
            watchers[id] = (watchers[id] ?: 1) - 1
            if (watchers[id] == 0) { watchers.remove(id); locks.remove(id, lock) }
        }
    }

    suspend fun refreshMovedNode(id: Long): Unit {
        if (_movedNodes.value[id] == null || cookie().isNullOrBlank()) return
        val generation = revision.value
        val before = nodeRevision
        try {
            val node = service.currentNode(id, cookie().orEmpty())
            if (generation == revision.value && before == nodeRevision) storeNode(id, node, pending = false)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Submission feedback never waits for metadata refresh. */ }
    }

    suspend fun prepare(id: Long, generation: Long): MovePreparation {
        ensureCurrent(generation)
        val result = service.prepare(id, cookie().orEmpty())
        ensureCurrent(generation)
        return result
    }

    suspend fun move(id: Long, generation: Long, current: String, destination: MoveNode, memo: String): MoveSubmission {
        ensureCurrent(generation)
        val result = service.move(id, cookie().orEmpty(), current, destination, memo) {
            generation == revision.value && !cookie().isNullOrBlank()
        }
        ensureCurrent(generation)
        storeNode(id, result.node, pending = result is MoveSubmission.Submitted)
        invalidate(id)
        return result
    }

    fun invalidate(id: Long): Unit {
        nodeRevision++
        _permissions.update { it - id }
    }

    fun expireNodes(): Unit {
        val expired = cached.filterValues { it.expires <= now() }.keys.toList()
        for (id in expired) { cached.remove(id); optimistic.remove(id); expiryJobs.remove(id)?.cancel() }
        _movedNodes.value = cached.mapValues { it.value.node }
        saveNodes()
    }

    private fun storeNode(id: Long, node: MoveNode, pending: Boolean): Unit {
        if (cached.size >= 200 && !cached.containsKey(id)) {
            val oldest = cached.minBy { it.value.expires }.key
            cached.remove(oldest); optimistic.remove(oldest); expiryJobs.remove(oldest)?.cancel()
        }
        cached[id] = CachedNode(node, now() + 300_000L)
        if (pending) optimistic.add(id) else optimistic.remove(id)
        _movedNodes.value = cached.mapValues { it.value.node }
        saveNodes()
        scheduleExpiry(id)
    }

    private fun scheduleExpiry(id: Long): Unit {
        expiryJobs[id]?.cancel()
        val wait = ((cached[id]?.expires ?: now()) - now()).coerceIn(0, 300_000L)
        expiryJobs[id] = scope.launch { delay(wait); expireNodes() }
    }

    private fun saveNodes(): Unit {
        runCatching {
            persistNodes(json.encodeToString(kotlinx.serialization.serializer<Map<Long, CachedNode>>(), cached.filterKeys { it !in optimistic }))
        }
    }

    private fun ensureCurrent(generation: Long): Unit {
        if (revision.value != generation || cookie().isNullOrBlank()) throw MoveFailure(MoveFailure.Kind.SESSION)
    }

    private companion object {
        fun validNode(node: MoveNode): Boolean = Regex("[A-Za-z0-9_]+").matches(node.name) && node.title.isNotBlank()
        fun cleanNodes(nodes: List<MoveNode>): List<MoveNode> = nodes.filter(::validNode).distinctBy { it.name }
    }
}
