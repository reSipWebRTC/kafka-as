package com.kafkaasr.gateway.ws.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionPingMessageDecoderTests {

    private SessionPingMessageDecoder decoder;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        decoder = new SessionPingMessageDecoder(objectMapper, validator);
    }

    @Test
    void decodesValidPingMessage() {
        SessionPingMessage message = decoder.decode("""
                {
                  "type": "session.ping",
                  "sessionId": "sess-1",
                  "ts": 1713744001000
                }
                """);

        assertEquals("session.ping", message.type());
        assertEquals("sess-1", message.sessionId());
        assertEquals(1713744001000L, message.ts());
    }

    @Test
    void rejectsNegativeTimestamp() {
        MessageValidationException exception = assertThrows(
                MessageValidationException.class,
                () -> decoder.decode("""
                        {
                          "type": "session.ping",
                          "sessionId": "sess-1",
                          "ts": -1
                        }
                        """));

        assertEquals("INVALID_MESSAGE", exception.code());
        assertEquals("sess-1", exception.sessionId());
    }

    @Test
    void rejectsUnsupportedType() {
        MessageValidationException exception = assertThrows(
                MessageValidationException.class,
                () -> decoder.decode("""
                        {
                          "type": "session.start",
                          "sessionId": "sess-1",
                          "ts": 1
                        }
                        """));

        assertEquals("INVALID_MESSAGE", exception.code());
        assertEquals("sess-1", exception.sessionId());
    }
}
