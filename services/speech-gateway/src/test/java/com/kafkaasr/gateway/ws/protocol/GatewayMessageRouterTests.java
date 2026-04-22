package com.kafkaasr.gateway.ws.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.gateway.ingress.AudioFrameIngressCommand;
import com.kafkaasr.gateway.ingress.AudioIngressPublisher;
import com.kafkaasr.gateway.session.SessionControlClient;
import com.kafkaasr.gateway.session.SessionStartCommand;
import com.kafkaasr.gateway.session.SessionStopCommand;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class GatewayMessageRouterTests {

    @Mock
    private AudioIngressPublisher audioIngressPublisher;

    @Mock
    private SessionControlClient sessionControlClient;

    private GatewayMessageRouter router;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        router = new GatewayMessageRouter(
                audioIngressPublisher,
                new AudioFrameMessageDecoder(objectMapper, validator),
                new SessionStartMessageDecoder(objectMapper, validator),
                new SessionPingMessageDecoder(objectMapper, validator),
                new SessionStopMessageDecoder(objectMapper, validator),
                sessionControlClient,
                objectMapper,
                new SimpleMeterRegistry());

        lenient().when(audioIngressPublisher.publishRawFrame(any())).thenReturn(Mono.empty());
        lenient().when(sessionControlClient.startSession(any())).thenReturn(Mono.empty());
        lenient().when(sessionControlClient.stopSession(any())).thenReturn(Mono.empty());
    }

    @Test
    void routesAudioFrameToIngressPublisher() {
        StepVerifier.create(router.route("""
                {
                  "type": "audio.frame",
                  "sessionId": "sess-1",
                  "seq": 7,
                  "codec": "pcm16le",
                  "sampleRate": 16000,
                  "audioBase64": "AQID"
                }
                """, sessionId -> {
                }))
                .verifyComplete();

        ArgumentCaptor<AudioFrameIngressCommand> commandCaptor = ArgumentCaptor.forClass(AudioFrameIngressCommand.class);
        verify(audioIngressPublisher).publishRawFrame(commandCaptor.capture());
        AudioFrameIngressCommand command = commandCaptor.getValue();

        assertEquals("sess-1", command.sessionId());
        assertEquals(7L, command.seq());
        assertEquals("pcm16le", command.codec());
        assertEquals(16000, command.sampleRate());
        assertArrayEquals(new byte[] {1, 2, 3}, command.audioBytes());

        verify(sessionControlClient, never()).startSession(any());
        verify(sessionControlClient, never()).stopSession(any());
    }

    @Test
    void routesSessionStartToControlClient() {
        StepVerifier.create(router.route("""
                {
                  "type": "session.start",
                  "sessionId": "sess-2",
                  "tenantId": "tenant-a",
                  "sourceLang": "zh-CN",
                  "targetLang": "en-US",
                  "traceId": "trc-1"
                }
                """, sessionId -> {
                }))
                .verifyComplete();

        ArgumentCaptor<SessionStartCommand> commandCaptor = ArgumentCaptor.forClass(SessionStartCommand.class);
        verify(sessionControlClient).startSession(commandCaptor.capture());

        SessionStartCommand command = commandCaptor.getValue();
        assertEquals("sess-2", command.sessionId());
        assertEquals("tenant-a", command.tenantId());
        assertEquals("zh-CN", command.sourceLang());
        assertEquals("en-US", command.targetLang());
        assertEquals("trc-1", command.traceId());

        verify(audioIngressPublisher, never()).publishRawFrame(any());
    }

    @Test
    void routesSessionStopToControlClient() {
        StepVerifier.create(router.route("""
                {
                  "type": "session.stop",
                  "sessionId": "sess-3",
                  "traceId": "trc-2",
                  "reason": "client.stop"
                }
                """, sessionId -> {
                }))
                .verifyComplete();

        ArgumentCaptor<SessionStopCommand> commandCaptor = ArgumentCaptor.forClass(SessionStopCommand.class);
        verify(sessionControlClient).stopSession(commandCaptor.capture());

        SessionStopCommand command = commandCaptor.getValue();
        assertEquals("sess-3", command.sessionId());
        assertEquals("trc-2", command.traceId());
        assertEquals("client.stop", command.reason());

        verify(audioIngressPublisher, never()).publishRawFrame(any());
    }

    @Test
    void routesSessionPingWithoutSideEffects() {
        StepVerifier.create(router.route("""
                {
                  "type": "session.ping",
                  "sessionId": "sess-4",
                  "ts": 1713744001000
                }
                """, sessionId -> {
                }))
                .verifyComplete();

        verify(audioIngressPublisher, never()).publishRawFrame(any());
        verify(sessionControlClient, never()).startSession(any());
        verify(sessionControlClient, never()).stopSession(any());
    }

    @Test
    void rejectsUnsupportedMessageType() {
        StepVerifier.create(router.route("""
                {
                  "type": "session.unknown",
                  "sessionId": "sess-4"
                }
                """, sessionId -> {
                }))
                .expectErrorSatisfies(error -> {
                    MessageValidationException exception = assertInstanceOf(MessageValidationException.class, error);
                    assertEquals("INVALID_MESSAGE", exception.code());
                    assertEquals("sess-4", exception.sessionId());
                })
                .verify();
    }
}
