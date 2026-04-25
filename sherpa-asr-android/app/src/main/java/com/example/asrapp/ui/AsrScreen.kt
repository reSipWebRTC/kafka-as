package com.example.asrapp.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallTopAppBar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.asrapp.AsrViewModelFactory
import com.example.asrapp.viewmodel.AsrViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AsrScreen(
    vm: AsrViewModel = viewModel(
        factory = AsrViewModelFactory(LocalContext.current.applicationContext as android.app.Application)
    ),
    onSettingsClick: () -> Unit = {}
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        vm.initModels()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            vm.startListening()
        }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = { Text("Android WS Command Flow") },
                actions = {
                    IconButton(onClick = { vm.clearText() }) {
                        Icon(Icons.Default.Clear, contentDescription = "清空")
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (state.isListening) {
                        vm.stopListening()
                    } else {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            ) {
                if (state.isListening) {
                    Icon(Icons.Default.Stop, contentDescription = "停止录音")
                } else {
                    Icon(Icons.Default.Mic, contentDescription = "开始录音")
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ConnectionStatusCard(
                gatewayWsUrl = state.gatewayWsUrl,
                gatewayConnected = state.gatewayConnected,
                gatewayConnecting = state.gatewayConnecting,
                isListening = state.isListening
            )

            state.error?.takeIf { it.isNotBlank() }?.let { err ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            TranscriptCard(
                finalText = state.finalText,
                partialText = state.partialText
            )

            CommandResultCard(
                status = state.lastCommandResult?.status.orEmpty(),
                code = state.lastCommandResult?.code.orEmpty(),
                replyText = state.lastCommandResult?.replyText.orEmpty(),
                retryable = state.lastCommandResult?.retryable == true
            )

            state.pendingConfirm?.let { pending ->
                CommandConfirmCard(
                    prompt = pending.prompt,
                    confirmToken = pending.confirmToken,
                    expiresInSec = pending.expiresInSec,
                    onAccept = { vm.submitCommandConfirm(true) },
                    onReject = { vm.submitCommandConfirm(false) }
                )
            }

            TtsStatusCard(
                ttsEnabled = state.isTtsEnabled,
                isLocalSpeaking = state.isSpeakingTts,
                isRemoteSpeaking = state.isRemoteTtsPlaying,
                ttsError = state.ttsError,
                onSpeak = vm::speakCorrectedText,
                onStop = vm::stopTts
            )

            Text(
                text = if (state.isListening) "录音中：音频帧实时通过 WS 上行" else "点击麦克风开始会话",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ConnectionStatusCard(
    gatewayWsUrl: String,
    gatewayConnected: Boolean,
    gatewayConnecting: Boolean,
    isListening: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "网关连接",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = gatewayWsUrl.ifBlank { "未配置 wsUrl" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val status = when {
                gatewayConnected && isListening -> "已连接（会话中）"
                gatewayConnected -> "已连接"
                gatewayConnecting -> "连接中"
                else -> "未连接"
            }
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    gatewayConnected -> MaterialTheme.colorScheme.primary
                    gatewayConnecting -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.error
                }
            )
        }
    }
}

@Composable
private fun TranscriptCard(
    finalText: String,
    partialText: String
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "识别结果",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            if (finalText.isBlank() && partialText.isBlank()) {
                Text(
                    text = "暂无识别内容",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                if (finalText.isNotBlank()) {
                    Text(
                        text = finalText,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                if (partialText.isNotBlank()) {
                    Text(
                        text = partialText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun CommandResultCard(
    status: String,
    code: String,
    replyText: String,
    retryable: Boolean
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "命令回执",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )

            if (status.isBlank() && code.isBlank() && replyText.isBlank()) {
                Text(
                    text = "尚未收到 command.result",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(text = "status: $status", style = MaterialTheme.typography.bodyMedium)
                Text(text = "code: $code", style = MaterialTheme.typography.bodyMedium)
                if (replyText.isNotBlank()) {
                    Text(text = replyText, style = MaterialTheme.typography.bodyLarge)
                }
                Text(
                    text = if (retryable) "可重试" else "不可重试",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (retryable) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CommandConfirmCard(
    prompt: String,
    confirmToken: String,
    expiresInSec: Long?,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "待确认命令",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(text = prompt, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "confirmToken: $confirmToken",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (expiresInSec != null) {
                Text(
                    text = "有效期: ${expiresInSec}s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAccept) {
                    Text("确认执行")
                }
                FilledTonalButton(onClick = onReject) {
                    Text("拒绝执行")
                }
            }
        }
    }
}

@Composable
private fun TtsStatusCard(
    ttsEnabled: Boolean,
    isLocalSpeaking: Boolean,
    isRemoteSpeaking: Boolean,
    ttsError: String?,
    onSpeak: () -> Unit,
    onStop: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "TTS 播放",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = when {
                    isRemoteSpeaking -> "远端 tts.ready 播放中"
                    isLocalSpeaking -> "本地 TTS 回退播放中"
                    ttsEnabled -> "已启用（远端优先，本地回退）"
                    else -> "未启用本地回退"
                },
                style = MaterialTheme.typography.bodyMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onSpeak, enabled = ttsEnabled && !isLocalSpeaking && !isRemoteSpeaking) {
                    Text("本地试播")
                }
                TextButton(onClick = onStop, enabled = isLocalSpeaking || isRemoteSpeaking) {
                    Text("停止播放")
                }
            }
            if (!ttsError.isNullOrBlank()) {
                Text(
                    text = ttsError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
