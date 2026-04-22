package com.kafkaasr.gateway.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.socket.CloseStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class HttpSessionControlClientTests {

    @Mock
    private ExchangeFunction exchangeFunction;

    private HttpSessionControlClient client;

    @BeforeEach
    void setUp() {
        GatewaySessionControlProperties properties = new GatewaySessionControlProperties();
        properties.setBaseUrl("http://session-orchestrator.test");
        properties.setTimeout(Duration.ofSeconds(1));

        WebClient webClient = WebClient.builder()
                .baseUrl(properties.getBaseUrl())
                .exchangeFunction(exchangeFunction)
                .build();

        client = new HttpSessionControlClient(webClient, new ObjectMapper(), properties);
    }

    @Test
    void startSessionCallsOrchestratorStartEndpoint() {
        when(exchangeFunction.exchange(any()))
                .thenReturn(Mono.just(ClientResponse.create(HttpStatus.OK).build()));

        StepVerifier.create(client.startSession(new SessionStartCommand(
                        "sess-1",
                        "tenant-a",
                        "zh-CN",
                        "en-US",
                        "trc-1")))
                .verifyComplete();

        ArgumentCaptor<ClientRequest> requestCaptor = ArgumentCaptor.forClass(ClientRequest.class);
        verify(exchangeFunction).exchange(requestCaptor.capture());

        ClientRequest request = requestCaptor.getValue();
        assertEquals(HttpMethod.POST, request.method());
        assertEquals("/api/v1/sessions:start", request.url().getPath());
        assertEquals(MediaType.APPLICATION_JSON, request.headers().getContentType());
    }

    @Test
    void stopSessionMapsStructuredNotFoundError() {
        when(exchangeFunction.exchange(any()))
                .thenReturn(Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("""
                                {
                                  \"code\": \"SESSION_NOT_FOUND\",
                                  \"message\": \"Session does not exist: sess-2\",
                                  \"sessionId\": \"sess-2\"
                                }
                                """)
                        .build()));

        StepVerifier.create(client.stopSession(new SessionStopCommand("sess-2", "trc-2", "client.stop")))
                .expectErrorSatisfies(error -> {
                    SessionControlClientException exception = assertInstanceOf(SessionControlClientException.class, error);
                    assertEquals("SESSION_NOT_FOUND", exception.code());
                    assertEquals("Session does not exist: sess-2", exception.getMessage());
                    assertEquals("sess-2", exception.sessionId());
                    assertEquals(CloseStatus.BAD_DATA, exception.closeStatus());
                })
                .verify();
    }

    @Test
    void startSessionMapsTransportErrorsToInternalError() {
        when(exchangeFunction.exchange(any()))
                .thenReturn(Mono.error(new WebClientRequestException(
                        new IOException("connection refused"),
                        HttpMethod.POST,
                        URI.create("http://session-orchestrator.test/api/v1/sessions:start"),
                        HttpHeaders.EMPTY)));

        StepVerifier.create(client.startSession(new SessionStartCommand(
                        "sess-3",
                        "tenant-a",
                        "zh-CN",
                        "en-US",
                        "trc-3")))
                .expectErrorSatisfies(error -> {
                    SessionControlClientException exception = assertInstanceOf(SessionControlClientException.class, error);
                    assertEquals("INTERNAL_ERROR", exception.code());
                    assertEquals("Session control service unavailable", exception.getMessage());
                    assertEquals("sess-3", exception.sessionId());
                    assertEquals(CloseStatus.SERVER_ERROR, exception.closeStatus());
                })
                .verify();
    }

    @Test
    void stopSessionMapsServerErrorsWhenBodyIsUnavailable() {
        when(exchangeFunction.exchange(any()))
                .thenReturn(Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build()));

        StepVerifier.create(client.stopSession(new SessionStopCommand("sess-4", null, null)))
                .expectErrorSatisfies(error -> {
                    SessionControlClientException exception = assertInstanceOf(SessionControlClientException.class, error);
                    assertEquals("INTERNAL_ERROR", exception.code());
                    assertEquals("sess-4", exception.sessionId());
                    assertEquals(CloseStatus.SERVER_ERROR, exception.closeStatus());
                })
                .verify();
    }
}
