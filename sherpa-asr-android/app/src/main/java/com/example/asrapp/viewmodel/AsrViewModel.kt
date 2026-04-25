package com.example.asrapp.viewmodel

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.asrapp.NetworkMonitor
import com.example.asrapp.audio.MicRecorder
import com.example.asrapp.audio.RemoteTtsPlayer
import com.example.asrapp.audio.TtsPlayer
import com.example.asrapp.gateway.GatewayConnectionConfig
import com.example.asrapp.gateway.SpeechGatewayWsClient
import com.example.asrapp.gateway.WsCommandConfirm
import com.example.asrapp.gateway.WsCommandResult
import com.example.asrapp.gateway.WsPlaybackMetric
import com.example.asrapp.gateway.WsSessionClosed
import com.example.asrapp.gateway.WsSessionError
import com.example.asrapp.gateway.WsSessionStart
import com.example.asrapp.gateway.WsSessionStop
import com.example.asrapp.gateway.WsSubtitleFinal
import com.example.asrapp.gateway.WsSubtitlePartial
import com.example.asrapp.gateway.WsTtsReady
import com.example.asrapp.model.TtsModelLoader
import com.example.asrapp.network.NetworkConfig
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class PendingCommandConfirm(
    val confirmToken: String,
    val prompt: String,
    val expiresInSec: Long?,
    val commandSeq: Long
)

data class CommandExecutionResult(
    val status: String,
    val code: String,
    val replyText: String,
    val retryable: Boolean,
    val seq: Long
)

data class CommandInfo(
    val action: String,
    val device: String,
    val location: String? = null,
    val description: String = listOfNotNull(action, device, location).joinToString(" ")
)

data class PendingLocalPlaybackStart(
    val seq: Long,
    val triggerAtMs: Long,
    val reason: String
)

data class AsrUiState(
    val finalText: String = "",
    val partialText: String = "",
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val networkAvailable: Boolean = true,
    val gatewayConnected: Boolean = false,
    val gatewayConnecting: Boolean = false,
    val gatewayWsUrl: String = "",
    val sessionId: String? = null,
    val pendingConfirm: PendingCommandConfirm? = null,
    val lastCommandResult: CommandExecutionResult? = null,
    val isTtsEnabled: Boolean = false,
    val isSpeakingTts: Boolean = false,
    val isRemoteTtsPlaying: Boolean = false,
    val ttsSpeed: Float = 1.0f,
    val ttsSpeakerId: Int = 0,
    val ttsError: String? = null
)

class AsrViewModel(
    app: Application,
    private val baseConfig: NetworkConfig = NetworkConfig()
) : AndroidViewModel(app), SpeechGatewayWsClient.Listener {

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(AsrUiState())
    val state: kotlinx.coroutines.flow.StateFlow<AsrUiState> = _state

    private val preferences = getApplication<Application>().getSharedPreferences(
        "settings",
        Context.MODE_PRIVATE
    )

    private val finalBuffer = StringBuilder()
    private val outboundSeq = AtomicLong(0L)

    private var wsClient: SpeechGatewayWsClient? = null
    private var activeSession: SessionContext? = null
    private var pendingStartSession: SessionContext? = null
    private var mic: MicRecorder? = null

    private var ttsPlayer: TtsPlayer? = null
    private var ttsWarmupJob: Job? = null
    private var localTtsFallbackJob: Job? = null
    private var pendingFallbackSeq: Long? = null
    private var pendingRemotePlaybackSeq: Long? = null
    private var pendingRemotePlaybackReadyAtMs: Long? = null
    private var pendingLocalPlaybackStart: PendingLocalPlaybackStart? = null

    private val remoteTtsPlayer = RemoteTtsPlayer(
        onPlaybackStateChanged = { playing ->
            onMain {
                _state.value = _state.value.copy(isRemoteTtsPlaying = playing)
            }
        },
        onPlaybackStarted = { seq ->
            onMain {
                val readyAt = pendingRemotePlaybackReadyAtMs
                val durationMs = if (pendingRemotePlaybackSeq == seq && readyAt != null) {
                    (System.currentTimeMillis() - readyAt).coerceAtLeast(0L)
                } else {
                    null
                }
                pendingRemotePlaybackSeq = null
                pendingRemotePlaybackReadyAtMs = null
                reportPlaybackMetric(
                    seq = seq,
                    stage = "start",
                    source = "remote",
                    durationMs = durationMs,
                    reason = "tts_ready"
                )
            }
        },
        onPlaybackStallStarted = { seq ->
            onMain {
                reportPlaybackMetric(
                    seq = seq,
                    stage = "stall.begin",
                    source = "remote",
                    reason = "buffering"
                )
            }
        },
        onPlaybackStallEnded = { seq, durationMs ->
            onMain {
                reportPlaybackMetric(
                    seq = seq,
                    stage = "stall.end",
                    source = "remote",
                    durationMs = durationMs,
                    reason = "buffering"
                )
            }
        },
        onPlaybackCompleted = { seq, stallCount, totalStallDurationMs ->
            onMain {
                reportPlaybackMetric(
                    seq = seq,
                    stage = "complete",
                    source = "remote",
                    durationMs = totalStallDurationMs,
                    stallCount = stallCount,
                    reason = "completed"
                )
            }
        },
        onError = { seq, message ->
            onMain {
                _state.value = _state.value.copy(ttsError = message)
                fallbackToLocalTtsIfPossible("remote_playback_failed", seq)
            }
        }
    )

    private val networkListener: (Boolean) -> Unit = { available ->
        setNetworkAvailable(available)
    }

    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in setOf(
                "server_url",
                "gateway_tenant_id",
                "gateway_user_id",
                "gateway_source_lang",
                "gateway_target_lang",
                "gateway_access_token",
                "tts_ready_fallback_delay_ms"
            )
        ) {
            val config = resolveConfig()
            _state.value = _state.value.copy(gatewayWsUrl = config.getGatewayWsEndpoint())
            resetGatewayClient()
        }
        if (key in setOf("tts_enabled", "tts_speed", "tts_speaker_id")) {
            loadTtsSettings()
        }
    }

    init {
        mic = MicRecorder { frame -> onMicFrame(frame) }
        loadTtsSettings()
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        NetworkMonitor.addListener(networkListener)
        setNetworkAvailable(NetworkMonitor.getAvailable())
        initModels()
    }

    fun initModels() {
        val config = resolveConfig()
        _state.value = _state.value.copy(
            isLoading = false,
            error = null,
            gatewayWsUrl = config.getGatewayWsEndpoint()
        )
        ensureGatewayClient()
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startListening() {
        if (_state.value.isListening) {
            return
        }
        stopTts()

        val config = resolveConfig()
        if (!config.isValid()) {
            _state.value = _state.value.copy(error = "网关配置无效，请检查设置")
            return
        }

        ensureGatewayClient()

        finalBuffer.clear()
        outboundSeq.set(0L)

        val session = SessionContext(
            sessionId = "sess-${UUID.randomUUID().toString().replace("-", "")}",
            tenantId = config.tenantId,
            userId = config.userId,
            sourceLang = config.sourceLang,
            targetLang = config.targetLang,
            traceId = "trc_${UUID.randomUUID().toString().replace("-", "")}"
        )

        activeSession = session
        pendingStartSession = session
        _state.value = _state.value.copy(
            finalText = "",
            partialText = "",
            isListening = false,
            isSpeaking = false,
            error = null,
            pendingConfirm = null,
            lastCommandResult = null,
            sessionId = session.sessionId,
            gatewayConnecting = !(wsClient?.isConnected() == true),
            gatewayConnected = wsClient?.isConnected() == true,
            gatewayWsUrl = config.getGatewayWsEndpoint(),
            ttsError = null
        )

        if (wsClient?.isConnected() == true) {
            startSessionAndRecorder(session)
        } else {
            wsClient?.connect()
        }
    }

    fun stopListening() {
        stopListeningInternal(sendStop = true, reason = "client_stop")
    }

    fun stopListeningAndUpload() {
        stopListeningInternal(sendStop = true, reason = "client_stop")
    }

    fun closeRecordingMode() {
        stopListeningInternal(sendStop = true, reason = "client_close")
    }

    fun clearText() {
        stopTts()
        finalBuffer.clear()
        _state.value = _state.value.copy(
            finalText = "",
            partialText = "",
            pendingConfirm = null,
            lastCommandResult = null,
            ttsError = null
        )
    }

    fun retryUpload() {
        // Legacy no-op for old UI button compatibility.
    }

    fun speakFinalText() {
        speakText(_state.value.finalText)
    }

    fun speakCorrectedText() {
        val fallback = _state.value.lastCommandResult?.replyText.orEmpty()
        speakText(if (fallback.isNotBlank()) fallback else _state.value.finalText)
    }

    fun stopTts() {
        localTtsFallbackJob?.cancel()
        pendingFallbackSeq = null
        pendingRemotePlaybackSeq = null
        pendingRemotePlaybackReadyAtMs = null
        pendingLocalPlaybackStart = null
        remoteTtsPlayer.stop()
        ttsPlayer?.stop()
        _state.value = _state.value.copy(
            isSpeakingTts = false,
            isRemoteTtsPlaying = false
        )
    }

    fun setNetworkAvailable(available: Boolean) {
        _state.value = _state.value.copy(networkAvailable = available)
    }

    fun updateNetworkConfig(config: NetworkConfig) {
        preferences.edit()
            .putString("server_url", config.serverUrl)
            .putString("gateway_tenant_id", config.tenantId)
            .putString("gateway_user_id", config.userId)
            .putString("gateway_source_lang", config.sourceLang)
            .putString("gateway_target_lang", config.targetLang)
            .putString("gateway_access_token", config.accessToken)
            .putLong("tts_ready_fallback_delay_ms", config.ttsReadyFallbackDelayMs)
            .apply()
        resetGatewayClient()
    }

    fun submitCommandConfirm(accept: Boolean) {
        val pending = _state.value.pendingConfirm
        val session = activeSession
        if (pending == null || session == null) {
            _state.value = _state.value.copy(error = "当前没有待确认命令")
            return
        }

        val sent = wsClient?.sendCommandConfirm(
            WsCommandConfirm(
                sessionId = session.sessionId,
                seq = nextSeq(),
                confirmToken = pending.confirmToken,
                accept = accept,
                traceId = session.traceId
            )
        ) == true

        if (!sent) {
            _state.value = _state.value.copy(error = "发送 command.confirm 失败，请检查网关连接")
            return
        }

        _state.value = _state.value.copy(
            pendingConfirm = null,
            lastCommandResult = CommandExecutionResult(
                status = "confirm_sent",
                code = if (accept) "CONFIRM_ACCEPTED" else "CONFIRM_REJECTED",
                replyText = if (accept) "已提交确认，等待执行结果" else "已拒绝本次命令",
                retryable = false,
                seq = pending.commandSeq
            )
        )
    }

    override fun onConnected() {
        onMain {
            _state.value = _state.value.copy(
                gatewayConnected = true,
                gatewayConnecting = false,
                error = null
            )
            pendingStartSession?.let { startSessionAndRecorder(it) }
        }
    }

    override fun onDisconnected(reason: String) {
        onMain {
            _state.value = _state.value.copy(
                gatewayConnected = false,
                gatewayConnecting = false
            )
            if (_state.value.isListening) {
                stopListeningInternal(sendStop = false, reason = "gateway_disconnected")
                _state.value = _state.value.copy(error = "网关连接断开: $reason")
            }
        }
    }

    override fun onFailure(message: String) {
        onMain {
            _state.value = _state.value.copy(
                gatewayConnected = false,
                gatewayConnecting = false,
                error = "网关连接失败: $message"
            )
            if (_state.value.isListening) {
                stopListeningInternal(sendStop = false, reason = "gateway_failure")
            }
        }
    }

    override fun onSessionError(message: WsSessionError) {
        onMain {
            _state.value = _state.value.copy(error = "${message.code}: ${message.message}")
            stopListeningInternal(sendStop = false, reason = "session_error")
        }
    }

    override fun onSubtitlePartial(message: WsSubtitlePartial) {
        if (!belongsToActiveSession(message.sessionId)) return
        onMain {
            _state.value = _state.value.copy(
                partialText = message.text,
                isSpeaking = message.text.isNotBlank()
            )
        }
    }

    override fun onSubtitleFinal(message: WsSubtitleFinal) {
        if (!belongsToActiveSession(message.sessionId)) return
        onMain {
            appendFinalText(message.text)
            _state.value = _state.value.copy(
                finalText = finalBuffer.toString(),
                partialText = "",
                isSpeaking = false
            )
        }
    }

    override fun onCommandResult(message: WsCommandResult) {
        if (!belongsToActiveSession(message.sessionId)) return
        onMain {
            val trimmedReply = message.replyText.trim()
            val confirmToken = message.confirmToken?.takeIf { it.isNotBlank() }
            val pendingConfirm = if (confirmToken == null) {
                null
            } else {
                PendingCommandConfirm(
                    confirmToken = confirmToken,
                    prompt = if (trimmedReply.isNotBlank()) trimmedReply else "该命令需要确认，是否继续执行？",
                    expiresInSec = message.expiresInSec,
                    commandSeq = message.seq
                )
            }

            val commandResult = CommandExecutionResult(
                status = message.status,
                code = message.code,
                replyText = trimmedReply,
                retryable = message.retryable,
                seq = message.seq
            )

            _state.value = _state.value.copy(
                pendingConfirm = pendingConfirm,
                lastCommandResult = commandResult
            )
            scheduleLocalTtsFallback(commandResult)
        }
    }

    override fun onTtsReady(message: WsTtsReady) {
        if (!belongsToActiveSession(message.sessionId)) return
        onMain {
            localTtsFallbackJob?.cancel()
            pendingFallbackSeq = null
            pendingRemotePlaybackSeq = message.seq
            pendingRemotePlaybackReadyAtMs = System.currentTimeMillis()
            val played = remoteTtsPlayer.play(message.playbackUrl, message.seq)
            if (!played) {
                fallbackToLocalTtsIfPossible("remote_init_failed", message.seq)
            }
        }
    }

    override fun onSessionClosed(message: WsSessionClosed) {
        if (!belongsToActiveSession(message.sessionId)) return
        onMain {
            pendingRemotePlaybackSeq?.let { seq ->
                reportPlaybackMetric(
                    seq = seq,
                    stage = "fallback",
                    source = "local",
                    reason = "session_closed"
                )
            }
            pendingRemotePlaybackSeq = null
            pendingRemotePlaybackReadyAtMs = null
            stopListeningInternal(sendStop = false, reason = "session_closed")
            _state.value = _state.value.copy(error = "会话已关闭: ${message.reason}")
        }
    }

    override fun onCleared() {
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        NetworkMonitor.removeListener(networkListener)
        localTtsFallbackJob?.cancel()
        ttsWarmupJob?.cancel()
        mic?.release()
        wsClient?.disconnect("viewmodel-cleared")
        ttsPlayer?.release()
        remoteTtsPlayer.release()
        super.onCleared()
    }

    private fun ensureGatewayClient() {
        if (wsClient != null) {
            return
        }
        wsClient = SpeechGatewayWsClient(
            configProvider = {
                val config = resolveConfig()
                GatewayConnectionConfig(
                    wsUrl = config.getGatewayWsEndpoint(),
                    accessToken = config.accessToken.ifBlank { null }
                )
            },
            listener = this
        )
    }

    private fun resetGatewayClient() {
        wsClient?.disconnect("client-reset")
        wsClient = null
        ensureGatewayClient()
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startSessionAndRecorder(session: SessionContext) {
        val sent = wsClient?.sendSessionStart(
            WsSessionStart(
                sessionId = session.sessionId,
                tenantId = session.tenantId,
                userId = session.userId,
                sourceLang = session.sourceLang,
                targetLang = session.targetLang,
                traceId = session.traceId
            )
        ) == true
        if (!sent) {
            _state.value = _state.value.copy(
                gatewayConnected = false,
                gatewayConnecting = false,
                error = "发送 session.start 失败，请检查网关连接"
            )
            return
        }

        mic?.start(viewModelScope)
        pendingStartSession = null
        _state.value = _state.value.copy(
            isListening = true,
            gatewayConnected = true,
            gatewayConnecting = false,
            error = null
        )
    }

    private fun stopListeningInternal(sendStop: Boolean, reason: String) {
        mic?.stop()
        val session = activeSession
        if (sendStop && session != null) {
            wsClient?.sendSessionStop(
                WsSessionStop(
                    sessionId = session.sessionId,
                    reason = reason,
                    traceId = session.traceId
                )
            )
        }
        pendingRemotePlaybackSeq = null
        pendingRemotePlaybackReadyAtMs = null
        pendingLocalPlaybackStart = null
        activeSession = null
        pendingStartSession = null
        outboundSeq.set(0L)

        _state.value = _state.value.copy(
            isListening = false,
            isSpeaking = false,
            partialText = "",
            sessionId = null,
            pendingConfirm = if (sendStop) null else _state.value.pendingConfirm
        )
    }

    private fun onMicFrame(frame: FloatArray) {
        val session = activeSession ?: return
        if (!_state.value.isListening) {
            return
        }

        val audioBytes = pcmFloatTo16kPcm16(frame)
        val sent = wsClient?.sendAudioFrame(
            sessionId = session.sessionId,
            seq = nextSeq(),
            codec = "pcm_s16le",
            sampleRate = MicRecorder.SAMPLE_RATE,
            audioBytes = audioBytes
        ) == true

        if (!sent) {
            onMain {
                _state.value = _state.value.copy(error = "发送 audio.frame 失败，请检查网关连接")
            }
        }
    }

    private fun nextSeq(): Long = outboundSeq.incrementAndGet()

    private fun appendFinalText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        if (finalBuffer.isNotEmpty()) {
            finalBuffer.append('\n')
        }
        finalBuffer.append(trimmed)
    }

    private fun belongsToActiveSession(sessionId: String): Boolean {
        val active = activeSession ?: return false
        return active.sessionId == sessionId
    }

    private fun resolveConfig(): NetworkConfig {
        val serverUrl = preferences.getString("server_url", baseConfig.serverUrl) ?: baseConfig.serverUrl
        val timeoutMs = preferences.getLong("timeout_ms", baseConfig.timeoutMs)
        val ttsFallback = preferences.getLong("tts_ready_fallback_delay_ms", baseConfig.ttsReadyFallbackDelayMs)

        return baseConfig.copy(
            serverUrl = serverUrl,
            timeoutMs = timeoutMs,
            tenantId = preferences.getString("gateway_tenant_id", baseConfig.tenantId) ?: baseConfig.tenantId,
            userId = preferences.getString("gateway_user_id", baseConfig.userId) ?: baseConfig.userId,
            sourceLang = preferences.getString("gateway_source_lang", baseConfig.sourceLang) ?: baseConfig.sourceLang,
            targetLang = preferences.getString("gateway_target_lang", baseConfig.targetLang) ?: baseConfig.targetLang,
            accessToken = preferences.getString("gateway_access_token", baseConfig.accessToken) ?: baseConfig.accessToken,
            ttsReadyFallbackDelayMs = ttsFallback
        )
    }

    private fun pcmFloatTo16kPcm16(frame: FloatArray): ByteArray {
        val bytes = ByteArray(frame.size * 2)
        var out = 0
        for (sample in frame) {
            val clamped = sample.coerceIn(-1f, 1f)
            val pcm = (clamped * Short.MAX_VALUE).toInt().toShort()
            bytes[out++] = (pcm.toInt() and 0xff).toByte()
            bytes[out++] = ((pcm.toInt() shr 8) and 0xff).toByte()
        }
        return bytes
    }

    private fun scheduleLocalTtsFallback(commandResult: CommandExecutionResult) {
        if (!_state.value.isTtsEnabled) {
            return
        }
        if (commandResult.replyText.isBlank()) {
            return
        }

        pendingFallbackSeq = commandResult.seq
        localTtsFallbackJob?.cancel()
        val fallbackDelayMs = resolveConfig().ttsReadyFallbackDelayMs.coerceAtLeast(200L)
        localTtsFallbackJob = viewModelScope.launch {
            delay(fallbackDelayMs)
            if (pendingFallbackSeq == commandResult.seq && !remoteTtsPlayer.isPlaying()) {
                fallbackToLocalTtsIfPossible("tts_ready_timeout", commandResult.seq)
            }
        }
    }

    private fun fallbackToLocalTtsIfPossible(reason: String, seq: Long? = null) {
        val replyText = _state.value.lastCommandResult?.replyText.orEmpty()
        if (replyText.isBlank()) {
            return
        }
        if (!_state.value.isTtsEnabled) {
            return
        }
        val metricSeq = seq ?: pendingFallbackSeq ?: nextSeq()
        pendingFallbackSeq = null
        reportPlaybackMetric(
            seq = metricSeq,
            stage = "fallback",
            source = "local",
            reason = reason
        )
        pendingLocalPlaybackStart = PendingLocalPlaybackStart(
            seq = metricSeq,
            triggerAtMs = System.currentTimeMillis(),
            reason = reason
        )
        speakText(replyText)
    }

    private fun speakText(text: String) {
        val trimmed = text.trim()
        val currentState = _state.value

        if (trimmed.isEmpty()) {
            _state.value = _state.value.copy(ttsError = "没有可播报的文本")
            return
        }
        if (!currentState.isTtsEnabled) {
            _state.value = _state.value.copy(ttsError = "TTS 未启用，请先在设置中打开")
            return
        }
        if (currentState.isListening) {
            _state.value = _state.value.copy(ttsError = "录音中无法播报")
            return
        }

        _state.value = _state.value.copy(ttsError = null)

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val player = ensureTtsPlayer()
                player.speak(
                    text = trimmed,
                    sid = _state.value.ttsSpeakerId,
                    speed = _state.value.ttsSpeed,
                    startTime = System.currentTimeMillis()
                )
            }.onFailure { err ->
                _state.value = _state.value.copy(
                    isSpeakingTts = false,
                    ttsError = err.message ?: "TTS 初始化失败"
                )
            }
        }
    }

    private fun ensureTtsPlayer(): TtsPlayer {
        ttsPlayer?.let { return it }
        val tts = TtsModelLoader.createTts(getApplication())
        return TtsPlayer(
            context = getApplication(),
            tts = tts,
            onPlaybackStateChanged = { speaking ->
                onMain {
                    if (speaking) {
                        val pending = pendingLocalPlaybackStart
                        if (pending != null) {
                            val durationMs = (System.currentTimeMillis() - pending.triggerAtMs)
                                .coerceAtLeast(0L)
                            reportPlaybackMetric(
                                seq = pending.seq,
                                stage = "start",
                                source = "local",
                                durationMs = durationMs,
                                reason = pending.reason
                            )
                            pendingLocalPlaybackStart = null
                        }
                    }
                    _state.value = _state.value.copy(isSpeakingTts = speaking)
                }
            },
            onError = { message ->
                onMain {
                    _state.value = _state.value.copy(
                        isSpeakingTts = false,
                        ttsError = message
                    )
                }
            }
        ).also {
            ttsPlayer = it
        }
    }

    private fun reportPlaybackMetric(
        seq: Long,
        stage: String,
        source: String,
        durationMs: Long? = null,
        stallCount: Int? = null,
        reason: String? = null
    ) {
        val session = activeSession ?: return
        wsClient?.sendPlaybackMetric(
            WsPlaybackMetric(
                sessionId = session.sessionId,
                seq = seq,
                stage = stage,
                source = source,
                durationMs = durationMs,
                stallCount = stallCount,
                reason = reason,
                traceId = session.traceId
            )
        )
    }

    private fun loadTtsSettings() {
        val enabled = preferences.getBoolean("tts_enabled", false)
        if (!enabled) {
            ttsPlayer?.stop()
            ttsWarmupJob?.cancel()
        }
        _state.value = _state.value.copy(
            isTtsEnabled = enabled,
            ttsSpeed = preferences.getFloat("tts_speed", 1.0f).coerceIn(0.5f, 2.0f),
            ttsSpeakerId = preferences.getInt("tts_speaker_id", 0).coerceAtLeast(0)
        )
        if (enabled) {
            warmUpTtsIfNeeded()
        }
    }

    private fun warmUpTtsIfNeeded() {
        if (!_state.value.isTtsEnabled) {
            return
        }
        if (ttsPlayer != null || ttsWarmupJob?.isActive == true) {
            return
        }
        ttsWarmupJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching { ensureTtsPlayer() }
        }
    }

    private fun onMain(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            block()
        }
    }

    private data class SessionContext(
        val sessionId: String,
        val tenantId: String,
        val userId: String,
        val sourceLang: String,
        val targetLang: String,
        val traceId: String
    )
}
