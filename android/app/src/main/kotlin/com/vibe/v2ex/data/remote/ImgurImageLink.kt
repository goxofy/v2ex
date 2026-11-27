package com.vibe.v2ex.data.remote

import java.net.URI

/** An original image file, not an Imgur page or album. */
object ImgurImageLink {
    private val imagePath = Regex("/[A-Za-z0-9]+\\.(?:jpe?g|png|gif|webp)", RegexOption.IGNORE_CASE)

    fun parse(raw: String): String? {
        val value = raw.trim()
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        return value.takeIf {
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host.equals("i.imgur.com", ignoreCase = true) &&
                uri.rawUserInfo == null && uri.port == -1 &&
                uri.rawQuery == null && uri.rawFragment == null &&
                imagePath.matches(uri.rawPath.orEmpty())
        }
    }
}
