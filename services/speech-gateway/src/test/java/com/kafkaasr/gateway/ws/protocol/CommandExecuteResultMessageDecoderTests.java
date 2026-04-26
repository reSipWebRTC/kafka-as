package com.kafkaasr.gateway.ws.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.gateway.ingress.CommandExecuteResultIngressCommand;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CommandExecuteResultMessageDecoderTests {

    private CommandExecuteResultMessageDecoder decoder;

    @BeforeEach
    void setUp() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        decoder = new CommandExecuteResultMessageDecoder(new ObjectMapper(), validator);
    }

    @Test
    void decodesValidCommandExecuteResult() {
        CommandExecuteResultIngressCommand command = decoder.decode("""
                {
                  "type": "command.execute.result",
                  "sessionId": "sess-1",
                  "seq": 12,
                  "executionId": "exec-1",
                  "status": "ok",
                  "code": "OK",
                  "replyText": "执行成功",
                  "retryable": false,
                  "traceId": "trc-1"
                }
                """);

        assertEquals("sess-1", command.sessionId());
        assertEquals(12L, command.seq());
        assertEquals("exec-1", command.executionId());
        assertEquals("ok", command.status());
        assertEquals("OK", command.code());
        assertEquals("执行成功", command.replyText());
        assertEquals(false, command.retryable());
        assertEquals("trc-1", command.traceId());
    }

    @Test
    void rejectsMissingExecutionId() {
        MessageValidationException exception = assertThrows(
                MessageValidationException.class,
                () -> decoder.decode("""
                        {
                          "type": "command.execute.result",
                          "sessionId": "sess-1",
                          "seq": 12,
                          "status": "ok",
                          "code": "OK",
                          "retryable": false
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
                          "type": "command.execute.result",
                          "sessionId": "sess-1",
                          "seq": -1,
                          "executionId": "exec-1",
                          "status": "ok",
                          "code": "OK",
                          "retryable": false
                        }
                        """));

        assertEquals("SESSION_SEQ_INVALID", exception.code());
    }
}
