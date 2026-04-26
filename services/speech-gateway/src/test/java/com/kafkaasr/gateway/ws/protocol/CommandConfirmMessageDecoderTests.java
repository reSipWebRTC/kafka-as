package com.kafkaasr.gateway.ws.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.gateway.ingress.CommandConfirmIngressCommand;
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
    void decodesValidCommandConfirm() {
        CommandConfirmIngressCommand command = decoder.decode("""
                {
                  "type": "command.confirm",
                  "sessionId": "sess-1",
                  "seq": 11,
                  "confirmToken": "cfm-123",
                  "accept": true,
                  "traceId": "trc-1",
                  "executionId": "exec-1"
                }
                """);

        assertEquals("sess-1", command.sessionId());
        assertEquals(11L, command.seq());
        assertEquals("cfm-123", command.confirmToken());
        assertEquals(true, command.accept());
        assertEquals("trc-1", command.traceId());
        assertEquals("exec-1", command.executionId());
    }

    @Test
    void rejectsMissingConfirmToken() {
        MessageValidationException exception = assertThrows(
                MessageValidationException.class,
                () -> decoder.decode("""
                        {
                          "type": "command.confirm",
                          "sessionId": "sess-1",
                          "seq": 11,
                          "accept": true
                        }
                        """));

        assertEquals("INVALID_MESSAGE", exception.code());
        assertEquals("sess-1", exception.sessionId());
    }

    @Test
    void rejectsNegativeSeq() {
        MessageValidationException exception = assertThrows(
                MessageValidationException.class,
                () -> decoder.decode("""
                        {
                          "type": "command.confirm",
                          "sessionId": "sess-1",
                          "seq": -1,
                          "confirmToken": "cfm-123",
                          "accept": true
                        }
                        """));

        assertEquals("SESSION_SEQ_INVALID", exception.code());
    }
}
