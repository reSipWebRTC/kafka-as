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
import com.kafkaasr.gateway.flow.GatewayAudioFrameFlowController;
import com.kafkaasr.gateway.flow.GatewayFlowControlProperties;
import com.kafkaasr.gateway.ingress.AudioFrameIngressCommand;
import com.kafkaasr.gateway.ingress.AudioIngressPublisher;
import com.kafkaasr.gateway.ingress.CommandConfirmIngressCommand;
import com.kafkaasr.gateway.ingress.CommandConfirmRequestPublisher;
import com.kafkaasr.gateway.session.SessionControlClient;
import com.kafkaasr.gateway.session.SessionStartCommand;
import com.kafkaasr.gateway.session.SessionStopCommand;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class GatewayMessageRouterTests {

    @Mock
    private AudioIngressPublisher audioIngressPublisher;

    @Mock
    private CommandConfirmRequestPublisher commandConfirmRequestPublisher;

    @Mock
    private SessionControlClient sessionControlClient;

    private GatewayMessageRouter router;

    @BeforeEach
    void setUp() {
        router = buildRouter(50, 8, Clock.systemUTC());
    }

    private GatewayMessageRouter buildRouter(
            int rateLimitPerSecond,
            int maxInflight,
            Clock clock) {
        ObjectMapper objectMapper = new ObjectMapper();
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        GatewayFlowControlProperties flowControlProperties = new GatewayFlowControlProperties();
        flowControlProperties.setAudioFrameRateLimitPerSecond(rateLimitPerSecond);
        flowControlProperties.setAudioFrameMaxInflight(maxInflight);

        GatewayMessageRouter gatewayMessageRouter = new GatewayMessageRouter(
                audioIngressPublisher,
                commandConfirmRequestPublisher,
                new GatewayAudioFrameFlowController(flowControlProperties, clock),
                new AudioFrameMessageDecoder(objectMapper, validator),
                new SessionStartMessageDecoder(objectMapper, validator),
                new SessionPingMessageDecoder(objectMapper, validator),
                new SessionStopMessageDecoder(objectMapper, validator),
                new CommandConfirmMessageDecoder(objectMapper, validator),
                sessionControlClient,
                objectMapper,
                new SimpleMeterRegistry());

        lenient().when(audioIngressPublisher.publishRawFrame(any())).thenReturn(Mono.empty());
        lenient().when(commandConfirmRequestPublisher.publish(any())).thenReturn(Mono.empty());
        lenient().when(sessionControlClient.startSession(any())).thenReturn(Mono.empty());
        lenient().when(sessionControlClient.stopSession(any())).thenReturn(Mono.empty());

        return gatewayMessageRouter;
    }

    @Test
    void routesAudioFrameToIngressPublisher() {
        StepVerifier.create(router.route(audioFrameMessage("sess-1", 7), sessionId -> {
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
                  "userId": "user-a",
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
        verify(commandConfirmRequestPublisher, never()).publish(any());
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
        verify(commandConfirmRequestPublisher, never()).publish(any());
    }

    @Test
    void routesCommandConfirmToIngressPublisherWithSessionContext() {
        StepVerifier.create(router.route("""
                {
                  "type": "session.start",
                  "sessionId": "sess-cmd-1",
                  "tenantId": "tenant-home",
                  "userId": "user-home",
                  "sourceLang": "zh-CN",
                  "targetLang": "en-US",
                  "traceId": "trc-start"
                }
                """, sessionId -> {
                }))
                .verifyComplete();

        StepVerifier.create(router.route("""
                {
                  "type": "command.confirm",
                  "sessionId": "sess-cmd-1",
                  "seq": 42,
                  "confirmToken": "cfm-001",
                  "accept": true
                }
                """, sessionId -> {
                }))
                .verifyComplete();

        ArgumentCaptor<CommandConfirmIngressCommand> commandCaptor =
                ArgumentCaptor.forClass(CommandConfirmIngressCommand.class);
        verify(commandConfirmRequestPublisher).publish(commandCaptor.capture());
        CommandConfirmIngressCommand command = commandCaptor.getValue();

        assertEquals("sess-cmd-1", command.sessionId());
        assertEquals(42L, command.seq());
        assertEquals("tenant-home", command.tenantId());
        assertEquals("user-home", command.userId());
        assertEquals("trc-start", command.traceId());
        assertEquals("cfm-001", command.confirmToken());
    }

    @Test
    void rejectsCommandConfirmWhenSessionContextMissing() {
        StepVerifier.create(router.route("""
                {
                  "type": "command.confirm",
                  "sessionId": "sess-missing",
                  "seq": 1,
                  "confirmToken": "cfm-404",
                  "accept": false
                }
                """, sessionId -> {
                }))
                .expectErrorSatisfies(error -> {
                    MessageValidationException exception = assertInstanceOf(MessageValidationException.class, error);
                    assertEquals("SESSION_NOT_FOUND", exception.code());
                    assertEquals("sess-missing", exception.sessionId());
                })
                .verify();

        verify(commandConfirmRequestPublisher, never()).publish(any());
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

    @Test
    void rejectsAudioFrameWhenRateLimited() {
        router = buildRouter(
                1,
                8,
                Clock.fixed(Instant.parse("2026-04-22T12:00:00Z"), ZoneOffset.UTC));

        StepVerifier.create(router.route(audioFrameMessage("sess-6", 1), sessionId -> {
                }))
                .verifyComplete();

        StepVerifier.create(router.route(audioFrameMessage("sess-6", 2), sessionId -> {
                }))
                .expectErrorSatisfies(error -> {
                    MessageValidationException exception = assertInstanceOf(MessageValidationException.class, error);
                    assertEquals("RATE_LIMITED", exception.code());
                    assertEquals("sess-6", exception.sessionId());
                })
                .verify();
    }

    @Test
    void rejectsAudioFrameWhenBackpressureThresholdExceeded() {
        router = buildRouter(50, 1, Clock.systemUTC());

        Sinks.Empty<Void> firstPublishSignal = Sinks.empty();
        when(audioIngressPublisher.publishRawFrame(any())).thenReturn(firstPublishSignal.asMono());

        Disposable firstSubscription = router.route(audioFrameMessage("sess-7", 1), sessionId -> {
                })
                .subscribe();
        try {
            StepVerifier.create(router.route(audioFrameMessage("sess-7", 2), sessionId -> {
                    }))
                    .expectErrorSatisfies(error -> {
                        MessageValidationException exception = assertInstanceOf(MessageValidationException.class, error);
                        assertEquals("BACKPRESSURE_DROP", exception.code());
                        assertEquals("sess-7", exception.sessionId());
                    })
                    .verify();
        } finally {
            firstPublishSignal.tryEmitEmpty();
            firstSubscription.dispose();
        }
    }

    private String audioFrameMessage(String sessionId, long seq) {
        return """
                {
                  "type": "audio.frame",
                  "sessionId": "%s",
                  "seq": %d,
                  "codec": "pcm16le",
                  "sampleRate": 16000,
                  "audioBase64": "AQID"
                }
                """.formatted(sessionId, seq);
    }
}
