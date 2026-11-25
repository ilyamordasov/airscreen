package com.airtv.receiver.link

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference

@OptIn(UnstableApi::class)
internal object AceStreamProbe {
    data class Video(val height: Int?, val bitrate: Int?)
    data class Result(val video: Video, val stats: AcePlaybackSession.Stats?)

    fun measure(context: Context, session: AcePlaybackSession): Result {
        val video = AtomicReference<Video>()
        val error = AtomicReference<Exception>()
        val thread = HandlerThread("AceStreamProbe").apply { start() }
        val handler = Handler(thread.looper)
        var player: ExoPlayer? = null
        handler.post {
            try {
                player = ExoPlayer.Builder(context.applicationContext).setLooper(thread.looper).build().apply {
                    addListener(object : Player.Listener {
                        override fun onTracksChanged(tracks: Tracks) {
                            val group = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
                                ?: return
                            val index = (0 until group.length).first { group.isTrackSelected(it) }
                            val format = group.getTrackFormat(index)
                            video.set(Video(
                                format.height.takeIf { it > 0 },
                                format.averageBitrate.takeIf { it > 0 } ?: format.peakBitrate.takeIf { it > 0 },
                            ))
                        }

                        override fun onPlayerError(failure: PlaybackException) {
                            error.set(failure)
                        }
                    })
                }
                val http = DefaultHttpDataSource.Factory().setConnectTimeoutMs(2_000).setReadTimeoutMs(4_000)
                val item = MediaItem.Builder().setUri(session.manifest).setMimeType(MimeTypes.APPLICATION_M3U8).build()
                player?.setMediaSource(HlsMediaSource.Factory(http).setAllowChunklessPreparation(false).createMediaSource(item))
                player?.prepare()
            } catch (failure: Exception) {
                error.set(failure)
            }
        }

        try {
            val start = SystemClock.elapsedRealtime()
            var stats: AcePlaybackSession.Stats? = null
            while (SystemClock.elapsedRealtime() - start < 15_000) {
                error.get()?.let { throw it }
                val sample = runCatching { session.stats() }.getOrNull()
                val measuredSpeed = sample?.downloadKilobytesPerSecond
                if (stats == null || (measuredSpeed != null &&
                        (measuredSpeed > 0 || stats.downloadKilobytesPerSecond == null))) {
                    stats = sample ?: stats
                }
                val track = video.get()
                if (track?.height != null && (stats?.downloadKilobytesPerSecond ?: 0) > 0 &&
                    SystemClock.elapsedRealtime() - start >= 3_000) {
                    return Result(track, stats)
                }
                Thread.sleep(750)
            }
            error.get()?.let { throw it }
            return Result(video.get() ?: throw IOException("No video track received"), stats)
        } finally {
            handler.post {
                try {
                    player?.release()
                } finally {
                    thread.quitSafely()
                }
            }
        }
    }
}
