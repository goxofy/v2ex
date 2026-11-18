package com.vibe.v2ex.data.repository

import androidx.core.content.edit
import com.vibe.v2ex.data.datastore.SecureStore
import com.vibe.v2ex.data.remote.MoveFailure
import com.vibe.v2ex.data.remote.MoveNode
import com.vibe.v2ex.data.remote.MovePreparation
import com.vibe.v2ex.data.remote.TopicMoveService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import okhttp3.OkHttpClient

@Singleton
internal class TopicMoveRepository internal constructor(
    val revision: StateFlow<Long>,
    private val cookie: () -> String?,
    private val service: TopicMoveService,
    loadNodes: () -> String?,
    private val persistNodes: (String) -> Unit,
    scope: CoroutineScope,
    private val now: () -> Long = System::nanoTime,
) {
    @Inject constructor(
        credentials: SecureStore,
        client: OkHttpClient,
        @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
    ) : this(
        revision = credentials.accountCredentialsRevision,
        cookie = { if (credentials.isWebSessionActive) credentials.sessionCookieHeader else null },
        service = TopicMoveService(client),
        loadNodes = { context.getSharedPreferences("topic_move_nodes", android.content.Context.MODE_PRIVATE).getString("confirmed", "{}") },
        persistNodes = { value -> context.getSharedPreferences("topic_move_nodes", android.content.Context.MODE_PRIVATE).edit { putString("confirmed", value) } },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )
    data class Permission(val allowed: Boolean, val revision: Long, val expires: Long)
    private val limit = Semaphore(2)
    private val locks = mutableMapOf<Long, Mutex>()
    private val watchers = mutableMapOf<Long, Int>()
    private val retryAfter = mutableMapOf<Long, Long>()
    private var nodeRevision = 0L
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val _permissions = MutableStateFlow<Map<Long, Permission>>(emptyMap())
    val permissions: StateFlow<Map<Long, Permission>> = _permissions.asStateFlow()
    private val _movedNodes = MutableStateFlow<Map<Long, MoveNode>>(
        runCatching { json.decodeFromString<Map<Long, MoveNode>>(loadNodes().orEmpty()) }.getOrDefault(emptyMap()),
    )
    val movedNodes: StateFlow<Map<Long, MoveNode>> = _movedNodes.asStateFlow()

    init {
        scope.launch { revision.collect { _permissions.value = emptyMap(); retryAfter.clear() } }
    }

    fun allowed(id: Long): Boolean {
        val entry = _permissions.value[id] ?: return false
        return !cookie().isNullOrBlank() && entry.revision == revision.value && entry.allowed && entry.expires > now()
    }

    suspend fun watch(id: Long): Unit {
        if (cookie().isNullOrBlank()) return
        val generation = revision.value
        val lock = locks.getOrPut(id) { Mutex() }
        watchers[id] = (watchers[id] ?: 0) + 1
        try {
            while (revision.value == generation) {
                lock.withLock {
                    val entry = _permissions.value[id]
                    if (entry == null || entry.expires <= now() || entry.revision != generation) {
                        _permissions.update { it - id }
                        if ((retryAfter[id] ?: 0) > now()) return@withLock
                        val before = nodeRevision
                        try {
                            val result = limit.withPermit {
                                if (generation != revision.value) throw MoveFailure(MoveFailure.Kind.SESSION)
                                service.permission(id, cookie().orEmpty())
                            }
                            if (generation == revision.value && before == nodeRevision) {
                                if (_movedNodes.value[id] != null && _movedNodes.value[id] != result.node) {
                                    _movedNodes.update { it + (id to result.node) }
                                    saveNodes()
                                }
                                _permissions.update { old ->
                                    val trimmed = if (old.size >= 200) old - old.minBy { it.value.expires }.key else old
                                    trimmed + (id to Permission(result.canMove, generation, now() + 60_000_000_000L))
                                }
                            }
                        } catch (error: CancellationException) { throw error }
                        catch (_: Exception) {
                            if (generation == revision.value) retryAfter[id] = now() + 60_000_000_000L
                        }
                    }
                }
                delay(5000)
            }
        } finally {
            watchers[id] = (watchers[id] ?: 1) - 1
            if (watchers[id] == 0) {
                watchers.remove(id)
                locks.remove(id, lock)
                retryAfter.remove(id)
            }
        }
    }

    suspend fun refreshMovedNode(id: Long): Unit {
        if (_movedNodes.value[id] == null || cookie().isNullOrBlank()) return
        val generation = revision.value
        val before = nodeRevision
        try {
            val result = limit.withPermit { service.permission(id, cookie().orEmpty()) }
            if (generation == revision.value && before == nodeRevision) {
                _movedNodes.update { it + (id to result.node) }
                saveNodes()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Keep the last confirmed metadata while offline. */ }
    }

    suspend fun prepare(id: Long, generation: Long): MovePreparation {
        ensureCurrent(generation)
        val result = service.prepare(id, cookie().orEmpty())
        ensureCurrent(generation)
        return result
    }

    suspend fun move(id: Long, generation: Long, current: String, destination: MoveNode, memo: String): MoveNode {
        ensureCurrent(generation)
        val node = service.move(id, cookie().orEmpty(), current, destination, memo) {
            generation == revision.value && !cookie().isNullOrBlank()
        }
        ensureCurrent(generation)
        _movedNodes.update { old ->
            val trimmed = if (old.size >= 200 && !old.containsKey(id)) old - old.keys.min() else old
            trimmed + (id to node)
        }
        nodeRevision++
        saveNodes()
        invalidate(id)
        return node
    }

    fun invalidate(id: Long): Unit {
        nodeRevision++
        _permissions.update { it - id }
        retryAfter.remove(id)
    }

    private fun saveNodes(): Unit {
        runCatching {
            persistNodes(json.encodeToString(
                kotlinx.serialization.serializer<Map<Long, MoveNode>>(),
                _movedNodes.value,
            ))
        }
    }

    private fun ensureCurrent(generation: Long): Unit {
        if (revision.value != generation || cookie().isNullOrBlank()) throw MoveFailure(MoveFailure.Kind.SESSION)
    }
}
