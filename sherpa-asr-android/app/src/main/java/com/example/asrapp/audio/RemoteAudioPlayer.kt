package com.example.asrapp.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer

class RemoteAudioPlayer(
    context: Context,
    private val onPlaybackStateChanged: (Boolean) -> Unit,
    private val onError: (String) -> Unit
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .build()

    @Volatile
    private var player: MediaPlayer? = null

    fun play(playbackUrl: String) {
        stop()
        if (playbackUrl.isBlank()) {
            onError("empty playback url")
            return
        }
        val focusResult = audioManager.requestAudioFocus(audioFocusRequest)
        if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            onError("unable to gain audio focus")
            return
        }

        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        mediaPlayer.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        mediaPlayer.setOnPreparedListener {
            onPlaybackStateChanged(true)
            it.start()
        }
        mediaPlayer.setOnCompletionListener {
            releasePlayer(it)
            onPlaybackStateChanged(false)
        }
        mediaPlayer.setOnErrorListener { mp, _what, _extra ->
            releasePlayer(mp)
            onPlaybackStateChanged(false)
            onError("remote audio playback failed")
            true
        }
        runCatching {
            mediaPlayer.setDataSource(playbackUrl)
            mediaPlayer.prepareAsync()
        }.onFailure { err ->
            releasePlayer(mediaPlayer)
            onPlaybackStateChanged(false)
            onError(err.message ?: "remote playback init failed")
        }
    }

    fun stop() {
        player?.let {
            runCatching {
                if (it.isPlaying) {
                    it.stop()
                }
            }
            releasePlayer(it)
        }
        onPlaybackStateChanged(false)
    }

    fun release() {
        stop()
    }

    private fun releasePlayer(mediaPlayer: MediaPlayer) {
        runCatching {
            mediaPlayer.reset()
            mediaPlayer.release()
        }
        if (player === mediaPlayer) {
            player = null
        }
        audioManager.abandonAudioFocusRequest(audioFocusRequest)
    }
}
