package com.example.asrapp.network.ws

import android.util.Log
import com.example.asrapp.network.NetworkConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject

class GatewayWsClient(
    private val config: NetworkConfig,
    private val listener: Listener
) {
    interface Listener {
        fun onConnected(sessionId: String)
        fun onSubtitlePartial(event: GatewaySubtitlePartialEvent)
        fun onSubtitleFinal(event: GatewaySubtitleFinalEvent)
        fun onCommandDispatch(event: GatewayCommandDispatchEvent)
        fun onCommandResult(event: GatewayCommandResultEvent)
        fun onTtsReady(event: GatewayTtsReadyEvent)
        fun onSessionError(event: GatewaySessionErrorEvent)
        fun onSessionClosed(event: GatewaySessionClosedEvent)
        fun onDisconnected(sessionId: String, reason: String)
        fun onFailure(sessionId: String, message: String, throwable: Throwable? = null)
    }

    private val tag = "GatewayWsClient"

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val sessionStartAdapter = moshi.adapter(GatewaySessionStartRequest::class.java)
    private val audioFrameAdapter = moshi.adapter(GatewayAudioFrameRequest::class.java)
    private val sessionStopAdapter = moshi.adapter(GatewaySessionStopRequest::class.java)
    private val commandConfirmAdapter = moshi.adapter(GatewayCommandConfirmRequest::class.java)
    private val executeResultAdapter = moshi.adapter(GatewayCommandExecuteResultRequest::class.java)

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var sessionId: String = ""

    @Volatile
    private var connected = false

    fun connect(sessionId: String, traceId: String? = null) {
        close("reconnect")
        this.sessionId = sessionId
        connected = false

        val request = Request.Builder()
            .url(config.getGatewayWsEndpoint())
            .build()

        webSocket = okHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    connected = true
                    val start = GatewaySessionStartRequest(
                        sessionId = sessionId,
                        tenantId = config.tenantId,
                        sourceLang = config.sourceLang,
                        targetLang = config.targetLang,
                        userId = config.userId,
                        traceId = traceId
                    )
                    if (!sendJson(sessionStartAdapter.toJson(start))) {
                        listener.onFailure(
                            sessionId = sessionId,
                            message = "failed to send session.start after ws opened"
                        )
                        return
                    }
                    listener.onConnected(sessionId)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleIncomingMessage(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    listener.onFailure(
                        sessionId = sessionId,
                        message = "unexpected binary websocket frame"
                    )
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    connected = false
                    listener.onDisconnected(sessionId, reason.ifBlank { "server closing ($code)" })
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    connected = false
                    listener.onDisconnected(sessionId, reason.ifBlank { "closed ($code)" })
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    connected = false
                    listener.onFailure(
                        sessionId = sessionId,
                        message = "ws failure: ${t.message ?: "unknown"}",
                        throwable = t
                    )
                }
            }
        )
    }

    fun sendAudioFrame(seq: Long, audioBase64: String): Boolean {
        if (!connected || sessionId.isBlank()) {
            return false
        }
        val payload = GatewayAudioFrameRequest(
            sessionId = sessionId,
            seq = seq,
            audioBase64 = audioBase64
        )
        return sendJson(audioFrameAdapter.toJson(payload))
    }

    fun sendSessionStop(seq: Long, traceId: String? = null, reason: String? = null): Boolean {
        if (sessionId.isBlank()) {
            return false
        }
        val payload = GatewaySessionStopRequest(
            sessionId = sessionId,
            traceId = traceId,
            reason = reason
        )
        // seq is reserved for tracing call ordering in caller side.
        return sendJson(sessionStopAdapter.toJson(payload))
    }

    fun sendCommandConfirm(
        seq: Long,
        confirmToken: String,
        accept: Boolean,
        traceId: String? = null,
        executionId: String? = null
    ): Boolean {
        if (!connected || sessionId.isBlank()) {
            return false
        }
        val payload = GatewayCommandConfirmRequest(
            sessionId = sessionId,
            seq = seq,
            confirmToken = confirmToken,
            accept = accept,
            traceId = traceId,
            executionId = executionId
        )
        return sendJson(commandConfirmAdapter.toJson(payload))
    }

    fun sendCommandExecuteResult(
        seq: Long,
        executionId: String,
        status: String,
        code: String,
        replyText: String,
        retryable: Boolean,
        traceId: String? = null
    ): Boolean {
        if (!connected || sessionId.isBlank()) {
            return false
        }
        val payload = GatewayCommandExecuteResultRequest(
            sessionId = sessionId,
            seq = seq,
            executionId = executionId,
            status = status,
            code = code,
            replyText = replyText,
            retryable = retryable,
            traceId = traceId
        )
        return sendJson(executeResultAdapter.toJson(payload))
    }

    fun close(reason: String = "client.close") {
        connected = false
        webSocket?.close(1000, reason)
        webSocket = null
    }

    private fun sendJson(json: String): Boolean {
        val socket = webSocket ?: return false
        if (json.isBlank()) {
            return false
        }
        val sent = socket.send(json)
        if (!sent) {
            Log.w(tag, "sendJson failed: $json")
        }
        return sent
    }

    private fun handleIncomingMessage(raw: String) {
        runCatching {
            val node = JSONObject(raw)
            val type = node.optString("type")
            when (type) {
                "subtitle.partial" -> {
                    listener.onSubtitlePartial(
                        GatewaySubtitlePartialEvent(
                            sessionId = node.optString("sessionId"),
                            seq = node.optLong("seq", 0L),
                            text = node.optString("text")
                        )
                    )
                }

                "subtitle.final" -> {
                    listener.onSubtitleFinal(
                        GatewaySubtitleFinalEvent(
                            sessionId = node.optString("sessionId"),
                            seq = node.optLong("seq", 0L),
                            text = node.optString("text")
                        )
                    )
                }

                "command.dispatch" -> {
                    listener.onCommandDispatch(
                        GatewayCommandDispatchEvent(
                            sessionId = node.optString("sessionId"),
                            seq = node.optLong("seq", 0L),
                            executionId = node.optString("executionId"),
                            commandText = node.optString("commandText"),
                            intent = node.optString("intent"),
                            subIntent = node.optString("subIntent"),
                            confirmRequired = node.optBoolean("confirmRequired", false),
                            confirmToken = node.optString("confirmToken"),
                            expiresInSec = node.optInt("expiresInSec", 0),
                            traceId = node.optString("traceId")
                        )
                    )
                }

                "command.result" -> {
                    listener.onCommandResult(
                        GatewayCommandResultEvent(
                            sessionId = node.optString("sessionId"),
                            seq = node.optLong("seq", 0L),
                            executionId = node.optString("executionId"),
                            status = node.optString("status"),
                            code = node.optString("code"),
                            replyText = node.optString("replyText"),
                            retryable = node.optBoolean("retryable", false),
                            confirmToken = node.optString("confirmToken"),
                            expiresInSec = node.optInt("expiresInSec", 0)
                        )
                    )
                }

                "tts.ready" -> {
                    listener.onTtsReady(
                        GatewayTtsReadyEvent(
                            sessionId = node.optString("sessionId"),
                            seq = node.optLong("seq", 0L),
                            playbackUrl = node.optString("playbackUrl"),
                            codec = node.optString("codec"),
                            sampleRate = node.optInt("sampleRate", 0),
                            durationMs = node.optLong("durationMs", 0L),
                            cacheKey = node.optString("cacheKey")
                        )
                    )
                }

                "session.error" -> {
                    listener.onSessionError(
                        GatewaySessionErrorEvent(
                            sessionId = node.optString("sessionId"),
                            code = node.optString("code"),
                            message = node.optString("message")
                        )
                    )
                }

                "session.closed" -> {
                    listener.onSessionClosed(
                        GatewaySessionClosedEvent(
                            sessionId = node.optString("sessionId"),
                            reason = node.optString("reason")
                        )
                    )
                }

                else -> {
                    Log.d(tag, "skip unsupported ws downlink type=$type payload=$raw")
                }
            }
        }.onFailure { err ->
            listener.onFailure(
                sessionId = sessionId,
                message = "invalid websocket payload: ${err.message ?: "unknown"}",
                throwable = err
            )
        }
    }
}
