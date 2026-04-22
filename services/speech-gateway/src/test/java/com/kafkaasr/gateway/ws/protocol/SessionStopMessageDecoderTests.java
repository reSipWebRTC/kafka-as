package com.kafkaasr.gateway.ws.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionStopMessageDecoderTests {

    private SessionStopMessageDecoder decoder;

    @BeforeEach
    void setUp() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        decoder = new SessionStopMessageDecoder(new ObjectMapper(), validator);
    }

    @Test
    void decodesValidSessionStopMessage() {
        SessionStopMessage message = decoder.decode("""
                {
                  "type": "session.stop",
                  "sessionId": "sess-1",
                  "traceId": "trc-1",
                  "reason": "client.stop"
                }
                """);

        assertEquals("session.stop", message.type());
        assertEquals("sess-1", message.sessionId());
        assertEquals("trc-1", message.traceId());
        assertEquals("client.stop", message.reason());
    }

    @Test
    void rejectsMissingSessionId() {
        MessageValidationException exception = assertThrows(
                MessageValidationException.class,
                () -> decoder.decode("""
                        {
                          "type": "session.stop",
                          "reason": "client.stop"
                        }
                        """));

        assertEquals("INVALID_MESSAGE", exception.code());
        assertEquals("", exception.sessionId());
    }
}
