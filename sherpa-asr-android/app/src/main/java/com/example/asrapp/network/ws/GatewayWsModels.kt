package com.example.asrapp.network.ws

data class GatewaySessionStartRequest(
    val type: String = "session.start",
    val sessionId: String,
    val tenantId: String,
    val sourceLang: String,
    val targetLang: String,
    val userId: String,
    val traceId: String? = null
)

data class GatewayAudioFrameRequest(
    val type: String = "audio.frame",
    val sessionId: String,
    val seq: Long,
    val codec: String = "pcm16le",
    val sampleRate: Int = 16000,
    val audioBase64: String
)

data class GatewaySessionStopRequest(
    val type: String = "session.stop",
    val sessionId: String,
    val traceId: String? = null,
    val reason: String? = null
)

data class GatewayCommandConfirmRequest(
    val type: String = "command.confirm",
    val sessionId: String,
    val seq: Long,
    val confirmToken: String,
    val accept: Boolean,
    val traceId: String? = null,
    val executionId: String? = null
)

data class GatewayCommandExecuteResultRequest(
    val type: String = "command.execute.result",
    val sessionId: String,
    val seq: Long,
    val executionId: String,
    val status: String,
    val code: String,
    val replyText: String,
    val retryable: Boolean,
    val traceId: String? = null
)

data class GatewaySubtitlePartialEvent(
    val sessionId: String,
    val seq: Long,
    val text: String
)

data class GatewaySubtitleFinalEvent(
    val sessionId: String,
    val seq: Long,
    val text: String
)

data class GatewayCommandDispatchEvent(
    val sessionId: String,
    val seq: Long,
    val executionId: String,
    val commandText: String,
    val intent: String,
    val subIntent: String,
    val confirmRequired: Boolean,
    val confirmToken: String,
    val expiresInSec: Int,
    val traceId: String
)

data class GatewayCommandResultEvent(
    val sessionId: String,
    val seq: Long,
    val executionId: String,
    val status: String,
    val code: String,
    val replyText: String,
    val retryable: Boolean,
    val confirmToken: String,
    val expiresInSec: Int
)

data class GatewayTtsReadyEvent(
    val sessionId: String,
    val seq: Long,
    val playbackUrl: String,
    val codec: String,
    val sampleRate: Int,
    val durationMs: Long,
    val cacheKey: String
)

data class GatewaySessionErrorEvent(
    val sessionId: String,
    val code: String,
    val message: String
)

data class GatewaySessionClosedEvent(
    val sessionId: String,
    val reason: String
)
