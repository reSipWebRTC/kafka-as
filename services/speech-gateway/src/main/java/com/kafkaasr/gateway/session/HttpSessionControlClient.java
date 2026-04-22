package com.kafkaasr.gateway.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.TimeoutException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.socket.CloseStatus;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "gateway.session-orchestrator.enabled", havingValue = "true", matchIfMissing = true)
public class HttpSessionControlClient implements SessionControlClient {

    private static final String SESSION_NOT_FOUND = "SESSION_NOT_FOUND";
    private static final String INVALID_MESSAGE = "INVALID_MESSAGE";
    private static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final GatewaySessionControlProperties properties;

    @Autowired
    public HttpSessionControlClient(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            GatewaySessionControlProperties properties) {
        this(webClientBuilder.baseUrl(properties.getBaseUrl()).build(), objectMapper, properties);
    }

    HttpSessionControlClient(
            WebClient webClient,
            ObjectMapper objectMapper,
            GatewaySessionControlProperties properties) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public Mono<Void> startSession(SessionStartCommand command) {
        return webClient
                .post()
                .uri("/api/v1/sessions:start")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new OrchestratorSessionStartRequest(
                        command.sessionId(),
                        command.tenantId(),
                        command.sourceLang(),
                        command.targetLang(),
                        command.traceId()))
                .exchangeToMono(response -> handleResponse(response, command.sessionId()))
                .timeout(properties.getTimeout())
                .onErrorMap(TimeoutException.class, exception -> SessionControlClientException.unavailable(
                        command.sessionId(),
                        exception))
                .onErrorMap(WebClientRequestException.class, exception -> SessionControlClientException.unavailable(
                        command.sessionId(),
                        exception));
    }

    @Override
    public Mono<Void> stopSession(SessionStopCommand command) {
        return webClient
                .post()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/sessions/{sessionId}:stop")
                        .build(command.sessionId()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new OrchestratorSessionStopRequest(command.traceId(), command.reason()))
                .exchangeToMono(response -> handleResponse(response, command.sessionId()))
                .timeout(properties.getTimeout())
                .onErrorMap(TimeoutException.class, exception -> SessionControlClientException.unavailable(
                        command.sessionId(),
                        exception))
                .onErrorMap(WebClientRequestException.class, exception -> SessionControlClientException.unavailable(
                        command.sessionId(),
                        exception));
    }

    private Mono<Void> handleResponse(ClientResponse response, String fallbackSessionId) {
        if (response.statusCode().is2xxSuccessful()) {
            return Mono.empty();
        }

        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .flatMap(errorBody -> Mono.error(mapException(response.statusCode(), errorBody, fallbackSessionId)));
    }

    private SessionControlClientException mapException(
            HttpStatusCode statusCode,
            String errorBody,
            String fallbackSessionId) {
        OrchestratorSessionErrorResponse errorResponse = parseError(errorBody);

        String mappedCode = coalesce(errorResponse == null ? null : errorResponse.code(), defaultCode(statusCode));
        String mappedMessage = coalesce(
                errorResponse == null ? null : errorResponse.message(),
                "Session control request failed with status " + statusCode.value());
        String mappedSessionId = coalesce(errorResponse == null ? null : errorResponse.sessionId(), fallbackSessionId);

        CloseStatus closeStatus = statusCode.is5xxServerError() ? CloseStatus.SERVER_ERROR : CloseStatus.BAD_DATA;
        return new SessionControlClientException(mappedCode, mappedMessage, mappedSessionId, closeStatus);
    }

    private OrchestratorSessionErrorResponse parseError(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }

        try {
            return objectMapper.readValue(body, OrchestratorSessionErrorResponse.class);
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private String defaultCode(HttpStatusCode statusCode) {
        if (statusCode.value() == 404) {
            return SESSION_NOT_FOUND;
        }
        if (statusCode.is4xxClientError()) {
            return INVALID_MESSAGE;
        }
        return INTERNAL_ERROR;
    }

    private String coalesce(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }
}
