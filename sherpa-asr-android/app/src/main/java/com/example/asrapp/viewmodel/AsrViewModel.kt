package com.example.asrapp.viewmodel

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.asrapp.NetworkMonitor
import com.example.asrapp.audio.MicRecorder
import com.example.asrapp.audio.RemoteAudioPlayer
import com.example.asrapp.audio.TtsPlayer
import com.example.asrapp.model.TtsModelLoader
import com.example.asrapp.network.NetworkConfig
import com.example.asrapp.network.smarthome.SmartHomeExecutionResult
import com.example.asrapp.network.smarthome.SmartHomeRuntimeClient
import com.example.asrapp.network.ws.GatewayCommandDispatchEvent
import com.example.asrapp.network.ws.GatewayCommandResultEvent
import com.example.asrapp.network.ws.GatewaySessionClosedEvent
import com.example.asrapp.network.ws.GatewaySessionErrorEvent
import com.example.asrapp.network.ws.GatewaySubtitleFinalEvent
import com.example.asrapp.network.ws.GatewaySubtitlePartialEvent
import com.example.asrapp.network.ws.GatewayTtsReadyEvent
import com.example.asrapp.network.ws.GatewayWsClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

data class AsrUiState(
    val finalText: String = "",
    val partialText: String = "",
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val isCorrecting: Boolean = false,
    val correctedText: String = "",
    val pendingCorrections: Map<Int, SentenceCorrection> = emptyMap(),
    val detectedCommands: List<CommandInfo> = emptyList(),
    val networkAvailable: Boolean = true,
    val asrLatencyMs: Float = 0f,
    val correctionLatencyMs: Float = 0f,
    val totalLatencyMs: Float = 0f,
    val isUploading: Boolean = false,
    val uploadSuccess: Boolean = false,
    val uploadError: String? = null,
    val isTtsEnabled: Boolean = false,
    val isSpeakingTts: Boolean = false,
    val ttsSpeed: Float = 1.0f,
    val ttsSpeakerId: Int = 0,
    val ttsError: String? = null,
    val sessionId: String = "",
    val gatewayConnected: Boolean = false,
    val pendingConfirm: PendingCommandConfirm? = null,
    val lastCommandStatus: String = "",
    val playbackUrl: String = ""
)

data class PendingCommandConfirm(
    val executionId: String,
    val confirmToken: String,
    val commandText: String,
    val traceId: String,
    val expiresInSec: Int
)

data class SentenceCorrection(
    val index: Int,
    val original: String,
    val corrected: String,
    val corrections: List<com.example.asrapp.network.model.DiffSpan>,
    val commands: List<com.example.asrapp.network.model.CommandInfo>,
    val confidence: Float,
    val processingTimeMs: Float
)

data class CommandInfo(
    val action: String,
    val device: String,
    val location: String? = null,
    val description: String
)

class AsrViewModel(
    app: Application,
    private val defaultConfig: NetworkConfig = NetworkConfig()
) : AndroidViewModel(app) {

    private val _state = kotlinx.coroutines.flow.MutableStateFlow(AsrUiState())
    val state: kotlinx.coroutines.flow.StateFlow<AsrUiState> = _state

    private val finalBuffer = StringBuilder()
    private var lastUploadedText = ""
    private var lastFailedDispatch: GatewayCommandDispatchEvent? = null

    private var mic: MicRecorder? = null
    private var gatewayClient: GatewayWsClient? = null
    private var runtimeConfig: NetworkConfig = defaultConfig
    private var smartHomeClient: SmartHomeRuntimeClient = SmartHomeRuntimeClient(defaultConfig)
    private var ttsPlayer: TtsPlayer? = null
    private var remoteAudioPlayer: RemoteAudioPlayer? = null

    private var modelInitJob: Job? = null
    private var ttsWarmupJob: Job? = null
    private var speakingStartTime = 0L
    private var activeSessionId = ""
    private val wsSeq = AtomicLong(0L)
    private val localConfirmTokenByExecutionId = mutableMapOf<String, String>()

    private val preferences = getApplication<Application>().getSharedPreferences(
        "settings",
        Context.MODE_PRIVATE
    )

    private val networkListener = { available: Boolean ->
        setNetworkAvailable(available)
    }

    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key in setOf(
                    "tts_enabled",
                    "tts_speed",
                    "tts_speaker_id",
                    "server_url",
                    "timeout_ms",
                    "gateway_ws_url",
                    "smart_home_nlu_url",
                    "tenant_id",
                    "user_id",
                    "source_lang",
                    "target_lang",
                    "user_role"
                )
            ) {
                refreshRuntimeConfig()
                loadTtsSettings()
            }
        }

    init {
        refreshRuntimeConfig()
        loadTtsSettings()
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        NetworkMonitor.addListener(networkListener)
        setNetworkAvailable(NetworkMonitor.getAvailable())
        initModels()
    }

    fun initModels() {
        if (mic != null) return
        if (modelInitJob?.isActive == true) return
        _state.value = _state.value.copy(isLoading = true, error = null)

        modelInitJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                mic = MicRecorder { frame -> onMicFrame(frame) }
            }.onSuccess {
                _state.value = _state.value.copy(isLoading = false)
            }.onFailure { err ->
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "录音初始化失败：${err.message}"
                )
            }
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startListening() {
        stopTts()
        if (mic == null) {
            initModels()
        }
        val recorder = mic ?: return

        val sessionId = "sess_${UUID.randomUUID().toString().replace("-", "").take(20)}"
        val traceId = "trc_${UUID.randomUUID().toString().replace("-", "").take(20)}"
        activeSessionId = sessionId
        wsSeq.set(0L)
        speakingStartTime = 0L
        finalBuffer.clear()
        lastFailedDispatch = null
        localConfirmTokenByExecutionId.clear()

        _state.value = _state.value.copy(
            isListening = true,
            isSpeaking = false,
            isLoading = false,
            error = null,
            finalText = "",
            partialText = "",
            correctedText = "",
            pendingCorrections = emptyMap(),
            detectedCommands = emptyList(),
            asrLatencyMs = 0f,
            correctionLatencyMs = 0f,
            totalLatencyMs = 0f,
            isUploading = false,
            uploadSuccess = false,
            uploadError = null,
            ttsError = null,
            sessionId = sessionId,
            gatewayConnected = false,
            pendingConfirm = null,
            lastCommandStatus = "",
            playbackUrl = ""
        )

        connectGateway(sessionId, traceId, recorder)
    }

    fun stopListening() {
        stopCurrentSession("client.stop")
    }

    fun stopListeningAndUpload() {
        stopCurrentSession("client.push_to_talk.release")
    }

    fun closeRecordingMode() {
        stopCurrentSession("client.manual.close")
    }

    fun retryUpload() {
        val dispatch = lastFailedDispatch
        if (dispatch == null) {
            _state.value = _state.value.copy(uploadError = "没有可重试的命令")
            return
        }
        executeDispatch(dispatch, fromConfirm = false)
    }

    fun clearText() {
        stopTts()
        finalBuffer.clear()
        _state.value = _state.value.copy(
            finalText = "",
            correctedText = "",
            partialText = "",
            pendingCorrections = emptyMap(),
            detectedCommands = emptyList(),
            isUploading = false,
            uploadSuccess = false,
            uploadError = null,
            ttsError = null,
            pendingConfirm = null,
            playbackUrl = ""
        )
    }

    fun speakFinalText() {
        speakText(_state.value.finalText)
    }

    fun speakCorrectedText() {
        speakText(_state.value.correctedText.ifBlank { _state.value.finalText })
    }

    fun stopTts() {
        remoteAudioPlayer?.stop()
        ttsPlayer?.stop()
        _state.value = _state.value.copy(isSpeakingTts = false)
    }

    fun setNetworkAvailable(available: Boolean) {
        _state.value = _state.value.copy(networkAvailable = available)
    }

    fun updateNetworkConfig(config: NetworkConfig) {
        runtimeConfig = config
        smartHomeClient = SmartHomeRuntimeClient(runtimeConfig)
    }

    fun acceptPendingCommand() {
        val pending = _state.value.pendingConfirm ?: return
        val seq = nextSeq()
        val confirmSent = gatewayClient?.sendCommandConfirm(
            seq = seq,
            confirmToken = pending.confirmToken,
            accept = true,
            traceId = pending.traceId,
            executionId = pending.executionId
        ) ?: false
        if (!confirmSent) {
            _state.value = _state.value.copy(uploadError = "确认上行失败，网关未连接")
            return
        }

        _state.value = _state.value.copy(
            pendingConfirm = null,
            isUploading = true,
            uploadSuccess = false,
            uploadError = null
        )

        executeDispatch(
            GatewayCommandDispatchEvent(
                sessionId = activeSessionId,
                seq = seq,
                executionId = pending.executionId,
                commandText = pending.commandText,
                intent = "",
                subIntent = "",
                confirmRequired = false,
                confirmToken = pending.confirmToken,
                expiresInSec = pending.expiresInSec,
                traceId = pending.traceId
            ),
            fromConfirm = true
        )
    }

    fun rejectPendingCommand() {
        val pending = _state.value.pendingConfirm ?: return
        val seq = nextSeq()
        val confirmSent = gatewayClient?.sendCommandConfirm(
            seq = seq,
            confirmToken = pending.confirmToken,
            accept = false,
            traceId = pending.traceId,
            executionId = pending.executionId
        ) ?: false

        val localToken = localConfirmTokenByExecutionId[pending.executionId]
        if (!localToken.isNullOrBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                smartHomeClient.confirm(localToken, accept = false, traceId = pending.traceId)
                localConfirmTokenByExecutionId.remove(pending.executionId)
            }
        }

        _state.value = _state.value.copy(
            pendingConfirm = null,
            isUploading = false,
            uploadSuccess = false,
            uploadError = if (confirmSent) null else "拒绝确认上行失败",
            correctedText = if (confirmSent) "命令已取消" else _state.value.correctedText
        )
    }

    private fun connectGateway(sessionId: String, traceId: String, recorder: MicRecorder) {
        gatewayClient?.close("replaced")
        gatewayClient = GatewayWsClient(
            config = runtimeConfig,
            listener = object : GatewayWsClient.Listener {
                override fun onConnected(sessionId: String) {
                    if (sessionId != activeSessionId) return
                    _state.value = _state.value.copy(gatewayConnected = true, error = null)
                    recorder.start(viewModelScope)
                }

                override fun onSubtitlePartial(event: GatewaySubtitlePartialEvent) {
                    if (event.sessionId != activeSessionId) return
                    if (speakingStartTime == 0L && event.text.isNotBlank()) {
                        speakingStartTime = System.currentTimeMillis()
                    }
                    _state.value = _state.value.copy(
                        partialText = event.text,
                        isSpeaking = event.text.isNotBlank()
                    )
                }

                override fun onSubtitleFinal(event: GatewaySubtitleFinalEvent) {
                    if (event.sessionId != activeSessionId) return
                    val text = event.text.trim()
                    if (text.isNotBlank()) {
                        if (finalBuffer.isNotEmpty()) {
                            finalBuffer.append('\n')
                        }
                        finalBuffer.append(text)
                    }
                    val asrLatency = if (speakingStartTime > 0L) {
                        (System.currentTimeMillis() - speakingStartTime).toFloat()
                    } else {
                        0f
                    }
                    speakingStartTime = 0L
                    _state.value = _state.value.copy(
                        finalText = finalBuffer.toString(),
                        partialText = "",
                        isSpeaking = false,
                        asrLatencyMs = asrLatency,
                        totalLatencyMs = asrLatency
                    )
                }

                override fun onCommandDispatch(event: GatewayCommandDispatchEvent) {
                    if (event.sessionId != activeSessionId) return
                    handleCommandDispatch(event)
                }

                override fun onCommandResult(event: GatewayCommandResultEvent) {
                    if (event.sessionId != activeSessionId) return
                    handleCommandResult(event)
                }

                override fun onTtsReady(event: GatewayTtsReadyEvent) {
                    if (event.sessionId != activeSessionId) return
                    handleTtsReady(event)
                }

                override fun onSessionError(event: GatewaySessionErrorEvent) {
                    if (event.sessionId != activeSessionId) return
                    _state.value = _state.value.copy(
                        error = "${event.code}: ${event.message}",
                        gatewayConnected = false
                    )
                }

                override fun onSessionClosed(event: GatewaySessionClosedEvent) {
                    if (event.sessionId != activeSessionId) return
                    mic?.stop()
                    _state.value = _state.value.copy(
                        gatewayConnected = false,
                        isListening = false,
                        isSpeaking = false,
                        partialText = ""
                    )
                }

                override fun onDisconnected(sessionId: String, reason: String) {
                    if (sessionId != activeSessionId) return
                    mic?.stop()
                    _state.value = _state.value.copy(
                        gatewayConnected = false,
                        isListening = false,
                        isSpeaking = false,
                        partialText = "",
                        error = if (reason.isBlank()) _state.value.error else "WS断开: $reason"
                    )
                }

                override fun onFailure(sessionId: String, message: String, throwable: Throwable?) {
                    if (sessionId != activeSessionId) return
                    _state.value = _state.value.copy(
                        gatewayConnected = false,
                        error = message
                    )
                }
            }
        )
        gatewayClient?.connect(sessionId = sessionId, traceId = traceId)
    }

    private fun handleCommandDispatch(event: GatewayCommandDispatchEvent) {
        lastUploadedText = event.commandText
        if (event.commandText.isNotBlank()) {
            _state.value = _state.value.copy(
                detectedCommands = listOf(
                    CommandInfo(
                        action = "",
                        device = "",
                        location = null,
                        description = event.commandText
                    )
                )
            )
        }

        if (event.confirmRequired) {
            _state.value = _state.value.copy(
                pendingConfirm = PendingCommandConfirm(
                    executionId = event.executionId,
                    confirmToken = event.confirmToken,
                    commandText = event.commandText,
                    traceId = event.traceId,
                    expiresInSec = event.expiresInSec
                ),
                isUploading = false,
                uploadSuccess = false,
                uploadError = null,
                lastCommandStatus = "confirm_required"
            )
            return
        }

        executeDispatch(event, fromConfirm = false)
    }

    private fun executeDispatch(event: GatewayCommandDispatchEvent, fromConfirm: Boolean) {
        _state.value = _state.value.copy(
            isUploading = true,
            uploadSuccess = false,
            uploadError = null
        )

        viewModelScope.launch(Dispatchers.IO) {
            val result = executeAgainstSmartHomeRuntime(event, fromConfirm)
            if (result.status == "confirm_required" && !result.confirmToken.isNullOrBlank()) {
                localConfirmTokenByExecutionId[event.executionId] = result.confirmToken
            }
            if (result.status != "confirm_required") {
                localConfirmTokenByExecutionId.remove(event.executionId)
            }

            val sendOk = gatewayClient?.sendCommandExecuteResult(
                seq = nextSeq(),
                executionId = event.executionId,
                status = result.status,
                code = result.code,
                replyText = result.replyText,
                retryable = result.retryable,
                traceId = if (result.traceId.isNotBlank()) result.traceId else event.traceId
            ) ?: false

            if (!sendOk) {
                lastFailedDispatch = event
                _state.value = _state.value.copy(
                    isUploading = false,
                    uploadSuccess = false,
                    uploadError = "执行结果上行失败，网关未连接",
                    correctedText = result.replyText
                )
                return@launch
            }

            val failed = result.status in setOf("failed", "timeout")
            val cancelled = result.status == "cancelled"
            val succeeded = result.status == "ok"

            if (failed) {
                lastFailedDispatch = event
            } else if (cancelled || succeeded) {
                lastFailedDispatch = null
            }

            _state.value = _state.value.copy(
                isUploading = false,
                uploadSuccess = succeeded,
                uploadError = if (failed) result.replyText else null,
                correctedText = result.replyText,
                lastCommandStatus = result.status
            )

            if (succeeded) {
                delay(3000)
                _state.value = _state.value.copy(uploadSuccess = false)
            }
        }
    }

    private suspend fun executeAgainstSmartHomeRuntime(
        event: GatewayCommandDispatchEvent,
        fromConfirm: Boolean
    ): SmartHomeExecutionResult {
        val sessionId = activeSessionId.ifBlank { event.sessionId }
        val traceId = if (event.traceId.isBlank()) null else event.traceId
        if (fromConfirm) {
            val localToken = localConfirmTokenByExecutionId[event.executionId]
            if (!localToken.isNullOrBlank()) {
                return smartHomeClient.confirm(
                    confirmToken = localToken,
                    accept = true,
                    traceId = traceId
                )
            }
        }
        return smartHomeClient.executeCommand(
            sessionId = sessionId,
            userId = runtimeConfig.userId,
            text = event.commandText,
            userRole = runtimeConfig.userRole,
            traceId = traceId
        )
    }

    private fun handleCommandResult(event: GatewayCommandResultEvent) {
        if (event.status != "confirm_required") {
            localConfirmTokenByExecutionId.remove(event.executionId)
        }
        _state.value = _state.value.copy(
            correctedText = event.replyText.ifBlank { _state.value.correctedText },
            lastCommandStatus = event.status,
            isUploading = false,
            uploadSuccess = event.status == "ok",
            uploadError = when (event.status) {
                "failed", "timeout", "cancelled" -> event.replyText.ifBlank { event.code }
                else -> null
            }
        )
    }

    private fun handleTtsReady(event: GatewayTtsReadyEvent) {
        _state.value = _state.value.copy(playbackUrl = event.playbackUrl)
        if (event.playbackUrl.isBlank()) {
            speakText(_state.value.correctedText.ifBlank { _state.value.finalText })
            return
        }
        ensureRemoteAudioPlayer().play(event.playbackUrl)
    }

    private fun onMicFrame(frame: FloatArray) {
        if (!_state.value.isListening) {
            return
        }
        val session = activeSessionId
        if (session.isBlank()) {
            return
        }
        val sent = gatewayClient?.sendAudioFrame(
            seq = nextSeq(),
            audioBase64 = pcmFloatToBase64(frame)
        ) ?: false
        if (!sent) {
            _state.value = _state.value.copy(error = "音频上行失败，网关未连接")
        }
    }

    private fun stopCurrentSession(reason: String) {
        mic?.stop()
        val session = activeSessionId
        if (session.isNotBlank()) {
            gatewayClient?.sendSessionStop(
                seq = nextSeq(),
                traceId = "trc_${UUID.randomUUID().toString().replace("-", "").take(12)}",
                reason = reason
            )
        }
        _state.value = _state.value.copy(
            isListening = false,
            isSpeaking = false,
            partialText = ""
        )
    }

    private fun nextSeq(): Long = wsSeq.incrementAndGet()

    private fun pcmFloatToBase64(samples: FloatArray): String {
        val bytes = ByteArray(samples.size * 2)
        var index = 0
        for (sample in samples) {
            val normalized = sample.coerceIn(-1f, 1f)
            val pcm = (normalized * Short.MAX_VALUE).toInt().toShort()
            bytes[index++] = (pcm.toInt() and 0xFF).toByte()
            bytes[index++] = ((pcm.toInt() shr 8) and 0xFF).toByte()
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun speakText(text: String) {
        val trimmed = text.trim()
        val currentState = _state.value

        if (trimmed.isEmpty()) {
            _state.value = currentState.copy(ttsError = "没有可播报的文本")
            return
        }

        if (!currentState.isTtsEnabled) {
            _state.value = currentState.copy(ttsError = "TTS 未启用，请先在设置中打开")
            return
        }

        if (currentState.isListening) {
            _state.value = currentState.copy(ttsError = "录音中无法播报")
            return
        }

        _state.value = currentState.copy(ttsError = null)

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
                _state.value = _state.value.copy(isSpeakingTts = speaking)
            },
            onError = { message ->
                _state.value = _state.value.copy(
                    isSpeakingTts = false,
                    ttsError = message
                )
            }
        ).also {
            ttsPlayer = it
        }
    }

    private fun ensureRemoteAudioPlayer(): RemoteAudioPlayer {
        remoteAudioPlayer?.let { return it }
        return RemoteAudioPlayer(
            context = getApplication(),
            onPlaybackStateChanged = { speaking ->
                _state.value = _state.value.copy(isSpeakingTts = speaking)
            },
            onError = { message ->
                _state.value = _state.value.copy(ttsError = message)
                speakText(_state.value.correctedText.ifBlank { _state.value.finalText })
            }
        ).also {
            remoteAudioPlayer = it
        }
    }

    private fun warmUpTtsIfNeeded(force: Boolean) {
        if (!_state.value.isTtsEnabled) return
        if (!force && ttsPlayer != null) return
        if (ttsWarmupJob?.isActive == true) return

        ttsWarmupJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                ensureTtsPlayer()
            }
        }
    }

    private fun loadTtsSettings() {
        val enabled = preferences.getBoolean("tts_enabled", false)
        if (!enabled) {
            ttsPlayer?.stop()
            remoteAudioPlayer?.stop()
            ttsWarmupJob?.cancel()
        }
        _state.value = _state.value.copy(
            isTtsEnabled = enabled,
            ttsSpeed = preferences.getFloat("tts_speed", 1.0f).coerceIn(0.5f, 2.0f),
            ttsSpeakerId = preferences.getInt("tts_speaker_id", 0).coerceAtLeast(0)
        )
        if (enabled) {
            warmUpTtsIfNeeded(force = false)
        }
    }

    private fun refreshRuntimeConfig() {
        val serverUrl = preferences.getString("server_url", defaultConfig.serverUrl)?.trim().orEmpty()
            .ifBlank { defaultConfig.serverUrl }
        val timeoutMs = preferences.getLong("timeout_ms", defaultConfig.timeoutMs).coerceAtLeast(1000L)
        val smartHomeUrl = preferences.getString("smart_home_nlu_url", defaultConfig.smartHomeRuntimeUrl)
            ?.trim()
            .orEmpty()
            .ifBlank { serverUrl }

        runtimeConfig = NetworkConfig(
            serverUrl = serverUrl,
            timeoutMs = timeoutMs,
            enableCorrection = preferences.getBoolean("enable_correction", true),
            cacheEnabled = defaultConfig.cacheEnabled,
            retryCount = defaultConfig.retryCount,
            smartHomeRuntimeUrl = smartHomeUrl,
            tenantId = preferences.getString("tenant_id", NetworkConfig.DEFAULT_TENANT_ID)
                ?.trim()
                .orEmpty()
                .ifBlank { NetworkConfig.DEFAULT_TENANT_ID },
            userId = preferences.getString("user_id", NetworkConfig.DEFAULT_USER_ID)
                ?.trim()
                .orEmpty()
                .ifBlank { NetworkConfig.DEFAULT_USER_ID },
            sourceLang = preferences.getString("source_lang", NetworkConfig.DEFAULT_SOURCE_LANG)
                ?.trim()
                .orEmpty()
                .ifBlank { NetworkConfig.DEFAULT_SOURCE_LANG },
            targetLang = preferences.getString("target_lang", NetworkConfig.DEFAULT_TARGET_LANG)
                ?.trim()
                .orEmpty()
                .ifBlank { NetworkConfig.DEFAULT_TARGET_LANG },
            userRole = preferences.getString("user_role", NetworkConfig.DEFAULT_USER_ROLE)
                ?.trim()
                .orEmpty()
                .ifBlank { NetworkConfig.DEFAULT_USER_ROLE },
            gatewayWsUrl = preferences.getString("gateway_ws_url", "").orEmpty().trim()
        )
        smartHomeClient = SmartHomeRuntimeClient(runtimeConfig)
    }

    override fun onCleared() {
        modelInitJob?.cancel()
        ttsWarmupJob?.cancel()
        NetworkMonitor.removeListener(networkListener)
        preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        mic?.release()
        gatewayClient?.close("viewmodel.cleared")
        ttsPlayer?.release()
        remoteAudioPlayer?.release()
    }
}
