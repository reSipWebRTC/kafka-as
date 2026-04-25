package com.kafkaasr.gateway.ws.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlaybackMetricMessageDecoderTests {

    private PlaybackMetricMessageDecoder decoder;

    @BeforeEach
    void setUp() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        decoder = new PlaybackMetricMessageDecoder(new ObjectMapper(), validator);
    }

    @Test
    void decodesValidPlaybackStartMetric() {
        PlaybackMetricMessage message = decoder.decode("""
                {
                  "type": "playback.metric",
                  "sessionId": "sess-1",
                  "seq": 12,
                  "stage": "start",
                  "source": "remote",
                  "durationMs": 320,
                  "reason": "tts_ready"
                }
                """);

        assertEquals("playback.metric", message.type());
        assertEquals("sess-1", message.sessionId());
        assertEquals(12L, message.seq());
        assertEquals("start", message.stage());
        assertEquals("remote", message.source());
        assertEquals(320L, message.durationMs());
    }

    @Test
    void decodesStallBeginWithoutDuration() {
        PlaybackMetricMessage message = decoder.decode("""
                {
                  "type": "playback.metric",
                  "sessionId": "sess-1",
                  "seq": 13,
                  "stage": "stall.begin",
                  "source": "remote",
                  "reason": "buffering"
                }
                """);

        assertEquals("stall.begin", message.stage());
        assertEquals("remote", message.source());
        assertNull(message.durationMs());
    }

    @Test
    void rejectsUnsupportedStage() {
        MessageValidationException exception = assertThrows(
                MessageValidationException.class,
                () -> decoder.decode("""
                        {
                          "type": "playback.metric",
                          "sessionId": "sess-1",
                          "seq": 12,
                          "stage": "progress",
                          "source": "remote",
                          "durationMs": 100
                        }
                        """));

        assertEquals("INVALID_MESSAGE", exception.code());
    }

    @Test
    void rejectsDurationMissingForStartStageAndStallEnd() {
        MessageValidationException exception = assertThrows(
                MessageValidationException.class,
                () -> decoder.decode("""
                        {
                          "type": "playback.metric",
                          "sessionId": "sess-1",
                          "seq": 12,
                          "stage": "start",
                          "source": "remote"
                        }
                        """));

        assertEquals("INVALID_MESSAGE", exception.code());

        MessageValidationException stallEndException = assertThrows(
                MessageValidationException.class,
                () -> decoder.decode("""
                        {
                          "type": "playback.metric",
                          "sessionId": "sess-1",
                          "seq": 12,
                          "stage": "stall.end",
                          "source": "remote"
                        }
                        """));

        assertEquals("INVALID_MESSAGE", stallEndException.code());
    }

    @Test
    void rejectsInvalidSeq() {
        MessageValidationException exception = assertThrows(
                MessageValidationException.class,
                () -> decoder.decode("""
                        {
                          "type": "playback.metric",
                          "sessionId": "sess-1",
                          "seq": -1,
                          "stage": "fallback",
                          "source": "local",
                          "reason": "tts_ready_timeout"
                        }
                        """));

        assertEquals("SESSION_SEQ_INVALID", exception.code());
    }
}
