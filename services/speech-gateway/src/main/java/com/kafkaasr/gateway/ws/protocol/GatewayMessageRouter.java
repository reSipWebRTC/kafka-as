package com.kafkaasr.gateway.ws.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.gateway.flow.GatewayAudioFrameFlowController;
import com.kafkaasr.gateway.ingress.AudioFrameIngressCommand;
import com.kafkaasr.gateway.ingress.AudioIngressPublisher;
import com.kafkaasr.gateway.ingress.CommandConfirmIngressCommand;
import com.kafkaasr.gateway.ingress.CommandConfirmRequestPublisher;
import com.kafkaasr.gateway.session.SessionControlClient;
import com.kafkaasr.gateway.session.SessionControlClientException;
import com.kafkaasr.gateway.session.SessionStartCommand;
import com.kafkaasr.gateway.session.SessionStopCommand;
import com.kafkaasr.gateway.ws.GatewayClientPlaybackMetrics;
import com.kafkaasr.gateway.ws.GatewayClientPerceivedMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
public class GatewayMessageRouter {

    private static final String AUDIO_FRAME_TYPE = "audio.frame";
    private static final String SESSION_START_TYPE = "session.start";
    private static final String SESSION_PING_TYPE = "session.ping";
    private static final String SESSION_STOP_TYPE = "session.stop";
    private static final String COMMAND_CONFIRM_TYPE = "command.confirm";
    private static final String PLAYBACK_METRIC_TYPE = "playback.metric";
    private static final String INVALID_MESSAGE_CODE = "INVALID_MESSAGE";
    private static final String SESSION_NOT_FOUND_CODE = "SESSION_NOT_FOUND";
    private static final String RATE_LIMITED_CODE = "RATE_LIMITED";
    private static final String BACKPRESSURE_DROP_CODE = "BACKPRESSURE_DROP";

    private final AudioIngressPublisher audioIngressPublisher;
    private final CommandConfirmRequestPublisher commandConfirmRequestPublisher;
    private final GatewayAudioFrameFlowController flowController;
    private final AudioFrameMessageDecoder audioFrameMessageDecoder;
    private final SessionStartMessageDecoder sessionStartMessageDecoder;
    private final SessionPingMessageDecoder sessionPingMessageDecoder;
    private final SessionStopMessageDecoder sessionStopMessageDecoder;
    private final CommandConfirmMessageDecoder commandConfirmMessageDecoder;
    private final PlaybackMetricMessageDecoder playbackMetricMessageDecoder;
    private final SessionControlClient sessionControlClient;
    private final GatewayClientPerceivedMetrics clientPerceivedMetrics;
    private final GatewayClientPlaybackMetrics clientPlaybackMetrics;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Map<String, SessionContext> sessionContexts = new ConcurrentHashMap<>();

    public GatewayMessageRouter(
            AudioIngressPublisher audioIngressPublisher,
            CommandConfirmRequestPublisher commandConfirmRequestPublisher,
            GatewayAudioFrameFlowController flowController,
            AudioFrameMessageDecoder audioFrameMessageDecoder,
            SessionStartMessageDecoder sessionStartMessageDecoder,
            SessionPingMessageDecoder sessionPingMessageDecoder,
            SessionStopMessageDecoder sessionStopMessageDecoder,
            CommandConfirmMessageDecoder commandConfirmMessageDecoder,
            PlaybackMetricMessageDecoder playbackMetricMessageDecoder,
            SessionControlClient sessionControlClient,
            GatewayClientPerceivedMetrics clientPerceivedMetrics,
            GatewayClientPlaybackMetrics clientPlaybackMetrics,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.audioIngressPublisher = audioIngressPublisher;
        this.commandConfirmRequestPublisher = commandConfirmRequestPublisher;
        this.flowController = flowController;
        this.audioFrameMessageDecoder = audioFrameMessageDecoder;
        this.sessionStartMessageDecoder = sessionStartMessageDecoder;
        this.sessionPingMessageDecoder = sessionPingMessageDecoder;
        this.sessionStopMessageDecoder = sessionStopMessageDecoder;
        this.commandConfirmMessageDecoder = commandConfirmMessageDecoder;
        this.playbackMetricMessageDecoder = playbackMetricMessageDecoder;
        this.sessionControlClient = sessionControlClient;
        this.clientPerceivedMetrics = clientPerceivedMetrics;
        this.clientPlaybackMetrics = clientPlaybackMetrics;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    public Mono<Void> route(String rawMessage, SessionBinder sessionBinder) {
        InboundEnvelope envelope;
        try {
            envelope = parseEnvelope(rawMessage);
        } catch (RuntimeException exception) {
            meterRegistry.counter(
                            "gateway.ws.messages.total",
                            "type",
                            "invalid",
                            "result",
                            "error",
                            "code",
                            normalizeErrorCode(exception))
                    .increment();
            throw exception;
        }

        String metricType = normalizeType(envelope.type());
        Timer.Sample sample = Timer.start(meterRegistry);

        Mono<Void> routeResult = switch (envelope.type()) {
            case AUDIO_FRAME_TYPE -> {
                AudioFrameIngressCommand request = audioFrameMessageDecoder.decode(rawMessage);
                sessionBinder.bind(request.sessionId());
                GatewayAudioFrameFlowController.FlowDecision decision = flowController.acquire(request.sessionId());
                if (decision == GatewayAudioFrameFlowController.FlowDecision.RATE_LIMITED) {
                    yield Mono.error(new MessageValidationException(
                            RATE_LIMITED_CODE,
                            "Audio frame rate limit exceeded",
                            request.sessionId()));
                }
                if (decision == GatewayAudioFrameFlowController.FlowDecision.BACKPRESSURE_DROP) {
                    yield Mono.error(new MessageValidationException(
                            BACKPRESSURE_DROP_CODE,
                            "Audio frame dropped due to gateway backpressure",
                            request.sessionId()));
                }

                SessionContext context = sessionContexts.get(request.sessionId());
                AudioFrameIngressCommand command = request;
                if (context != null) {
                    command = request.withSessionContext(
                            context.tenantId(),
                            context.userId(),
                            context.traceId());
                }

                yield audioIngressPublisher.publishRawFrame(command)
                        .doFinally(signalType -> flowController.release(request.sessionId()));
            }
            case SESSION_START_TYPE -> {
                SessionStartMessage request = sessionStartMessageDecoder.decode(rawMessage);
                sessionBinder.bind(request.sessionId());
                flowController.reset(request.sessionId());
                yield sessionControlClient.startSession(new SessionStartCommand(
                        request.sessionId(),
                        request.tenantId(),
                        request.sourceLang(),
                        request.targetLang(),
                        request.traceId()))
                        .doOnSuccess(unused -> sessionContexts.put(
                                request.sessionId(),
                                new SessionContext(
                                        request.tenantId(),
                                        request.userId(),
                                        request.traceId())))
                        .doOnSuccess(unused -> clientPerceivedMetrics.onSessionStarted(request.sessionId()));
            }
            case SESSION_PING_TYPE -> {
                SessionPingMessage request = sessionPingMessageDecoder.decode(rawMessage);
                sessionBinder.bind(request.sessionId());
                yield Mono.empty();
            }
            case SESSION_STOP_TYPE -> {
                SessionStopMessage request = sessionStopMessageDecoder.decode(rawMessage);
                sessionBinder.bind(request.sessionId());
                flowController.reset(request.sessionId());
                yield sessionControlClient.stopSession(new SessionStopCommand(
                        request.sessionId(),
                        request.traceId(),
                        request.reason()))
                        .doOnSuccess(unused -> clientPerceivedMetrics.onSessionClosed(request.sessionId()))
                        .doFinally(signalType -> sessionContexts.remove(request.sessionId()));
            }
            case COMMAND_CONFIRM_TYPE -> {
                CommandConfirmMessage request = commandConfirmMessageDecoder.decode(rawMessage);
                sessionBinder.bind(request.sessionId());
                SessionContext context = sessionContexts.get(request.sessionId());
                if (context == null) {
                    yield Mono.error(new MessageValidationException(
                            SESSION_NOT_FOUND_CODE,
                            "Session context not found for command.confirm",
                            request.sessionId()));
                }

                yield commandConfirmRequestPublisher.publish(new CommandConfirmIngressCommand(
                        request.sessionId(),
                        request.seq(),
                        coalesce(request.traceId(), context.traceId()),
                        context.tenantId(),
                        context.userId(),
                        request.confirmToken(),
                        request.accept()));
            }
            case PLAYBACK_METRIC_TYPE -> {
                PlaybackMetricMessage request = playbackMetricMessageDecoder.decode(rawMessage);
                sessionBinder.bind(request.sessionId());
                clientPlaybackMetrics.onPlaybackMetric(request);
                yield Mono.empty();
            }
            default -> Mono.error(new MessageValidationException(
                    INVALID_MESSAGE_CODE,
                    "Unsupported message type: " + envelope.type(),
                    envelope.sessionId()));
        };

        return routeResult
                .doOnSuccess(unused -> meterRegistry.counter(
                                "gateway.ws.messages.total",
                                "type",
                                metricType,
                                "result",
                                "success",
                                "code",
                                "OK")
                        .increment())
                .doOnError(exception -> meterRegistry.counter(
                                "gateway.ws.messages.total",
                                "type",
                                metricType,
                                "result",
                                "error",
                                "code",
                                normalizeErrorCode(exception))
                        .increment())
                .doFinally(signalType -> sample.stop(Timer.builder("gateway.ws.messages.duration")
                        .tag("type", metricType)
                        .register(meterRegistry)));
    }

    private InboundEnvelope parseEnvelope(String rawMessage) {
        try {
            JsonNode jsonNode = objectMapper.readTree(rawMessage);
            String sessionId = readText(jsonNode, "sessionId");
            String type = readText(jsonNode, "type");
            if (type.isBlank()) {
                throw new MessageValidationException(
                        INVALID_MESSAGE_CODE,
                        "Validation failed: type must not be blank",
                        sessionId);
            }
            return new InboundEnvelope(type, sessionId);
        } catch (JsonProcessingException exception) {
            throw new MessageValidationException(
                    INVALID_MESSAGE_CODE,
                    "Invalid JSON payload",
                    "",
                    exception);
        }
    }

    private String readText(JsonNode node, String fieldName) {
        JsonNode fieldNode = node.get(fieldName);
        if (fieldNode == null || fieldNode.isNull()) {
            return "";
        }
        return fieldNode.asText("");
    }

    private String normalizeType(String type) {
        return switch (type) {
            case AUDIO_FRAME_TYPE, SESSION_START_TYPE, SESSION_PING_TYPE, SESSION_STOP_TYPE, COMMAND_CONFIRM_TYPE, PLAYBACK_METRIC_TYPE -> type;
            default -> "unsupported";
        };
    }

    private String normalizeErrorCode(Throwable throwable) {
        if (throwable instanceof MessageValidationException exception) {
            return exception.code();
        }
        if (throwable instanceof SessionControlClientException exception) {
            return exception.code();
        }
        if (throwable instanceof IllegalArgumentException) {
            return INVALID_MESSAGE_CODE;
        }
        return "INTERNAL_ERROR";
    }

    private record InboundEnvelope(String type, String sessionId) {
    }

    private String coalesce(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private record SessionContext(String tenantId, String userId, String traceId) {
    }

    @FunctionalInterface
    public interface SessionBinder {
        void bind(String sessionId);
    }
}
