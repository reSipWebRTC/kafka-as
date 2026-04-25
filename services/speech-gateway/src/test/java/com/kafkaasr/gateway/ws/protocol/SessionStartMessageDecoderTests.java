package com.kafkaasr.gateway.ws.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionStartMessageDecoderTests {

    private SessionStartMessageDecoder decoder;

    @BeforeEach
    void setUp() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        decoder = new SessionStartMessageDecoder(new ObjectMapper(), validator);
    }

    @Test
    void decodesValidSessionStartMessage() {
        SessionStartMessage message = decoder.decode("""
                {
                  "type": "session.start",
                  "sessionId": "sess-1",
                  "tenantId": "tenant-a",
                  "userId": "user-a",
                  "sourceLang": "zh-CN",
                  "targetLang": "en-US",
                  "traceId": "trc-1"
                }
                """);

        assertEquals("session.start", message.type());
        assertEquals("sess-1", message.sessionId());
        assertEquals("tenant-a", message.tenantId());
        assertEquals("user-a", message.userId());
        assertEquals("zh-CN", message.sourceLang());
        assertEquals("en-US", message.targetLang());
        assertEquals("trc-1", message.traceId());
    }

    @Test
    void rejectsMissingTenantId() {
        MessageValidationException exception = assertThrows(
                MessageValidationException.class,
                    () -> decoder.decode("""
                        {
                          "type": "session.start",
                          "sessionId": "sess-1",
                          "userId": "user-a",
                          "sourceLang": "zh-CN",
                          "targetLang": "en-US"
                        }
                        """));

        assertEquals("INVALID_MESSAGE", exception.code());
        assertEquals("sess-1", exception.sessionId());
    }

    @Test
    void rejectsMissingUserId() {
        MessageValidationException exception = assertThrows(
                MessageValidationException.class,
                () -> decoder.decode("""
                        {
                          "type": "session.start",
                          "sessionId": "sess-1",
                          "tenantId": "tenant-a",
                          "sourceLang": "zh-CN",
                          "targetLang": "en-US"
                        }
                        """));

        assertEquals("INVALID_MESSAGE", exception.code());
        assertEquals("sess-1", exception.sessionId());
    }
}
