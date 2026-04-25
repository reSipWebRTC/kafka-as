package com.example.asrapp.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log

class RemoteTtsPlayer(
    private val onPlaybackStateChanged: (Boolean) -> Unit,
    private val onError: (String) -> Unit
) {
    private val tag = "RemoteTtsPlayer"

    @Volatile
    private var mediaPlayer: MediaPlayer? = null

    @Volatile
    private var playing: Boolean = false

    fun isPlaying(): Boolean = playing

    fun play(url: String): Boolean {
        if (url.isBlank()) {
            return false
        }
        stop(notifyState = false)

        return runCatching {
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
                    it.start()
                }
                setOnCompletionListener {
                    releaseInternal(notifyState = true)
                }
                setOnErrorListener { _, what, extra ->
                    val message = "Remote playback failed: what=$what, extra=$extra"
                    Log.e(tag, message)
                    onError(message)
                    releaseInternal(notifyState = true)
                    true
                }
                prepareAsync()
            }
            mediaPlayer = player
            true
        }.getOrElse { error ->
            onError(error.message ?: "Remote playback init failed")
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
        if (notifyState && wasPlaying) {
            onPlaybackStateChanged(false)
        }
    }
}
