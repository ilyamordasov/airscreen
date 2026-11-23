package com.airtv.receiver.vpn

import com.airtv.receiver.link.TvCrateResolver
import com.airtv.receiver.link.TvCrateTransport
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/** Measures the channel page response and requires a playable HLS media playlist on the same proxy. */
internal fun checkTvCrate(
    transport: TvCrateTransport,
    nanoTime: () -> Long = System::nanoTime,
    nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
): Long {
    var latency = -1L
    val timed = TvCrateTransport { url, headers ->
        val start = nanoTime()
        transport.get(url, headers).also {
            if (latency < 0) latency = TimeUnit.NANOSECONDS.toMillis(nanoTime() - start).coerceAtLeast(0)
        }
    }
    val stream = TvCrateResolver(timed, nowSeconds).resolve(TvCrateResolver.MATCH_PREMIER_URL)
    var playlistUrl = stream.mediaUrl
    repeat(3) {
        val response = transport.get(playlistUrl, stream.requestHeaders)
        val lines = response.body.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (response.statusCode !in 200..299 || lines.firstOrNull() != "#EXTM3U") {
            throw IOException("HLS playlist unavailable")
        }
        val media = lines.firstOrNull { !it.startsWith('#') } ?: throw IOException("Empty HLS playlist")
        if (lines.any { it.startsWith("#EXTINF:") }) return latency
        playlistUrl = URI(playlistUrl).resolve(media).toString()
    }
    throw IOException("HLS media playlist unavailable")
}
