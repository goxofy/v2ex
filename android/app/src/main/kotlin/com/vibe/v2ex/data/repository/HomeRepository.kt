package com.vibe.v2ex.data.repository

import com.vibe.v2ex.data.datastore.SecureStore
import com.vibe.v2ex.data.remote.FollowingService
import kotlinx.coroutines.flow.StateFlow
import com.vibe.v2ex.data.model.Topic
import com.vibe.v2ex.data.remote.PublicTopicPage
import com.vibe.v2ex.data.remote.V2exApiV1
import com.vibe.v2ex.data.remote.WebSessionService
import javax.inject.Inject
import javax.inject.Singleton

/** Rejects private feed responses obtained under a previous web session. */
internal class FollowingSession(
    private val revision: StateFlow<Long>,
    private val cookie: () -> String?,
    private val fetch: suspend (Int, String) -> PublicTopicPage,
) {
    suspend fun topics(page: Int): PublicTopicPage {
        val started = revision.value
        val credential = cookie()?.takeIf { it.isNotBlank() }
            ?: error("请先在 App 内网页登录 V2EX，再查看关注")
        val result = fetch(page, credential)
        check(started == revision.value && credential == cookie()) { "登录账号已变化，请重新加载关注" }
        return result
    }
}

@Singleton
class HomeRepository @Inject constructor(
    private val apiV1: V2exApiV1,
    private val webSessionService: WebSessionService,
    followingService: FollowingService,
    credentials: SecureStore,
) {
    val sessionRevision: StateFlow<Long> = credentials.accountCredentialsRevision
    private val followingSession = FollowingSession(
        revision = sessionRevision,
        cookie = { if (credentials.isWebSessionActive) credentials.sessionCookieHeader else null },
        fetch = followingService::topics,
    )

    suspend fun followingTopics(page: Int): PublicTopicPage = followingSession.topics(page)

    /** Public website feed used by recent and node pagination. */
    suspend fun publicTopicPage(nodeName: String?, page: Int): Result<PublicTopicPage> =
        webSessionService.publicTopicPage(nodeName = nodeName, page = page)

    /** Kept for the bounded background offline-sync job; interactive feeds use [publicTopicPage]. */
    suspend fun latestTopics(): Result<List<Topic>> = runCatching { apiV1.latestTopics() }

    /**
     * 「最热」。`/api/topics/hot.json` 服务端硬性只返回 10 条且没有分页参数，网页版
     * `?tab=hot` 同一时刻有 30+ 条。所以优先抓网页，解析结果确实比 API 上限多才采用；
     * 网页改版把解析打残时静默退回 API —— 最热永远不会是空的。
     */
    suspend fun hotTopics(): Result<List<Topic>> = runCatching {
        val scraped = runCatching { webSessionService.hotTopics() }.getOrDefault(emptyList())
        if (scraped.size > HOT_API_LIMIT) scraped else apiV1.hotTopics()
    }

    /**
     * 「R2」。网页端按投票算出来的排序，两版 API 都没有对应接口，只能读网页。
     * 抓不到就当失败 —— 这一栏没有可退的接口，空列表比一份错的排序诚实。
     */
    suspend fun r2Topics(): Result<List<Topic>> = runCatching {
        webSessionService.r2Topics().ifEmpty { error("R2 页面没有返回话题") }
    }

    /** Kept for the bounded background offline-sync job; interactive feeds use [publicTopicPage]. */
    suspend fun topicsInNode(nodeName: String): Result<List<Topic>> =
        runCatching { apiV1.topicsInNode(nodeName) }

    private companion object {
        /** `/api/topics/hot.json` 的服务端返回上限。 */
        const val HOT_API_LIMIT = 10
    }
}
