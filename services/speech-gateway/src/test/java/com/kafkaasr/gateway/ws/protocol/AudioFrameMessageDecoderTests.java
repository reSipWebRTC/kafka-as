package com.kafkaasr.gateway.ws.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.gateway.ingress.AudioFrameIngressCommand;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AudioFrameMessageDecoderTests {

    private AudioFrameMessageDecoder decoder;

    @BeforeEach
    void setUp() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        decoder = new AudioFrameMessageDecoder(new ObjectMapper(), validator);
    }

    @Test
    void decodesValidAudioFrame() {
        AudioFrameIngressCommand command = decoder.decode("""
                {
                  "type": "audio.frame",
                  "sessionId": "sess-1",
                  "seq": 7,
                  "codec": "pcm16le",
                  "sampleRate": 16000,
                  "audioBase64": "AQID"
                }
                """);

        assertEquals("sess-1", command.sessionId());
        assertEquals(7L, command.seq());
        assertEquals("pcm16le", command.codec());
        assertEquals(16000, command.sampleRate());
        assertArrayEquals(new byte[] {1, 2, 3}, command.audioBytes());
    }

    @Test
    void rejectsUnsupportedType() {
        MessageValidationException exception = assertThrows(
                MessageValidationException.class,
                () -> decoder.decode("""
                        {
                          "type": "session.ping",
                          "sessionId": "sess-1",
                          "seq": 7,
                          "codec": "pcm16le",
                          "sampleRate": 16000,
                          "audioBase64": "AQID"
                        }
                        """));

        assertEquals("INVALID_MESSAGE", exception.code());
    }

    @Test
    void rejectsInvalidSeq() {
        MessageValidationException exception = assertThrows(
                MessageValidationException.class,
                () -> decoder.decode("""
                        {
                          "type": "audio.frame",
                          "sessionId": "sess-1",
                          "seq": -1,
                          "codec": "pcm16le",
                          "sampleRate": 16000,
                          "audioBase64": "AQID"
                        }
                        """));

        assertEquals("SESSION_SEQ_INVALID", exception.code());
    }
}
