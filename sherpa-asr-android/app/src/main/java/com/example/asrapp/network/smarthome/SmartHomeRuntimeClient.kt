package com.example.asrapp.network.smarthome

import android.util.Log
import com.example.asrapp.network.NetworkConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class SmartHomeExecutionResult(
    val status: String,
    val code: String,
    val replyText: String,
    val retryable: Boolean,
    val confirmToken: String? = null,
    val expiresInSec: Int = 0,
    val traceId: String = ""
)

class SmartHomeRuntimeClient(
    private val config: NetworkConfig
) {
    private val tag = "SmartHomeRuntimeClient"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    suspend fun executeCommand(
        sessionId: String,
        userId: String,
        text: String,
        userRole: String,
        traceId: String? = null
    ): SmartHomeExecutionResult = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("session_id", sessionId)
            .put("user_id", userId)
            .put("text", text)
            .put("user_role", userRole)
        post(config.getSmartHomeCommandEndpoint(), payload, traceId)
    }

    suspend fun confirm(
        confirmToken: String,
        accept: Boolean,
        traceId: String? = null
    ): SmartHomeExecutionResult = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("confirm_token", confirmToken)
            .put("accept", accept)
        post(config.getSmartHomeConfirmEndpoint(), payload, traceId)
    }

    private fun post(
        endpoint: String,
        payload: JSONObject,
        traceId: String?
    ): SmartHomeExecutionResult {
        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(payload.toString().toRequestBody(jsonMediaType))
        if (!traceId.isNullOrBlank()) {
            requestBuilder.header("X-Trace-Id", traceId)
        }

        return runCatching {
            client.newCall(requestBuilder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) {
                    return@use SmartHomeExecutionResult(
                        status = "failed",
                        code = if (response.isSuccessful) "INTERNAL_ERROR" else "UPSTREAM_ERROR",
                        replyText = "smartHomeNlu empty response",
                        retryable = !response.isSuccessful
                    )
                }
                parseEnvelope(body)
            }
        }.getOrElse { err ->
            Log.e(tag, "request failed endpoint=$endpoint message=${err.message}", err)
            SmartHomeExecutionResult(
                status = "timeout",
                code = "UPSTREAM_TIMEOUT",
                replyText = "smartHomeNlu timeout: ${err.message ?: "unknown"}",
                retryable = true
            )
        }
    }

    private fun parseEnvelope(raw: String): SmartHomeExecutionResult {
        val node = JSONObject(raw)
        val code = node.optString("code", "INTERNAL_ERROR")
        val retryable = node.optBoolean("retryable", false)
        val traceId = node.optString("trace_id")
        val data = node.optJSONObject("data")
        val dataStatus = data?.optString("status").orEmpty()
        val status = normalizeStatus(code, dataStatus)
        val replyText = firstNotBlank(
            data?.optString("reply_text"),
            data?.optString("tts_text"),
            node.optString("message")
        ).ifBlank { code }
        val confirmToken = data?.optString("confirm_token")?.takeIf { it.isNotBlank() }
        val expires = data?.optInt("expires_in_sec", 0) ?: 0

        return SmartHomeExecutionResult(
            status = status,
            code = code.ifBlank { "UNKNOWN" },
            replyText = replyText,
            retryable = retryable,
            confirmToken = confirmToken,
            expiresInSec = expires,
            traceId = traceId
        )
    }

    private fun normalizeStatus(code: String, dataStatus: String): String {
        val normalizedDataStatus = dataStatus.lowercase()
        if (normalizedDataStatus in setOf("ok", "confirm_required", "cancelled", "failed", "timeout")) {
            return normalizedDataStatus
        }
        return when (code) {
            "OK" -> "ok"
            "POLICY_CONFIRM_REQUIRED" -> "confirm_required"
            "UPSTREAM_TIMEOUT" -> "timeout"
            else -> "failed"
        }
    }

    private fun firstNotBlank(vararg candidates: String?): String {
        for (candidate in candidates) {
            if (!candidate.isNullOrBlank()) {
                return candidate
            }
        }
        return ""
    }
}
