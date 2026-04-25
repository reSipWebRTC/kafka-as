package com.example.asrapp.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

class GatewayMessageParserTest {

    @Test
    fun parseCommandResult_withConfirmToken() {
        val raw = """
            {
              "type":"command.result",
              "sessionId":"sess-1",
              "seq":12,
              "status":"CONFIRM_REQUIRED",
              "code":"DEVICE_CONFIRM_NEEDED",
              "replyText":"Please confirm",
              "retryable":false,
              "confirmToken":"cfm-1",
              "expiresInSec":45
            }
        """.trimIndent()

        val parsed = GatewayMessageParser.parse(raw)
        assertTrue(parsed is WsCommandResult)
        val message = parsed as WsCommandResult
        assertEquals("sess-1", message.sessionId)
        assertEquals(12L, message.seq)
        assertEquals("DEVICE_CONFIRM_NEEDED", message.code)
        assertEquals("cfm-1", message.confirmToken)
        assertEquals(45L, message.expiresInSec)
    }

    @Test
    fun parseSubtitleFinal_success() {
        val raw = """
            {
              "type":"subtitle.final",
              "sessionId":"sess-2",
              "seq":9,
              "text":"turn on the light"
            }
        """.trimIndent()

        val parsed = GatewayMessageParser.parse(raw)
        assertTrue(parsed is WsSubtitleFinal)
        val message = parsed as WsSubtitleFinal
        assertEquals("sess-2", message.sessionId)
        assertEquals(9L, message.seq)
        assertEquals("turn on the light", message.text)
    }

    @Test
    fun parseUnknownType_returnsUnknownMessage() {
        val raw = """
            {
              "type":"custom.event",
              "sessionId":"sess-x"
            }
        """.trimIndent()

        val parsed = GatewayMessageParser.parse(raw)
        assertNotNull(parsed)
        assertTrue(parsed is WsUnknownDownlink)
        val message = parsed as WsUnknownDownlink
        assertEquals("custom.event", message.type)
        assertEquals("sess-x", message.sessionId)
    }

    @Test
    fun serializePlaybackMetric_uplinkPayload() {
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
        val adapter = moshi.adapter(WsPlaybackMetric::class.java)

        val json = adapter.toJson(
            WsPlaybackMetric(
                sessionId = "sess-play-1",
                seq = 33L,
                stage = "start",
                source = "remote",
                durationMs = 210L,
                reason = "tts_ready",
                traceId = "trc-1"
            )
        )

        assertTrue(json.contains("\"type\":\"playback.metric\""))
        assertTrue(json.contains("\"sessionId\":\"sess-play-1\""))
        assertTrue(json.contains("\"stage\":\"start\""))
        assertTrue(json.contains("\"source\":\"remote\""))
        assertTrue(json.contains("\"durationMs\":210"))
    }

    @Test
    fun serializePlaybackMetric_stallBeginPayload() {
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
        val adapter = moshi.adapter(WsPlaybackMetric::class.java)

        val json = adapter.toJson(
            WsPlaybackMetric(
                sessionId = "sess-play-2",
                seq = 34L,
                stage = "stall.begin",
                source = "remote",
                reason = "buffering",
                traceId = "trc-2"
            )
        )

        assertTrue(json.contains("\"stage\":\"stall.begin\""))
        assertTrue(json.contains("\"source\":\"remote\""))
        assertTrue(!json.contains("\"durationMs\""))
    }
}
