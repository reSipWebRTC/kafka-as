package com.kafkaasr.command.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class HttpSmartHomeClientTests {

    @Mock
    private ExchangeFunction exchangeFunction;

    private HttpSmartHomeClient client;

    @BeforeEach
    void setUp() {
        SmartHomeClientProperties properties = new SmartHomeClientProperties();
        properties.setBaseUrl("http://127.0.0.1:8000");
        properties.setCommandPath("/api/v1/command");
        properties.setConfirmPath("/api/v1/confirm");
        properties.setTimeoutMs(1500);

        WebClient webClient = WebClient.builder()
                .baseUrl(properties.getBaseUrl())
                .exchangeFunction(exchangeFunction)
                .build();

        client = new HttpSmartHomeClient(webClient, new com.fasterxml.jackson.databind.ObjectMapper(), properties);
    }

    @Test
    void parsesCommandSuccessResponse() {
        when(exchangeFunction.exchange(any()))
                .thenReturn(Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("""
                                {
                                  "trace_id": "trc-1",
                                  "code": "OK",
                                  "message": "success",
                                  "retryable": false,
                                  "data": {
                                    "status": "ok",
                                    "reply_text": "Done",
                                    "intent": "CONTROL",
                                    "sub_intent": "switch.on"
                                  }
                                }
                                """)
                        .build()));

        SmartHomeApiResponse response = client.executeCommand("sess-1", "user-1", "turn on light", "trc-gateway");

        assertEquals("trc-1", response.traceId());
        assertEquals("OK", response.code());
        assertEquals("ok", response.status());
        assertEquals("Done", response.replyText());
        assertEquals("CONTROL", response.intent());
        assertEquals("switch.on", response.subIntent());
    }

    @Test
    void parsesConfirmRequiredResponse() {
        when(exchangeFunction.exchange(any()))
                .thenReturn(Mono.just(ClientResponse.create(HttpStatus.CONFLICT)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .body("""
                                {
                                  "trace_id": "trc-2",
                                  "code": "POLICY_CONFIRM_REQUIRED",
                                  "message": "confirmation required",
                                  "retryable": false,
                                  "data": {
                                    "status": "confirm_required",
                                    "reply_text": "Please confirm",
                                    "confirm_token": "cfm-1",
                                    "expires_in_sec": 30
                                  }
                                }
                                """)
                        .build()));

        SmartHomeApiResponse response = client.executeCommand("sess-2", "user-2", "unlock door", "trc-gateway");

        assertEquals("POLICY_CONFIRM_REQUIRED", response.code());
        assertEquals("confirm_required", response.status());
        assertEquals("cfm-1", response.confirmToken());
        assertEquals(30, response.expiresInSec());
        assertEquals(false, response.retryable());
    }

    @Test
    void mapsTransportFailureToRetryableException() {
        when(exchangeFunction.exchange(any()))
                .thenReturn(Mono.error(new WebClientRequestException(
                        new IOException("connection refused"),
                        HttpMethod.POST,
                        URI.create("http://127.0.0.1:8000/api/v1/command"),
                        HttpHeaders.EMPTY)));

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> client.executeCommand("sess-3", "user-3", "turn on light", "trc-gateway"));
        SmartHomeClientException exception = assertInstanceOf(SmartHomeClientException.class, thrown);
        assertEquals("UPSTREAM_TIMEOUT", exception.errorCode());
        assertEquals(true, exception.retryable());
    }
}
