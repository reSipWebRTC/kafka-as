package com.kafkaasr.gateway.ws.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CommandConfirmMessageDecoderTests {

    private CommandConfirmMessageDecoder decoder;

    @BeforeEach
    void setUp() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        decoder = new CommandConfirmMessageDecoder(new ObjectMapper(), validator);
    }

    @Test
    void decodesValidMessage() {
        CommandConfirmMessage message = decoder.decode("""
                {
                  "type": "command.confirm",
                  "sessionId": "sess-1",
                  "seq": 8,
                  "confirmToken": "cfm-1",
                  "accept": true,
                  "traceId": "trc-1"
                }
                """);

        assertEquals("command.confirm", message.type());
        assertEquals("sess-1", message.sessionId());
        assertEquals(8L, message.seq());
        assertEquals("cfm-1", message.confirmToken());
        assertEquals(true, message.accept());
        assertEquals("trc-1", message.traceId());
    }

    @Test
    void rejectsNegativeSequence() {
        MessageValidationException exception = assertThrows(
                MessageValidationException.class,
                () -> decoder.decode("""
                        {
                          "type": "command.confirm",
                          "sessionId": "sess-1",
                          "seq": -1,
                          "confirmToken": "cfm-1",
                          "accept": true
                        }
                        """));

        assertEquals("SESSION_SEQ_INVALID", exception.code());
        assertEquals("sess-1", exception.sessionId());
    }

    @Test
    void rejectsUnexpectedType() {
        MessageValidationException exception = assertThrows(
                MessageValidationException.class,
                () -> decoder.decode("""
                        {
                          "type": "command.confirmed",
                          "sessionId": "sess-1",
                          "seq": 9,
                          "confirmToken": "cfm-1",
                          "accept": false
                        }
                        """));

        assertEquals("INVALID_MESSAGE", exception.code());
        assertEquals("sess-1", exception.sessionId());
    }
}
