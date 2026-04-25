package com.example.asrapp.gateway

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

object GatewayMessageTypes {
    const val SESSION_START = "session.start"
    const val AUDIO_FRAME = "audio.frame"
    const val SESSION_STOP = "session.stop"
    const val COMMAND_CONFIRM = "command.confirm"
    const val PLAYBACK_METRIC = "playback.metric"

    const val SESSION_ERROR = "session.error"
    const val SUBTITLE_PARTIAL = "subtitle.partial"
    const val SUBTITLE_FINAL = "subtitle.final"
    const val TTS_READY = "tts.ready"
    const val COMMAND_RESULT = "command.result"
    const val SESSION_CLOSED = "session.closed"
}

@JsonClass(generateAdapter = true)
data class WsSessionStart(
    val type: String = GatewayMessageTypes.SESSION_START,
    val sessionId: String,
    val tenantId: String,
    val userId: String,
    val sourceLang: String,
    val targetLang: String,
    val traceId: String? = null
)

@JsonClass(generateAdapter = true)
data class WsAudioFrame(
    val type: String = GatewayMessageTypes.AUDIO_FRAME,
    val sessionId: String,
    val seq: Long,
    val codec: String,
    val sampleRate: Int,
    val audioBase64: String
)

@JsonClass(generateAdapter = true)
data class WsSessionStop(
    val type: String = GatewayMessageTypes.SESSION_STOP,
    val sessionId: String,
    val reason: String? = null,
    val traceId: String? = null
)

@JsonClass(generateAdapter = true)
data class WsCommandConfirm(
    val type: String = GatewayMessageTypes.COMMAND_CONFIRM,
    val sessionId: String,
    val seq: Long,
    val confirmToken: String,
    val accept: Boolean,
    val traceId: String? = null
)

@JsonClass(generateAdapter = true)
data class WsPlaybackMetric(
    val type: String = GatewayMessageTypes.PLAYBACK_METRIC,
    val sessionId: String,
    val seq: Long,
    val stage: String,
    val source: String,
    val durationMs: Long? = null,
    val stallCount: Int? = null,
    val reason: String? = null,
    val traceId: String? = null
)

sealed interface GatewayDownlinkMessage {
    val type: String
    val sessionId: String
}

@JsonClass(generateAdapter = true)
data class WsSessionError(
    override val type: String = GatewayMessageTypes.SESSION_ERROR,
    override val sessionId: String,
    val code: String,
    val message: String
) : GatewayDownlinkMessage

@JsonClass(generateAdapter = true)
data class WsSubtitlePartial(
    override val type: String = GatewayMessageTypes.SUBTITLE_PARTIAL,
    override val sessionId: String,
    val seq: Long,
    val text: String
) : GatewayDownlinkMessage

@JsonClass(generateAdapter = true)
data class WsSubtitleFinal(
    override val type: String = GatewayMessageTypes.SUBTITLE_FINAL,
    override val sessionId: String,
    val seq: Long,
    val text: String
) : GatewayDownlinkMessage

@JsonClass(generateAdapter = true)
data class WsTtsReady(
    override val type: String = GatewayMessageTypes.TTS_READY,
    override val sessionId: String,
    val seq: Long,
    val playbackUrl: String,
    val codec: String,
    val sampleRate: Int,
    val durationMs: Long,
    val cacheKey: String
) : GatewayDownlinkMessage

@JsonClass(generateAdapter = true)
data class WsCommandResult(
    override val type: String = GatewayMessageTypes.COMMAND_RESULT,
    override val sessionId: String,
    val seq: Long,
    val status: String,
    val code: String,
    val replyText: String,
    val retryable: Boolean,
    val confirmToken: String? = null,
    val expiresInSec: Long? = null
) : GatewayDownlinkMessage

@JsonClass(generateAdapter = true)
data class WsSessionClosed(
    override val type: String = GatewayMessageTypes.SESSION_CLOSED,
    override val sessionId: String,
    val reason: String
) : GatewayDownlinkMessage

data class WsUnknownDownlink(
    override val type: String,
    override val sessionId: String
) : GatewayDownlinkMessage

object GatewayMessageParser {
    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val envelopeAdapter = moshi.adapter(Map::class.java)
    private val sessionErrorAdapter = moshi.adapter(WsSessionError::class.java)
    private val subtitlePartialAdapter = moshi.adapter(WsSubtitlePartial::class.java)
    private val subtitleFinalAdapter = moshi.adapter(WsSubtitleFinal::class.java)
    private val ttsReadyAdapter = moshi.adapter(WsTtsReady::class.java)
    private val commandResultAdapter = moshi.adapter(WsCommandResult::class.java)
    private val sessionClosedAdapter = moshi.adapter(WsSessionClosed::class.java)

    fun parse(raw: String): GatewayDownlinkMessage? {
        val envelope = envelopeAdapter.fromJson(raw) ?: return null
        val type = envelope["type"]?.toString().orEmpty()
        val sessionId = envelope["sessionId"]?.toString().orEmpty()

        return when (type) {
            GatewayMessageTypes.SESSION_ERROR -> sessionErrorAdapter.fromJson(raw)
            GatewayMessageTypes.SUBTITLE_PARTIAL -> subtitlePartialAdapter.fromJson(raw)
            GatewayMessageTypes.SUBTITLE_FINAL -> subtitleFinalAdapter.fromJson(raw)
            GatewayMessageTypes.TTS_READY -> ttsReadyAdapter.fromJson(raw)
            GatewayMessageTypes.COMMAND_RESULT -> commandResultAdapter.fromJson(raw)
            GatewayMessageTypes.SESSION_CLOSED -> sessionClosedAdapter.fromJson(raw)
            else -> WsUnknownDownlink(type = type, sessionId = sessionId)
        }
    }
}
