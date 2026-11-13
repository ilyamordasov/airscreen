package com.airtv.receiver.link

import java.net.URI

/** Public media URL that the TV can fetch independently of Safari. */
object MediaCandidate {
    fun parse(input: String): String? {
        val url = SharedPage.parse(input) ?: return null
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
        return url.takeIf { path.endsWith(".m3u8") || path.endsWith(".mp4") }
    }
}
