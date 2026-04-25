package com.example.asrapp.gateway

import android.util.Base64
import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

data class GatewayConnectionConfig(
    val wsUrl: String,
    val accessToken: String? = null
)

class SpeechGatewayWsClient(
    private val configProvider: () -> GatewayConnectionConfig,
    private val listener: Listener
) {
    interface Listener {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onFailure(message: String)
        fun onSessionError(message: WsSessionError)
        fun onSubtitlePartial(message: WsSubtitlePartial)
        fun onSubtitleFinal(message: WsSubtitleFinal)
        fun onCommandResult(message: WsCommandResult)
        fun onTtsReady(message: WsTtsReady)
        fun onSessionClosed(message: WsSessionClosed)
    }

    private val tag = "SpeechGatewayWsClient"
    private val lock = Any()
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val sessionStartAdapter = moshi.adapter(WsSessionStart::class.java)
    private val audioFrameAdapter = moshi.adapter(WsAudioFrame::class.java)
    private val sessionStopAdapter = moshi.adapter(WsSessionStop::class.java)
    private val commandConfirmAdapter = moshi.adapter(WsCommandConfirm::class.java)
    private val playbackMetricAdapter = moshi.adapter(WsPlaybackMetric::class.java)

    private val httpClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var connected: Boolean = false

    fun isConnected(): Boolean = connected

    fun connect() {
        synchronized(lock) {
            if (webSocket != null) {
                return
            }

            val config = configProvider()
            if (config.wsUrl.isBlank()) {
                listener.onFailure("Gateway wsUrl is blank")
                return
            }

            val requestBuilder = Request.Builder()
                .url(config.wsUrl)

            val token = config.accessToken?.trim().orEmpty()
            if (token.isNotEmpty()) {
                requestBuilder.header("Authorization", "Bearer $token")
            }

            webSocket = httpClient.newWebSocket(requestBuilder.build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    connected = true
                    listener.onConnected()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    dispatchDownlink(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    clearSocket()
                    listener.onDisconnected("closed($code): $reason")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    clearSocket()
                    val message = t.message ?: "websocket failure"
                    listener.onFailure(message)
                }
            })
        }
    }

    fun disconnect(reason: String = "client-close") {
        synchronized(lock) {
            webSocket?.close(1000, reason)
            clearSocket()
        }
    }

    fun sendSessionStart(message: WsSessionStart): Boolean {
        return sendJson(sessionStartAdapter.toJson(message))
    }

    fun sendAudioFrame(
        sessionId: String,
        seq: Long,
        codec: String,
        sampleRate: Int,
        audioBytes: ByteArray
    ): Boolean {
        val payload = WsAudioFrame(
            sessionId = sessionId,
            seq = seq,
            codec = codec,
            sampleRate = sampleRate,
            audioBase64 = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
        )
        return sendJson(audioFrameAdapter.toJson(payload))
    }

    fun sendSessionStop(message: WsSessionStop): Boolean {
        return sendJson(sessionStopAdapter.toJson(message))
    }

    fun sendCommandConfirm(message: WsCommandConfirm): Boolean {
        return sendJson(commandConfirmAdapter.toJson(message))
    }

    fun sendPlaybackMetric(message: WsPlaybackMetric): Boolean {
        return sendJson(playbackMetricAdapter.toJson(message))
    }

    private fun sendJson(payload: String): Boolean {
        val socket = webSocket
        if (!connected || socket == null) {
            return false
        }
        return socket.send(payload)
    }

    private fun dispatchDownlink(raw: String) {
        val parsed = runCatching { GatewayMessageParser.parse(raw) }
            .onFailure {
                Log.e(tag, "Failed to parse downlink: ${it.message}", it)
            }
            .getOrNull()
            ?: return

        when (parsed) {
            is WsSessionError -> listener.onSessionError(parsed)
            is WsSubtitlePartial -> listener.onSubtitlePartial(parsed)
            is WsSubtitleFinal -> listener.onSubtitleFinal(parsed)
            is WsCommandResult -> listener.onCommandResult(parsed)
            is WsTtsReady -> listener.onTtsReady(parsed)
            is WsSessionClosed -> listener.onSessionClosed(parsed)
            is WsUnknownDownlink -> {
                Log.d(tag, "Ignored downlink type=${parsed.type}")
            }
        }
    }

    private fun clearSocket() {
        connected = false
        webSocket = null
    }
}
