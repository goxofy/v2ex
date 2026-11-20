package com.vibe.v2ex.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vibe.v2ex.data.datastore.HomeTab
import com.vibe.v2ex.data.datastore.HomeTabsStore
import com.vibe.v2ex.data.datastore.FollowedNodesStore
import com.vibe.v2ex.data.datastore.ReadStateStore
import com.vibe.v2ex.data.datastore.SettingsDataStore
import com.vibe.v2ex.data.model.Topic
import com.vibe.v2ex.data.moderation.ModerationStore
import com.vibe.v2ex.data.nodes.NodeCatalog
import com.vibe.v2ex.data.repository.FeedCacheRepository
import com.vibe.v2ex.data.repository.HomeRepository
import com.vibe.v2ex.data.repository.NodesRepository
import com.vibe.v2ex.data.repository.TopicPreviewCache
import com.vibe.v2ex.data.remote.NetworkErrorMessages
import com.vibe.v2ex.data.repository.OfflineRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Independently configured home tabs, up to fifteen entries. */
sealed interface HomeFeed {
    val key: String
    val title: String

    data object All : HomeFeed {
        override val key = "all"
        override val title = "全部"
    }

    data object Hot : HomeFeed {
        override val key = "hot"
        override val title = "最热"
    }

    /** 网页端的投票排序，只有网页有；见 HomeRepository.r2Topics。 */
    data object R2 : HomeFeed {
        override val key = "r2"
        override val title = "R2"
    }

    data object Following : HomeFeed {
        override val key = "following"
        override val title = "关注"
    }

    data class Node(val name: String, override val title: String) : HomeFeed {
        override val key: String get() = "node:$name"
    }
}

data class HomeUiState(
    val feeds: List<HomeFeed> = listOf(HomeFeed.All, HomeFeed.Hot, HomeFeed.R2, HomeFeed.Following),
    val currentIndex: Int = 0,
    val topicsByFeed: Map<String, List<Topic>> = emptyMap(),
    val loadingFeeds: Set<String> = emptySet(),
    val loadingMoreFeeds: Set<String> = emptySet(),
    val errorsByFeed: Map<String, String> = emptyMap(),
    val loadMoreErrorsByFeed: Map<String, String> = emptyMap(),
    val hasMoreByFeed: Map<String, Boolean> = emptyMap(),
    /** Changes after every successful page, including an overlap-only page. */
    val paginationTokensByFeed: Map<String, Long> = emptyMap(),
    /** 该 feed 当前展示的是本地快照（断网 / 还没刷新成功），值是快照时间。 */
    val cachedAtByFeed: Map<String, Long> = emptyMap(),
    /** 已读话题（配合 dimReadTopics 置灰）+ 已离线话题（行尾徽章）。 */
    val readIds: Set<Long> = emptySet(),
    val dimReadTopics: Boolean = false,
    val offlineIds: Set<Long> = emptySet(),
    val communityPulseEnabled: Boolean = true,
) {
    val currentFeed: HomeFeed get() = feeds.getOrElse(currentIndex) { HomeFeed.All }

    /** Keep readable rows and their cursor, but never hide a failed refresh behind them. */
    internal fun refreshFailed(feedKey: String, message: String, liveHasMore: Boolean?): HomeUiState = copy(
        loadingFeeds = loadingFeeds - feedKey,
        errorsByFeed = errorsByFeed + (feedKey to message),
        hasMoreByFeed = if (liveHasMore != null) {
            hasMoreByFeed + (feedKey to liveHasMore)
        } else {
            hasMoreByFeed - feedKey
        },
    )
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: HomeRepository,
    private val feedCacheRepository: FeedCacheRepository,
    private val nodesRepository: NodesRepository,
    private val followedNodesStore: FollowedNodesStore,
    private val homeTabsStore: HomeTabsStore,
    private val moderationStore: ModerationStore,
    private val networkErrors: NetworkErrorMessages,
    private val topicPreviews: TopicPreviewCache,
    readStateStore: ReadStateStore,
    settingsDataStore: SettingsDataStore,
    offlineRepository: OfflineRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private data class FeedSnapshot(
        val topics: List<Topic>,
        val page: Int,
        val hasMore: Boolean,
    )

    private data class HomeModerationRules(
        val hiddenTopicIds: List<Long>,
        val blockedUsernames: List<String>,
        val unavailableMemberIds: List<Long>,
    )

    private val snapshots = mutableMapOf<String, FeedSnapshot>()
    /** Raw live or disk-backed rows currently represented in [HomeUiState.topicsByFeed]. */
    private val displayedRawTopics = mutableMapOf<String, List<Topic>>()
    private val requestGenerations = mutableMapOf<String, Long>()
    private var followedNames: List<String> = emptyList()
    private var moderationRules: HomeModerationRules? = null

    init {
        viewModelScope.launch {
            repository.sessionRevision.collect {
                invalidateFollowingFeed()
                if (_uiState.value.currentFeed == HomeFeed.Following) loadIfNeeded(HomeFeed.Following)
            }
        }
        viewModelScope.launch {
            combine(
                moderationStore.hiddenTopicIds,
                moderationStore.blockedUsernames,
                moderationStore.unavailableBlockedMemberIds,
            ) { hiddenTopicIds, blockedUsernames, unavailableMemberIds ->
                HomeModerationRules(hiddenTopicIds, blockedUsernames, unavailableMemberIds)
            }.collect { rules ->
                moderationRules = rules
                _uiState.update { state ->
                    state.copy(
                        topicsByFeed = state.topicsByFeed.mapValues { (feedKey, topics) ->
                            displayedRawTopics[feedKey]?.let { visibleTopics(it, feedKey) } ?: topics
                        },
                    )
                }
            }
        }
        viewModelScope.launch {
            readStateStore.readIds.collect { ids -> _uiState.update { it.copy(readIds = ids) } }
        }
        viewModelScope.launch {
            settingsDataStore.dimReadTopics.collect { dim -> _uiState.update { it.copy(dimReadTopics = dim) } }
        }
        viewModelScope.launch {
            settingsDataStore.communityPulseEnabled.collect { enabled ->
                _uiState.update { it.copy(communityPulseEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            offlineRepository.observeSummaries()
                .map { summaries -> summaries.mapTo(mutableSetOf()) { it.topicId } }
                .collect { ids -> _uiState.update { it.copy(offlineIds = ids) } }
        }
        viewModelScope.launch {
            followedNodesStore.names.collect { names ->
                followedNames = names
                val liveTitles = nodesRepository.cachedTitleMap()
                homeTabsStore.initialize(names.map { HomeTab("node:$it", NodeCatalog.displayName(it, liveTitles)) })
            }
        }
        viewModelScope.launch {
            homeTabsStore.items.collect { saved ->
                if (saved == null) return@collect
                val feeds = saved.map { tab ->
                    when (tab.key) {
                        "all" -> HomeFeed.All
                        "hot" -> HomeFeed.Hot
                        "r2" -> HomeFeed.R2
                        "following" -> HomeFeed.Following
                        else -> HomeFeed.Node(tab.key.removePrefix("node:"), tab.title)
                    }
                }
                val previous = _uiState.value.currentFeed.key
                if (feeds.none { it.key == previous }) invalidateRequest(previous)
                _uiState.update { state ->
                    state.copy(feeds = feeds, currentIndex = feeds.indexOfFirst { it.key == state.currentFeed.key }.coerceAtLeast(0))
                }
                loadIfNeeded(_uiState.value.currentFeed)
            }
        }
    }

    fun saveTabs(tabs: List<HomeTab>): Unit = homeTabsStore.save(tabs)
    fun defaultTabs(): List<HomeTab> = HomeTabsStore.defaults(followedNames.map { HomeTab("node:$it", NodeCatalog.displayName(it, nodesRepository.cachedTitleMap())) })
    suspend fun tabNodes(): Result<List<com.vibe.v2ex.data.model.Node>> = nodesRepository.allNodes()

    fun selectFeed(index: Int) {
        val state = _uiState.value
        if (index !in state.feeds.indices) return
        if (index != state.currentIndex) {
            invalidateRequest(state.currentFeed.key)
            _uiState.update { it.copy(currentIndex = index) }
        }
        loadIfNeeded(state.feeds[index])
    }

    fun refresh(feed: HomeFeed) {
        val state = _uiState.value
        if (state.currentFeed.key != feed.key) return
        if (feed.key in state.loadingFeeds || feed.key in state.loadingMoreFeeds) return
        load(feed, force = true)
    }

    private fun loadIfNeeded(feed: HomeFeed) {
        snapshots[feed.key]?.let { snapshot ->
            publishSnapshot(feed.key, snapshot)
            return
        }
        load(feed, force = false)
    }

    private fun load(feed: HomeFeed, force: Boolean) {
        val state = _uiState.value
        if (feed.key in state.loadingFeeds || feed.key in state.loadingMoreFeeds) return
        if (!force) {
            snapshots[feed.key]?.let { snapshot ->
                publishSnapshot(feed.key, snapshot)
                return
            }
        }
        val request = nextGeneration(feed.key)
        val diskKey = feed.key
        _uiState.update {
            it.copy(
                loadingFeeds = it.loadingFeeds + feed.key,
                errorsByFeed = it.errorsByFeed - feed.key,
                loadMoreErrorsByFeed = it.loadMoreErrorsByFeed - feed.key,
            )
        }
        viewModelScope.launch {
            // 断网冷启动时先把上次的快照放出来 —— 正文早已离线，缺的只是能点进去的列表。
            if (!force && feed != HomeFeed.Following) hydrateFromCache(feed, request, diskKey)

            val result = runCatching { fetch(feed = feed, page = 1) }
            if (!isCurrent(feed.key, request) || _uiState.value.currentFeed.key != feed.key) return@launch
            result.onSuccess { fetched ->
                val snapshot = fetched
                snapshots[feed.key] = snapshot
                displayedRawTopics[feed.key] = snapshot.topics
                // Only a live response owns a cursor. A disk snapshot remains display-only until this succeeds.
                _uiState.update {
                    it.copy(
                        topicsByFeed = it.topicsByFeed + (feed.key to visibleTopics(snapshot.topics, feed.key)),
                        loadingFeeds = it.loadingFeeds - feed.key,
                        cachedAtByFeed = it.cachedAtByFeed - feed.key,
                        errorsByFeed = it.errorsByFeed - feed.key,
                        hasMoreByFeed = it.hasMoreByFeed + (feed.key to snapshot.hasMore),
                        paginationTokensByFeed = it.paginationTokensByFeed +
                            (feed.key to ((it.paginationTokensByFeed[feed.key] ?: 0L) + 1L)),
                    )
                }
                if (feed != HomeFeed.Following) runCatching { feedCacheRepository.save(diskKey, snapshot.topics) }
            }.onFailure {
                val message = networkErrors.describe(result.exceptionOrNull(), "加载失败")
                _uiState.update {
                    it.refreshFailed(feed.key, message, snapshots[feed.key]?.hasMore)
                }
            }
        }
    }

    fun loadMore(feed: HomeFeed) {
        val state = _uiState.value
        val previous = snapshots[feed.key] ?: return
        if (state.currentFeed.key != feed.key || !previous.hasMore) return
        if (feed.key in state.loadingFeeds || feed.key in state.loadingMoreFeeds) return

        val request = nextGeneration(feed.key)
        val diskKey = feed.key
        _uiState.update {
            it.copy(
                loadingMoreFeeds = it.loadingMoreFeeds + feed.key,
                loadMoreErrorsByFeed = it.loadMoreErrorsByFeed - feed.key,
            )
        }
        viewModelScope.launch {
            val result = runCatching {
                fetch(feed = feed, page = previous.page + 1)
            }
            if (!isCurrent(feed.key, request) || _uiState.value.currentFeed.key != feed.key) return@launch
            result.onSuccess { fetched ->
                val known = previous.topics.mapTo(HashSet()) { it.id }
                val unique = fetched.topics.filter { known.add(it.id) }
                val snapshot = fetched.copy(topics = previous.topics + unique)
                snapshots[feed.key] = snapshot
                displayedRawTopics[feed.key] = snapshot.topics
                _uiState.update {
                    it.copy(
                        topicsByFeed = it.topicsByFeed + (feed.key to visibleTopics(snapshot.topics, feed.key)),
                        loadingMoreFeeds = it.loadingMoreFeeds - feed.key,
                        loadMoreErrorsByFeed = it.loadMoreErrorsByFeed - feed.key,
                        cachedAtByFeed = it.cachedAtByFeed - feed.key,
                        hasMoreByFeed = it.hasMoreByFeed + (feed.key to snapshot.hasMore),
                        // Do not key pagination to topic count: a valid page may contain only duplicates.
                        paginationTokensByFeed = it.paginationTokensByFeed +
                            (feed.key to ((it.paginationTokensByFeed[feed.key] ?: 0L) + 1L)),
                    )
                }
                if (feed != HomeFeed.Following) runCatching { feedCacheRepository.save(diskKey, snapshot.topics) }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        loadingMoreFeeds = it.loadingMoreFeeds - feed.key,
                        loadMoreErrorsByFeed = it.loadMoreErrorsByFeed +
                            (feed.key to networkErrors.describe(error, "加载更多失败，请重试")),
                    )
                }
            }
        }
    }

    private suspend fun fetch(feed: HomeFeed, page: Int): FeedSnapshot =
        fetchFeed(feed, page).also { if (feed != HomeFeed.Following) topicPreviews.remember(it.topics) }

    private suspend fun fetchFeed(feed: HomeFeed, page: Int): FeedSnapshot = when (feed) {
        HomeFeed.Hot -> FeedSnapshot(
            topics = repository.hotTopics().getOrThrow(),
            page = 1,
            hasMore = false,
        )
        HomeFeed.R2 -> FeedSnapshot(
            topics = repository.r2Topics().getOrThrow(),
            page = 1,
            hasMore = false,
        )
        HomeFeed.All -> repository.publicTopicPage(nodeName = null, page = page).getOrThrow().let {
            FeedSnapshot(topics = it.topics, page = page, hasMore = it.hasMore)
        }
        is HomeFeed.Node -> repository.publicTopicPage(nodeName = feed.name, page = page).getOrThrow().let {
            FeedSnapshot(topics = it.topics, page = page, hasMore = it.hasMore)
        }
        HomeFeed.Following -> repository.followingTopics(page).let {
            FeedSnapshot(topics = it.topics, page = page, hasMore = it.hasMore)
        }
    }

    private suspend fun hydrateFromCache(feed: HomeFeed, request: Long, diskKey: String) {
        if (feed.key in _uiState.value.topicsByFeed) return
        val cached = feedCacheRepository.load(diskKey) ?: return
        topicPreviews.remember(cached.topics)
        if (!isCurrent(feed.key, request) || _uiState.value.currentFeed.key != feed.key) return
        displayedRawTopics[feed.key] = cached.topics
        _uiState.update { state ->
            if (feed.key in state.topicsByFeed) {
                state
            } else {
                state.copy(
                    topicsByFeed = state.topicsByFeed + (feed.key to visibleTopics(cached.topics)),
                    cachedAtByFeed = state.cachedAtByFeed + (feed.key to cached.updatedAt),
                )
            }
        }
    }

    private fun publishSnapshot(feedKey: String, snapshot: FeedSnapshot) {
        displayedRawTopics[feedKey] = snapshot.topics
        _uiState.update {
            it.copy(
                topicsByFeed = it.topicsByFeed + (feedKey to visibleTopics(snapshot.topics, feedKey)),
                hasMoreByFeed = it.hasMoreByFeed + (feedKey to snapshot.hasMore),
                cachedAtByFeed = it.cachedAtByFeed - feedKey,
                loadMoreErrorsByFeed = it.loadMoreErrorsByFeed - feedKey,
            )
        }
    }

    private fun nextGeneration(feedKey: String): Long {
        val next = (requestGenerations[feedKey] ?: 0L) + 1L
        requestGenerations[feedKey] = next
        return next
    }

    private fun isCurrent(feedKey: String, request: Long): Boolean =
        requestGenerations[feedKey] == request

    private fun invalidateRequest(feedKey: String) {
        nextGeneration(feedKey)
        _uiState.update {
            it.copy(
                loadingFeeds = it.loadingFeeds - feedKey,
                loadingMoreFeeds = it.loadingMoreFeeds - feedKey,
            )
        }
    }

    private fun invalidateFollowingFeed() {
        val key = HomeFeed.Following.key
        snapshots.remove(key)
        displayedRawTopics.remove(key)
        invalidateRequest(key)
        _uiState.update {
            it.copy(
                topicsByFeed = it.topicsByFeed - key,
                errorsByFeed = it.errorsByFeed - key,
                loadMoreErrorsByFeed = it.loadMoreErrorsByFeed - key,
                hasMoreByFeed = it.hasMoreByFeed - key,
                paginationTokensByFeed = it.paginationTokensByFeed - key,
                cachedAtByFeed = it.cachedAtByFeed - key,
            )
        }
    }

    /** Cosmetic home-feed-only ad filter (hides entirely) — same keyword list as iOS. */
    private fun isPromotion(topic: Topic): Boolean {
        val haystack = "${topic.title} ${topic.authorName}".lowercase(Locale.ROOT)
        return PROMOTION_KEYWORDS.any { haystack.contains(it) }
    }

    private fun visibleTopics(raw: List<Topic>, feedKey: String = "all"): List<Topic> {
        val rules = moderationRules ?: return emptyList()
        return raw.filterNot { topic ->
            (feedKey != HomeFeed.Following.key && isPromotion(topic)) || moderationStore.isTopicHidden(
                topic = topic,
                hiddenIds = rules.hiddenTopicIds,
                blockedUsers = rules.blockedUsernames,
                keywords = emptyList(),
                blockedMemberIds = rules.unavailableMemberIds,
            )
        }
    }

    private companion object {
        val PROMOTION_KEYWORDS = listOf(
            "邀请码", "免费送", "动态住宅", "住宅 ip", "住宅ip", "流量用不完", "注册送", "返利",
        )
    }
}
