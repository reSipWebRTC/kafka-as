package com.example.asrapp.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log

class RemoteTtsPlayer(
    private val onPlaybackStateChanged: (Boolean) -> Unit,
    private val onPlaybackStarted: (Long) -> Unit,
    private val onPlaybackStall: (Long, Long) -> Unit,
    private val onPlaybackCompleted: (Long, Int, Long) -> Unit,
    private val onError: (Long?, String) -> Unit
) {
    private val tag = "RemoteTtsPlayer"

    @Volatile
    private var mediaPlayer: MediaPlayer? = null

    @Volatile
    private var playing: Boolean = false

    @Volatile
    private var activeSeq: Long? = null

    @Volatile
    private var bufferingStartedAtMs: Long? = null

    @Volatile
    private var stallCount: Int = 0

    @Volatile
    private var totalStallDurationMs: Long = 0

    fun isPlaying(): Boolean = playing

    fun play(url: String, seq: Long): Boolean {
        if (url.isBlank()) {
            return false
        }
        stop(notifyState = false)

        return runCatching {
            activeSeq = seq
            bufferingStartedAtMs = null
            stallCount = 0
            totalStallDurationMs = 0
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(url)
                setOnPreparedListener {
                    playing = true
                    onPlaybackStateChanged(true)
                    onPlaybackStarted(seq)
                    it.start()
                }
                setOnInfoListener { _, what, _ ->
                    when (what) {
                        MediaPlayer.MEDIA_INFO_BUFFERING_START -> {
                            if (bufferingStartedAtMs == null) {
                                bufferingStartedAtMs = System.currentTimeMillis()
                            }
                        }

                        MediaPlayer.MEDIA_INFO_BUFFERING_END -> {
                            val startAt = bufferingStartedAtMs
                            if (startAt != null) {
                                val durationMs = (System.currentTimeMillis() - startAt).coerceAtLeast(0L)
                                bufferingStartedAtMs = null
                                stallCount += 1
                                totalStallDurationMs += durationMs
                                onPlaybackStall(seq, durationMs)
                            }
                        }
                    }
                    false
                }
                setOnCompletionListener {
                    onPlaybackCompleted(seq, stallCount, totalStallDurationMs)
                    releaseInternal(notifyState = true)
                }
                setOnErrorListener { _, what, extra ->
                    val message = "Remote playback failed: what=$what, extra=$extra"
                    Log.e(tag, message)
                    onError(seq, message)
                    releaseInternal(notifyState = true)
                    true
                }
                prepareAsync()
            }
            mediaPlayer = player
            true
        }.getOrElse { error ->
            onError(activeSeq, error.message ?: "Remote playback init failed")
            releaseInternal(notifyState = true)
            false
        }
    }

    fun stop(notifyState: Boolean = true) {
        mediaPlayer?.runCatching {
            stop()
        }
        releaseInternal(notifyState = notifyState)
    }

    fun release() {
        stop(notifyState = false)
    }

    private fun releaseInternal(notifyState: Boolean) {
        mediaPlayer?.runCatching {
            reset()
            release()
        }
        mediaPlayer = null
        val wasPlaying = playing
        playing = false
        activeSeq = null
        bufferingStartedAtMs = null
        stallCount = 0
        totalStallDurationMs = 0
        if (notifyState && wasPlaying) {
            onPlaybackStateChanged(false)
        }
    }
}
