package com.example.asrapp.network.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * 智能家居解析数据模型
 * 与服务端 /smart-home 端点对应
 */

// ── 请求模型 ─────────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class SmartHomeRequest(
    val text: String,
    @param:Json(name = "use_glm")
    val useGlm: Boolean = true
)

// ── 响应模型 ─────────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class SmartHomeResponse(
    @param:Json(name = "original")
    val original: String,

    @param:Json(name = "filtered")
    val filtered: String,

    @param:Json(name = "layers")
    val layers: LayersInfo? = null,

    @param:Json(name = "stats")
    val stats: StatsInfo? = null,

    @param:Json(name = "smart_home_parsed")
    val smartHomeParsed: String? = null
) {
    val isSuccess: Boolean
        get() = filtered.isNotEmpty()

    val hasChanges: Boolean
        get() = original != filtered
}

@JsonClass(generateAdapter = true)
data class LayersInfo(
    @param:Json(name = "regex")
    val regex: String? = null,

    @param:Json(name = "context")
    val context: String? = null
)

@JsonClass(generateAdapter = true)
data class StatsInfo(
    @param:Json(name = "change_ratio")
    val changeRatio: Float? = null,

    @param:Json(name = "glm_used")
    val glmUsed: Boolean = false,

    @param:Json(name = "smart_home_used")
    val smartHomeUsed: Boolean = false,

    @param:Json(name = "latency_ms")
    val latencyMs: Float? = null,

    @param:Json(name = "input_length")
    val inputLength: Int? = null,

    @param:Json(name = "output_length")
    val outputLength: Int? = null
)

// ── 流式响应事件类型 ───────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class StreamEvent(
    @param:Json(name = "type")
    val type: EventType,

    @param:Json(name = "text")
    val text: String? = null,

    @param:Json(name = "delta")
    val delta: String? = null,

    @param:Json(name = "commands")
    val commands: List<CommandInfo>? = null,

    @param:Json(name = "confidence")
    val confidence: Float? = null,

    @param:Json(name = "latency_ms")
    val latencyMs: Float? = null,

    @param:Json(name = "glm_needed")
    val glmNeeded: Boolean? = null,

    @param:Json(name = "glm_used")
    val glmUsed: Boolean? = null,

    @param:Json(name = "is_final")
    val isFinal: Boolean? = null
)

enum class EventType {
    IMMEDIATE,
    TOKEN,
    DONE
}

// ── 智能补全模型 ─────────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class CompletionRequest(
    @param:Json(name = "partial")
    val partial: String,

    @param:Json(name = "max_results")
    val maxResults: Int = 5
)

@JsonClass(generateAdapter = true)
data class CompletionCandidate(
    @param:Json(name = "completed")
    val completed: String,

    @param:Json(name = "confidence")
    val confidence: Float,

    @param:Json(name = "match_type")
    val matchType: String,

    @param:Json(name = "source")
    val source: String
)

@JsonClass(generateAdapter = true)
data class CompletionResponse(
    @param:Json(name = "partial")
    val partial: String,

    @param:Json(name = "completions")
    val completions: List<CompletionCandidate>? = null,

    @param:Json(name = "total_latency_ms")
    val totalLatencyMs: Float? = null
)
