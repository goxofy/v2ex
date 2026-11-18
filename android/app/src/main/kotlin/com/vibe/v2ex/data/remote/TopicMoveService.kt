package com.vibe.v2ex.data.remote

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

internal data class MovePreparation(val topic: MoveTopic, val form: MoveForm, val nodes: List<MoveNode>)

internal class TopicMoveService(
    client: OkHttpClient,
    private val base: HttpUrl = "https://www.v2ex.com/".toHttpUrl(),
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .cookieJar(CookieJar.NO_COOKIES).cache(null).callTimeout(15, TimeUnit.SECONDS)
        .also { it.interceptors().clear(); it.networkInterceptors().clear() }.build()
    private val confirmation = http.newBuilder().callTimeout(5, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()

    suspend fun permission(id: Long, cookie: String): MoveTopic = safe {
        validate(id, cookie)
        TopicMovePage(get("/t/$id", cookie).body, base).topic(id)
    }

    suspend fun prepare(id: Long, cookie: String): MovePreparation = safe {
        val topic = permission(id, cookie)
        if (!topic.canMove) throw MoveFailure(MoveFailure.Kind.DENIED)
        val form = TopicMovePage(get("/move/topic/$id", cookie).body, base).form(id)
        val json = get("/api/nodes/list.json?fields=name,title,topics,aliases&sort_by=topics&reverse=1&_=${System.nanoTime()}", cookie)
        val values = Json.parseToJsonElement(json.body) as? JsonArray ?: throw MoveFailure(MoveFailure.Kind.UNAVAILABLE)
        val nodes = values.mapNotNull { value ->
            val obj = value as? JsonObject ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (!SLUG.matches(name) || title.isBlank()) return@mapNotNull null
            val aliases = when (val raw = obj["aliases"]) {
                is JsonArray -> raw.mapNotNull { it.jsonPrimitive.contentOrNull }
                else -> listOfNotNull(raw?.jsonPrimitive?.contentOrNull)
            }
            MoveNode(name, title, aliases)
        }.distinctBy { it.name }
        if (nodes.isEmpty()) throw MoveFailure(MoveFailure.Kind.UNAVAILABLE)
        MovePreparation(topic, form, (nodes + form.shortcuts).distinctBy { it.name })
    }

    suspend fun move(
        id: Long, cookie: String, expectedNode: String, destination: MoveNode, memo: String,
        sessionCurrent: () -> Boolean,
    ): MoveNode {
        var submitted = false
        try {
            validate(id, cookie)
            if (!SLUG.matches(destination.name) || memo.length > 2000) throw MoveFailure(MoveFailure.Kind.UNAVAILABLE)
            if (!sessionCurrent()) throw MoveFailure(MoveFailure.Kind.SESSION)
            val fresh = permission(id, cookie)
            if (!fresh.canMove) throw MoveFailure(MoveFailure.Kind.DENIED)
            if (fresh.node.name != expectedNode) throw MoveFailure(MoveFailure.Kind.CHANGED)
            if (fresh.node.name == destination.name) return fresh.node
            val form = TopicMovePage(get("/move/topic/$id", cookie).body, base).form(id)
            if (!sessionCurrent()) throw MoveFailure(MoveFailure.Kind.SESSION)
            val body = FormBody.Builder().add("destination", destination.name).add("memo", memo)
                .apply { form.hidden.forEach { (key, value) -> add(key, value) } }.build()
            submitted = true
            val response = try {
                execute(request(base.resolve("/move/topic/$id")!!, cookie)
                    .post(OneShotBody(body)).build(), http)
            } catch (error: IOException) { null }
            if (response != null) {
                if (response.code in 400..499 && response.code != 408) {
                    throw MoveFailure(if (response.code in listOf(401, 403)) MoveFailure.Kind.DENIED else MoveFailure.Kind.REJECTED)
                }
                if (response.code in 300..399) {
                    val target = response.location?.let { base.resolve(it) }
                    if (target == null || !allowed(target, "/t/$id")) throw MoveFailure(MoveFailure.Kind.UNCONFIRMED)
                }
                if (response.code == 200) TopicMovePage(response.body, base).checkPage()
            }
            repeat(3) { attempt ->
                if (attempt > 0) wait(attempt * 1000L)
                if (!sessionCurrent()) throw MoveFailure(MoveFailure.Kind.UNCONFIRMED)
                try {
                    val topic = kotlinx.coroutines.withTimeoutOrNull(5000) {
                        TopicMovePage(get("/t/$id", cookie, confirmation).body, base).topic(id)
                    }
                    if (topic != null && topic.node.name == destination.name && sessionCurrent()) return topic.node
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { /* Only GET confirmation may be retried. */ }
            }
            throw MoveFailure(MoveFailure.Kind.UNCONFIRMED)
        } catch (error: CancellationException) { throw error }
        catch (error: MoveFailure) { throw error }
        catch (_: Exception) {
            throw MoveFailure(if (submitted) MoveFailure.Kind.UNCONFIRMED else MoveFailure.Kind.UNAVAILABLE)
        }
    }

    private suspend fun <T> safe(block: suspend () -> T): T = try { block() }
        catch (error: CancellationException) { throw error }
        catch (error: MoveFailure) { throw error }
        catch (_: Exception) { throw MoveFailure(MoveFailure.Kind.UNAVAILABLE) }

    private fun validate(id: Long, cookie: String): Unit {
        if (id <= 0 || cookie.isBlank() || cookie.any { it.code !in 32..126 }) throw MoveFailure(MoveFailure.Kind.SESSION)
    }

    private suspend fun get(path: String, cookie: String, client: OkHttpClient = http): Page {
        var url = base.resolve(path) ?: throw MoveFailure(MoveFailure.Kind.UNAVAILABLE)
        val expectedPath = url.encodedPath
        repeat(4) {
            val page = execute(request(url, cookie).get().build(), client)
            if (page.code == 200) return page
            if (page.code in listOf(401, 403)) throw MoveFailure(MoveFailure.Kind.DENIED)
            if (page.code !in listOf(301, 302, 303, 307, 308)) throw MoveFailure(MoveFailure.Kind.UNAVAILABLE)
            val target = page.location?.let { url.resolve(it) } ?: throw MoveFailure(MoveFailure.Kind.UNAVAILABLE)
            if (target.encodedPath in listOf("/signin", "/2fa")) throw MoveFailure(MoveFailure.Kind.SESSION)
            if (!allowed(target, expectedPath)) throw MoveFailure(MoveFailure.Kind.UNAVAILABLE)
            url = target
        }
        throw MoveFailure(MoveFailure.Kind.UNAVAILABLE)
    }

    private fun allowed(url: HttpUrl, path: String): Boolean = url.sameOrigin(base) && url.encodedPath == path &&
        url.username.isEmpty() && url.password.isEmpty() && url.fragment == null &&
        (url.query == null || path.startsWith("/api/"))

    private fun request(url: HttpUrl, cookie: String): Request.Builder = Request.Builder().url(url)
        .header("Cookie", cookie).header("Origin", base.toString().trimEnd('/'))
        .header("Referer", url.toString()).header("User-Agent", "Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Mobile/15E148 Safari/604.1")
        .header("Cache-Control", "no-cache, no-store").header("Pragma", "no-cache")

    private data class Page(val code: Int, val location: String?, val body: String)

    private suspend fun execute(request: Request, client: OkHttpClient): Page = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException): Unit = continuation.resumeWith(Result.failure(e))
            override fun onResponse(call: Call, response: Response): Unit {
                continuation.resumeWith(runCatching { response.use { Page(it.code, it.header("Location"), it.body.string()) } })
            }
        })
    }

    private class OneShotBody(private val form: FormBody) : RequestBody() {
        override fun contentType(): MediaType = form.contentType()
        override fun contentLength(): Long = form.contentLength()
        override fun isOneShot(): Boolean = true
        override fun writeTo(sink: BufferedSink): Unit = form.writeTo(sink)
    }

    private companion object { val SLUG = Regex("[A-Za-z0-9_]+") }
}
