package com.vibe.v2ex.data.remote

import com.vibe.v2ex.data.datastore.SecureStore
import com.vibe.v2ex.data.model.Topic
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

private const val BASE = "https://www.v2ex.com"

/** 网页收藏列表里的一行（/my/topics）。API 拿不到收藏，只能靠登录态网页。 */
data class ScrapedFavorite(
    val topicId: Long,
    val title: String,
    val nodeName: String,
    val nodeTitle: String,
    val authorName: String,
    val replies: Int,
)

/** 楼主附言（Supplement）。API 1.0/2.0 都不返回，只能从话题网页解析。 */
data class TopicAppend(val index: Int, val timeLabel: String, val contentHtml: String)

/** 话题页一次抓取能给出的所有额外信息：浏览数、附言、PRO 会员。 */
data class TopicPageExtras(
    val views: Int? = null,
    val appends: List<TopicAppend> = emptyList(),
    val proMembers: Set<String> = emptySet(),
)

private data class WebsiteHttpPage(
    val code: Int,
    val finalPath: String,
    val finalUrl: String,
    val html: String,
)

private class WebsiteFlowException(message: String) : IllegalStateException(message)

/**
 * Drives the parts of V2EX that only exist as HTML forms, not the JSON API: posting a
 * reply, favoriting a topic, following a node. Login itself happens in a WebView (see
 * feature/account/WebLoginScreen.kt) — mirrors the iOS app, which ships a working
 * scripted-login implementation in V2EXClient but never wires it into the UI, using
 * WKWebView instead since it handles CAPTCHA/2FA without the app reimplementing them.
 * Session identity lives entirely in the cookie jar (see NetworkModule); the password
 * is never involved here at all.
 */
@Singleton
class WebSessionService @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val secureStore: SecureStore,
    private val cookieJar: PersistentCookieJar,
    private val apiV1: V2exApiV1,
) {
    /** Explicit website-cookie operations must never be overwritten by the app's persisted jar. */
    private val isolatedWebClient: OkHttpClient by lazy {
        okHttpClient.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
    }

    private suspend fun fetchDocument(url: String): Document = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        okHttpClient.newCall(request).execute().use { response ->
            Jsoup.parse(response.body?.string().orEmpty(), url)
        }
    }

    private fun extractOnce(doc: Document): String? =
        doc.select("input[name=once]").firstOrNull()?.attr("value")
            ?: doc.select("a[href*=once=]").firstOrNull()?.attr("href")
                ?.substringAfter("once=")?.substringBefore("&")

    private val loginVerifier: WebLoginVerifier by lazy { WebLoginVerifier(okHttpClient) }

    /** Does not persist anything; challenge pages are inconclusive, not signed out. */
    suspend fun verifyCookie(candidate: WebLoginCandidate): WebSessionVerification = loginVerifier.verify(candidate)

    suspend fun verifySession(): WebSessionVerification {
        if (!secureStore.isWebSessionActive) return WebSessionVerification.SIGNED_OUT
        // Older installs have no captured UA. Keep their previous OkHttp identity until a new login.
        return loginVerifier.verify(WebLoginCandidate(
            secureStore.sessionCookieHeader.orEmpty(), secureStore.sessionUsername.orEmpty(),
            secureStore.sessionUserAgent ?: "okhttp/${okhttp3.OkHttp.VERSION}",
        ))
    }

    /** Public website feed pagination: `/recent?p=N` or `/go/{node}?p=N`. */
    suspend fun publicTopicPage(nodeName: String?, page: Int): Result<PublicTopicPage> =
        withContext(Dispatchers.IO) {
            websiteResult("加载网页话题失败，请检查网络后重试") {
                if (page < 1) websiteFailure("页码必须大于 0")
                val node = nodeName?.trim()?.also {
                    if (!WEBSITE_NODE_NAME.matches(it)) websiteFailure("节点名称无效")
                }
                val path = node?.let { "/go/$it" } ?: "/recent"
                val response = executeWebsiteGet(path = "$path?p=$page", userAgent = DESKTOP_USER_AGENT)
                validateWebsitePage(response, operation = "加载网页话题", expectedPath = path)
                val topics = WebsitePageParser.publicTopics(
                    html = response.html,
                    baseUrl = response.finalUrl,
                    fallbackNodeName = node,
                )
                if (topics.isEmpty()) websiteFailure("网页没有返回话题，请稍后重试")
                PublicTopicPage(
                    topics = topics,
                    hasMore = WebsitePageParser.hasMorePublicTopics(
                        html = response.html,
                        baseUrl = response.finalUrl,
                        expectedPath = path,
                        page = page,
                    ),
                )
            }
        }

    /** Reads the authoritative blocked member IDs embedded in the logged-in desktop homepage. */
    suspend fun blockedUsers(cookieHeader: String): Result<WebsiteBlockSnapshot> =
        withContext(Dispatchers.IO) {
            websiteResult("读取官网屏蔽名单失败，请检查网络后重试") {
                readBlockedUsers(validCookie(cookieHeader))
            }
        }

    /** Uses the exact action supplied by a member page and confirms both member ID and final state. */
    suspend fun setMemberBlocked(
        cookieHeader: String,
        username: String,
        blocked: Boolean,
    ): Result<WebsiteBlockMutation> = withContext(Dispatchers.IO) {
        websiteResult("更新官网屏蔽状态失败，请检查网络后重试") {
            val cookie = validCookie(cookieHeader)
            val memberName = validWebsiteName(username, "用户名格式不正确")
            val memberPath = "/member/$memberName"
            val before = readMemberBlockPage(memberPath, cookie)
            if (before.blocked != blocked) {
                val actionResponse = executeWebsiteGet(
                    path = before.actionPath,
                    cookieHeader = cookie,
                    userAgent = MOBILE_USER_AGENT,
                    refererPath = memberPath,
                )
                validateWebsitePage(actionResponse, operation = if (blocked) "屏蔽用户" else "取消屏蔽")
                val after = readMemberBlockPage(memberPath, cookie)
                if (after.memberId != before.memberId || after.blocked != blocked) {
                    websiteFailure("官网尚未确认${if (blocked) "屏蔽" else "取消屏蔽"}，请重试")
                }
            }
            WebsiteBlockMutation(
                username = memberName,
                memberId = before.memberId,
                blocked = blocked,
            )
        }
    }

    /** Reads the website-wide unread counter without opening the side-effecting notifications page. */
    suspend fun notificationReadState(
        cookieHeader: String,
        expectedUsername: String,
    ): Result<WebsiteNotificationState> = withContext(Dispatchers.IO) {
        websiteResult("读取官网未读提醒失败，请检查网络后重试") {
            readNotificationState(validCookie(cookieHeader), validExpectedUsername(expectedUsername))
        }
    }

    /** Pre-validates account identity, visits `/notifications`, then re-reads the homepage counter. */
    suspend fun markNotificationsRead(
        cookieHeader: String,
        expectedUsername: String,
    ): Result<WebsiteNotificationState> = withContext(Dispatchers.IO) {
        websiteResult("标记官网提醒已读失败，请检查网络后重试") {
            val cookie = validCookie(cookieHeader)
            val expected = validExpectedUsername(expectedUsername)
            readNotificationState(cookie, expected)

            val response = executeWebsiteGet(
                path = "/notifications",
                cookieHeader = cookie,
                userAgent = DESKTOP_USER_AGENT,
            )
            validateWebsitePage(response, operation = "打开官网提醒", expectedPath = "/notifications")
            val stateOnPage = WebsitePageParser.notificationState(response.html)
            if (!WebsitePageParser.isConfirmedNotificationsPage(response.html) ||
                stateOnPage?.username?.equals(expected, ignoreCase = true) != true
            ) {
                websiteFailure("官网未确认提醒页面，请重试")
            }

            // HTTP 200 is not an acknowledgement: only the newly parsed homepage state is returned.
            readNotificationState(cookie, expected)
        }
    }

    suspend fun postReply(topicId: Long, content: String): Result<Unit> =
        ReplySubmission(okHttpClient).postReply(
            topicId = topicId,
            content = content,
            cookie = secureStore.sessionCookieHeader,
            username = secureStore.sessionUsername,
        )

    suspend fun setFavoriteTopic(topicId: Long, favorited: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val topicPage = fetchDocument("$BASE/t/$topicId")
            val action = if (favorited) "favorite" else "unfavorite"
            val href = topicPage.select("a[href*=/$action/topic/$topicId]").firstOrNull()?.attr("href")
                ?: error("missing $action link")
            val request = Request.Builder().url(BASE + href).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("$action failed: ${response.code}")
            }
        }
    }

    /** Use the exact current action, then re-read the node to confirm its ID and state. */
    suspend fun setFollowNode(cookieHeader: String, nodeName: String, following: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            websiteResult("更新官网节点关注失败，请检查网络后重试") {
                val cookie = validCookie(cookieHeader)
                if (!WEBSITE_NODE_NAME.matches(nodeName)) websiteFailure("节点名称无效")
                val path = "/go/$nodeName"
                val before = readNodeFavoritePage(path, cookie)
                if (before.following != following) {
                    currentCoroutineContext().ensureActive()
                    val response = executeWebsiteGet(
                        path = before.actionPath,
                        cookieHeader = cookie,
                        userAgent = MOBILE_USER_AGENT,
                        refererPath = path,
                    )
                    validateWebsitePage(response, operation = "更新节点关注")
                    val after = readNodeFavoritePage(path, cookie)
                    if (after.nodeId != before.nodeId || after.following != following) {
                        websiteFailure("官网尚未确认${if (following) "关注" else "取消关注"}，请重试")
                    }
                }
            }
        }

    private fun readNodeFavoritePage(path: String, cookie: String): WebsiteNodeFavoritePage {
        val response = executeWebsiteGet(path, cookie, MOBILE_USER_AGENT)
        validateWebsitePage(response, operation = "读取节点关注状态", expectedPath = path)
        return WebsitePageParser.nodeFavoritePage(response.html) ?: run {
            if (Jsoup.parse(response.html).select("a[href]").any {
                    it.attr("href").substringBefore('?') == "/signin"
                }) websiteFailure("网页会话已失效，请重新登录 V2EX")
            websiteFailure("无法读取官网节点关注状态，请确认节点可访问后重试")
        }
    }

    /**
     * 网页版「最热」（GET /?tab=hot）。官方 `/api/topics/hot.json` 服务端硬性只返回
     * 10 条且没有分页参数，网页同一时刻有 30+ 条 —— 用户对着网页看会觉得 App 少了
     * 一大截。这一页未登录也拿得全，所以不需要会话（mirrors iOS hotTopicsFromWeb）。
     */
    suspend fun hotTopics(): List<Topic> = withContext(Dispatchers.IO) {
        parseTopicRows(fetchDocument("$BASE/?tab=hot"))
    }

    /**
     * 网页版 R2（GET /?tab=r2）。R2 是网页端按投票算出来的首页排序，不是节点：
     * v1 的 latest/hot 接口会忽略 `tab` 参数，v2 也没有 tabs 接口，只能读网页
     * （mirrors iOS r2Topics）。未登录同样拿得到。
     */
    suspend fun r2Topics(): List<Topic> = withContext(Dispatchers.IO) {
        // R2 页面会被 CDN 短暂缓存，带个独立查询参数，下拉刷新才能拿到新排序。
        parseTopicRows(fetchDocument("$BASE/?tab=r2&_=${System.currentTimeMillis()}"))
    }

    /**
     * 解析首页/节点页的话题行。一行的结构：
     * ```
     * <div class="cell item"><table><tr>
     *   <td><a href="/member/foo"><img class="avatar" src="…_normal.png"></a></td>
     *   <td><span class="item_title"><a href="/t/123#reply22" class="topic-link">标题</a></span>
     *     <span class="topic_info"><a class="node" href="/go/career">职场话题</a> •
     *       <strong><a href="/member/foo">foo</a></strong> •
     *       <span title="2026-08-31 11:30:18 +08:00">6 mins ago</span> •
     *       Lastly replied by <strong><a href="/member/bar">bar</a></strong></span></td>
     *   <td><a class="count_livid">22</a></td>
     * </tr></table></div>
     * ```
     * 作者和「最后回复者」用的是同一种 `<strong><a href="/member/…">` 标记，只有按
     * 文档顺序取第一个才不会认错人。时间取 `title` 里的绝对时间戳而不是「6 mins ago」
     * 文案 —— 后者随登录语言在中英文之间切换，解析出来也只是个近似值。
     * 正文网页列表不提供，[Topic.content] 留空，只影响首页大卡片的摘要行。
     */
    private fun parseTopicRows(doc: Document): List<Topic> =
        WebsitePageParser.publicTopics(doc.html(), doc.baseUri())

    /**
     * 网页收藏列表：GET /my/topics 分页拉全（每页 20 条，最多 [maxPages] 页）。
     * 未登录会被重定向到 /signin，此时抛错让调用方静默跳过。
     */
    suspend fun favoriteTopics(maxPages: Int = 10): Result<List<ScrapedFavorite>> = withContext(Dispatchers.IO) {
        runCatching {
            val all = mutableListOf<ScrapedFavorite>()
            for (page in 1..maxPages.coerceIn(1, 10)) {
                val doc = fetchDocument(if (page == 1) "$BASE/my/topics" else "$BASE/my/topics?p=$page")
                val rows = doc.select("span.item_title > a.topic-link")
                if (rows.isEmpty()) {
                    if (page == 1 && doc.select("a[href*=/signin]").isNotEmpty()) error("未登录")
                    break
                }
                for (link in rows) {
                    val id = Regex("""/t/(\d+)""").find(link.attr("href"))?.groupValues?.get(1)?.toLongOrNull()
                        ?: continue
                    val cell = link.closest(".cell")
                    val nodeLink = cell?.selectFirst("a.node")
                    val author = cell?.selectFirst("strong > a")?.text().orEmpty()
                    val replies = cell?.selectFirst("a.count_livid, a.count_orange, a.count_gray")
                        ?.text()?.toIntOrNull() ?: 0
                    all += ScrapedFavorite(
                        topicId = id,
                        title = link.text(),
                        nodeName = nodeLink?.attr("href")?.substringAfterLast('/').orEmpty(),
                        nodeTitle = nodeLink?.text().orEmpty(),
                        authorName = author,
                        replies = replies,
                    )
                }
                if (rows.size < 20) break // 不满一页 = 最后一页
            }
            all
        }
    }

    /** 网页「我收藏的节点」（/my/nodes）— API 2.0 没有关注节点接口。 */
    suspend fun favoriteNodeNames(cookieHeader: String): Result<List<String>> = withContext(Dispatchers.IO) {
        websiteResult("读取官网关注节点失败，请检查网络后重试") {
            val response = executeWebsiteGet("/my/nodes", validCookie(cookieHeader), MOBILE_USER_AGENT)
            validateWebsitePage(response, operation = "读取关注节点", expectedPath = "/my/nodes")
            val doc = Jsoup.parse(response.html)
            if (doc.select("a[href]").any { it.attr("href").substringBefore('?') == "/signin" }) {
                websiteFailure("网页会话已失效，请重新登录 V2EX")
            }
            doc.select("a[href^=/go/]")
                .mapNotNull { Regex("""^/go/([a-zA-Z0-9_-]+)$""").matchEntire(it.attr("href"))?.groupValues?.get(1) }
                .distinct()
        }
    }

    /**
     * 读取浏览器插件 V2EX Polish 备份在记事本里的设置（协议见 [PolishSettingsNoteParser]）。
     * null = 这个账号还没有备份记事本。记事本里还有插件自己的 Token 等私密设置，
     * 只在内存里过一遍交给调用方按键取用，不落盘、不进日志。
     */
    suspend fun polishSettingsNote(cookieHeader: String): Result<PolishSettingsNote?> =
        withContext(Dispatchers.IO) {
            websiteResult("读取记事本失败，请检查网络后重试") {
                val cookie = validCookie(cookieHeader)
                val noteId = readPolishNoteId(cookie) ?: return@websiteResult null
                PolishSettingsNoteParser.parse(noteId, readPolishNoteContent(noteId, cookie))
                    ?: websiteFailure(
                        "记事本里的 V2EX_Polish_settings 不是插件认可的备份格式。请在插件弹窗里点「开始备份」；" +
                            "仍不行就在 V2EX 记事本里删掉这篇再备份一次",
                    )
            }
        }

    /**
     * 把整篇内容写进插件已有的备份记事本（新建只交给插件，见 [PolishSettingsNoteParser.buildContent]）。
     * 插件的请求不带 once，这里编辑页表单里若有 once 也一并带上。HTTP 200 不算数：写完重新读回
     * 逐字比对，官网没存下来就报失败。
     */
    suspend fun writePolishSettingsNote(cookieHeader: String, noteId: Long, content: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            websiteResult("写入记事本失败，请检查网络后重试") {
                val cookie = validCookie(cookieHeader)
                val formPath = "/notes/edit/$noteId"
                val formPage = executeWebsiteGet(formPath, cookie, DESKTOP_USER_AGENT)
                validateWebsitePage(formPage, operation = "打开记事本编辑页", expectedPath = formPath)
                val once = Jsoup.parse(formPage.html).selectFirst("form input[name=once]")?.attr("value")
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("content", content)
                    .addFormDataPart("syntax", "0")
                    .apply { once?.takeIf(String::isNotBlank)?.let { addFormDataPart("once", it) } }
                    .build()
                val response = executeWebsitePost(formPath, cookie, DESKTOP_USER_AGENT, body, refererPath = formPath)
                if (response.finalPath == "/signin" || response.finalPath.startsWith("/2fa") || response.code == 401) {
                    websiteFailure("网页会话已失效，请重新登录 V2EX")
                }
                if (response.code >= 500) websiteFailure("V2EX 服务暂时不可用（HTTP ${response.code}），请稍后重试")
                if (response.code !in 200..399) websiteFailure("写入记事本失败（HTTP ${response.code}），请稍后重试")
                Jsoup.parse(response.html).selectFirst("div.problem")?.let { problem ->
                    websiteFailure(problem.text().ifBlank { "记事本被官网拒绝，请稍后重试" })
                }
                currentCoroutineContext().ensureActive()
                val stored = readPolishNoteContent(noteId, cookie)
                if (stored.trim() != content.trim()) websiteFailure("官网未确认记事本内容，请稍后重试")
            }
        }

    private fun readPolishNoteId(cookie: String): Long? {
        val list = executeWebsiteGet("/notes", cookie, DESKTOP_USER_AGENT)
        validateWebsitePage(list, operation = "读取记事本列表", expectedPath = "/notes")
        return PolishSettingsNoteParser.findNoteId(list.html)
    }

    private fun readPolishNoteContent(noteId: Long, cookie: String): String {
        val path = "/notes/edit/$noteId"
        val page = executeWebsiteGet(path, cookie, DESKTOP_USER_AGENT)
        validateWebsitePage(page, operation = "读取记事本", expectedPath = path)
        return PolishSettingsNoteParser.noteContent(page.html)
            ?: websiteFailure("记事本页面没有编辑框，请确认登录仍有效后重试")
    }

    /**
     * 一次抓取话题页，同时解析浏览数、附言与 PRO 徽章 —— 三者都只存在于网页，
     * 分开请求会浪费整次网页往返。
     */
    suspend fun topicPageExtras(topicId: Long): TopicPageExtras = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("$BASE/t/$topicId").build()
            val html = okHttpClient.newCall(request).execute().use { it.body?.string().orEmpty() }
            TopicPageExtras(
                views = Regex("""([\d,]+)\s*(?:views|次点击)""").find(html)
                    ?.groupValues?.get(1)?.filter(Char::isDigit)?.toIntOrNull(),
                appends = extractAppends(html),
                proMembers = extractProMembers(html),
            )
        }.getOrDefault(TopicPageExtras())
    }

    /**
     * 解析附言块。新版结构：`<div class="subtle"><span class="fade">第 1 条附言 · 时间</span>
     * <div class="topic_content">…</div></div>`；旧帖仍可能是 topic_append 结构。
     */
    private fun extractAppends(html: String): List<TopicAppend> {
        val result = mutableListOf<TopicAppend>()
        val supplementPattern = Regex(
            """<div class="subtle">[\s\S]*?<span class="fade">[^<]*?(\d+)[^<]*?·\s*([^<]+)</span>[\s\S]*?<div class="topic_content">([\s\S]*?)</div>""",
        )
        for (match in supplementPattern.findAll(html)) {
            val content = match.groupValues[3].trim()
            if (content.isEmpty()) continue
            result += TopicAppend(
                index = match.groupValues[1].toIntOrNull() ?: (result.size + 1),
                timeLabel = match.groupValues[2].replace("&nbsp;", "").trim(),
                contentHtml = content,
            )
        }
        val legacyPattern = Regex(
            """<div class="topic_append">[\s\S]*?<span class="time">([^<]+)</span>[\s\S]*?<div class="topic_append_content">([\s\S]*?)</div>""",
        )
        for (match in legacyPattern.findAll(html)) {
            result += TopicAppend(
                index = result.size + 1,
                timeLabel = match.groupValues[1].trim(),
                contentHtml = match.groupValues[2].trim(),
            )
        }
        return result
    }

    /**
     * 本页佩戴 PRO 徽章的用户名。徽章容器紧跟在作者自己的 /member/ 链接后面，
     * 两组匹配都按文档顺序返回，单向游走即可配对。
     */
    private fun extractProMembers(html: String): Set<String> {
        val members = Regex("""/member/([A-Za-z0-9_-]+)""").findAll(html).toList()
        if (members.isEmpty()) return emptySet()
        val badges = Regex("""<div class="badge pro">""").findAll(html).toList()
        val found = mutableSetOf<String>()
        var cursor = 0
        for (badge in badges) {
            while (cursor + 1 < members.size && members[cursor + 1].range.first < badge.range.first) cursor++
            if (members[cursor].range.first < badge.range.first) found += members[cursor].groupValues[1]
        }
        return found
    }

    /**
     * 发布新主题并返回其 id。API 没有写端点，走网页 /write 表单（once + POST）。
     * 成功只认落在真实 /t/<id> 的重定向；response body 回显草稿的拒绝页绝不能当成功。
     * 无法确认时向 API 查询「我最近的话题」兜底，避免误报失败导致重复发帖。
     */
    suspend fun createTopic(
        title: String,
        content: String,
        nodeName: String,
        recentTopicIdByTitle: suspend (String) -> Long?,
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmedTitle = title.trim()
            require(trimmedTitle.isNotEmpty()) { "标题不能为空" }
            require(nodeName.isNotEmpty()) { "请先选择节点" }

            val formPath = "$BASE/write?node=$nodeName"
            val form = fetchDocument(formPath)
            if (mentionsCaptcha(form.html())) error("V2EX 要求验证码，这一步只能在网页完成")
            val once = extractOnce(form) ?: error("会话可能已失效，请重新登录")

            val body = FormBody.Builder()
                .add("title", trimmedTitle)
                .add("content", content)
                .add("node_name", nodeName)
                .add("syntax", "markdown")
                .add("once", once)
                .build()
            val request = Request.Builder()
                .url("$BASE/write")
                .header("Referer", formPath)
                .post(body)
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                // 快路径：重定向直接落到了新帖。
                val finalPath = response.request.url.encodedPath
                Regex("""^/t/(\d+)$""").find(finalPath)?.groupValues?.get(1)?.toLongOrNull()?.let { return@use it }

                val html = response.body?.string().orEmpty()
                Jsoup.parse(html).selectFirst("div.problem")?.let { problem ->
                    error(problem.text().ifBlank { "发布被拒绝，请稍后重试" })
                }
                if (mentionsCaptcha(html)) error("V2EX 要求验证码，这一步只能在网页完成")

                // 没有重定向也没有错误页 —— 问 API 帖子是否已存在，避免误报失败。
                for (attempt in 0 until 3) {
                    if (attempt > 0) delay(2_000)
                    recentTopicIdByTitle(trimmedTitle)?.let { return@use it }
                }
                error("发布结果未确认。请到网页查看是否已发出，避免重复发送。")
            }
        }
    }

    // Kept broad for the standalone /write flow, whose page contains no user discussion text.
    private fun mentionsCaptcha(html: String): Boolean = html.contains("captcha") || html.contains("验证码")

    /** Scoped to challenge/form markup so a normal reply discussing "captcha/验证码" is not a false hit. */
    private fun hasCaptchaChallenge(document: Document): Boolean =
        document.select(
            "input[name*=captcha], input[id*=captcha], img[src*=captcha], " +
                "[class*=captcha], [id*=captcha]",
        ).isNotEmpty() || document.select("form").any { form ->
            form.text().contains("验证码") && form.select("input[name=code]").isNotEmpty()
        }

    /** Called after a successful WebView login — see WebLoginScreen. */
    fun saveWebSession(candidate: WebLoginCandidate): Unit {
        // 新登录整体替换会话，不与旧 cookie 合并 —— 混入过期条目会让后续网页请求被拒。
        cookieJar.clear()
        cookieJar.setCookieHeader(candidate.cookie)
        secureStore.sessionUsername = candidate.username
        secureStore.sessionUserAgent = candidate.userAgent
        secureStore.webLoggedIn = true
    }

    fun signOutWebSession() {
        cookieJar.clear()
        secureStore.clearWebSession()
    }

    private suspend fun readBlockedUsers(cookieHeader: String): WebsiteBlockSnapshot {
        val response = executeWebsiteGet(
            path = "/",
            cookieHeader = cookieHeader,
            userAgent = DESKTOP_USER_AGENT,
        )
        validateWebsitePage(response, operation = "读取官网屏蔽名单", expectedPath = "/")
        val ids = WebsitePageParser.blockedMemberIds(response.html)
            ?: websiteFailure("无法读取官网屏蔽名单，请确认网页登录有效后重试")

        val usernames = mutableListOf<String>()
        val unavailable = mutableListOf<Long>()
        for (id in ids) {
            try {
                val member = apiV1.showMemberById(id)
                if (member.id != id || member.username.isBlank()) {
                    websiteFailure("官网屏蔽用户信息不完整，请稍后重试")
                }
                usernames += member.username
            } catch (error: CancellationException) {
                throw error
            } catch (error: HttpException) {
                if (error.code() == 404) {
                    unavailable += id
                } else {
                    websiteFailure("读取官网屏蔽用户失败（HTTP ${error.code()}），已保留原名单")
                }
            } catch (error: WebsiteFlowException) {
                throw error
            } catch (_: Throwable) {
                websiteFailure("读取官网屏蔽用户失败，请检查网络后重试")
            }
        }
        return WebsiteBlockSnapshot(
            usernames = usernames.distinctBy { it.lowercase(Locale.ROOT) },
            unavailableMemberIds = unavailable,
        )
    }

    private fun readMemberBlockPage(path: String, cookieHeader: String): WebsiteMemberBlockPage {
        val response = executeWebsiteGet(
            path = path,
            cookieHeader = cookieHeader,
            userAgent = MOBILE_USER_AGENT,
        )
        validateWebsitePage(response, operation = "读取官网用户状态")
        if (!response.finalPath.equals(path, ignoreCase = true)) {
            websiteFailure("读取官网用户状态返回了异常页面，请稍后重试")
        }
        return WebsitePageParser.memberBlockPage(response.html)
            ?: websiteFailure("未找到官网屏蔽按钮，请确认登录仍有效、用户存在且不是你自己")
    }

    private fun readNotificationState(
        cookieHeader: String,
        expectedUsername: String,
    ): WebsiteNotificationState {
        val response = executeWebsiteGet(
            path = "/",
            cookieHeader = cookieHeader,
            userAgent = DESKTOP_USER_AGENT,
        )
        validateWebsitePage(response, operation = "读取官网未读提醒", expectedPath = "/")
        val state = WebsitePageParser.notificationState(response.html)
            ?: websiteFailure("无法读取官网未读提醒，请重新登录后重试")
        if (!state.username.equals(expectedUsername, ignoreCase = true)) {
            websiteFailure("网页登录账号与通知 Token 的账号不一致")
        }
        return state
    }

    private fun executeWebsiteGet(
        path: String,
        cookieHeader: String? = null,
        userAgent: String,
        refererPath: String? = null,
    ): WebsiteHttpPage {
        if (!path.startsWith('/') || path.startsWith("//") || path.contains('\r') || path.contains('\n')) {
            websiteFailure("官网请求地址无效")
        }
        val request = Request.Builder()
            .url(BASE + path)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header("User-Agent", userAgent)
            .apply {
                cookieHeader?.let { header("Cookie", it) }
                refererPath?.let { header("Referer", BASE + it) }
            }
            .build()
        return isolatedWebClient.newCall(request).execute().use { response ->
            WebsiteHttpPage(
                code = response.code,
                finalPath = response.request.url.encodedPath,
                finalUrl = response.request.url.toString(),
                html = response.body.string(),
            )
        }
    }

    /** 和 [executeWebsiteGet] 同一条隔离链路，只是换成 POST；重定向后的落点同样在返回值里。 */
    private fun executeWebsitePost(
        path: String,
        cookieHeader: String,
        userAgent: String,
        body: RequestBody,
        refererPath: String? = null,
    ): WebsiteHttpPage {
        if (!path.startsWith('/') || path.startsWith("//") || path.contains('\r') || path.contains('\n')) {
            websiteFailure("官网请求地址无效")
        }
        val request = Request.Builder()
            .url(BASE + path)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .header("User-Agent", userAgent)
            .header("Cookie", cookieHeader)
            .apply { refererPath?.let { header("Referer", BASE + it) } }
            .post(body)
            .build()
        return isolatedWebClient.newCall(request).execute().use { response ->
            WebsiteHttpPage(
                code = response.code,
                finalPath = response.request.url.encodedPath,
                finalUrl = response.request.url.toString(),
                html = response.body.string(),
            )
        }
    }

    private fun validateWebsitePage(
        response: WebsiteHttpPage,
        operation: String,
        expectedPath: String? = null,
    ) {
        if (response.finalPath == "/signin" || response.finalPath.startsWith("/2fa") || response.code == 401) {
            websiteFailure("网页会话已失效，请重新登录 V2EX")
        }
        if (response.code >= 500) {
            websiteFailure("V2EX 服务暂时不可用（HTTP ${response.code}），请稍后重试")
        }
        if (response.code !in 200..299) {
            websiteFailure("$operation 失败（HTTP ${response.code}），请稍后重试")
        }
        if (expectedPath != null && response.finalPath != expectedPath) {
            websiteFailure("$operation 返回了异常页面，请稍后重试")
        }
    }

    private fun validCookie(cookieHeader: String): String {
        if (cookieHeader.isBlank()) websiteFailure("网页会话已失效，请重新登录 V2EX")
        if (cookieHeader.contains('\r') || cookieHeader.contains('\n')) websiteFailure("网页登录信息无效")
        return cookieHeader
    }

    private fun validWebsiteName(value: String, message: String): String {
        val name = value.trim()
        if (!WEBSITE_NAME.matches(name)) websiteFailure(message)
        return name
    }

    private fun validExpectedUsername(value: String): String =
        validWebsiteName(value, "通知账号无效，请重新登录")

    private suspend fun <T> websiteResult(
        fallbackMessage: String,
        block: suspend () -> T,
    ): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: WebsiteFlowException) {
        Result.failure(error)
    } catch (_: Throwable) {
        // Do not propagate transport exception strings: some stacks include request metadata.
        Result.failure(WebsiteFlowException(fallbackMessage))
    }

    private fun websiteFailure(message: String): Nothing = throw WebsiteFlowException(message)

    private companion object {
        val WEBSITE_NAME = Regex("""^[A-Za-z0-9_]+$""")
        val WEBSITE_NODE_NAME = Regex("""^[A-Za-z0-9_-]+$""")
        const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/18.5 Mobile/15E148 Safari/604.1"
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/18.5 Safari/605.1.15"
    }
}
