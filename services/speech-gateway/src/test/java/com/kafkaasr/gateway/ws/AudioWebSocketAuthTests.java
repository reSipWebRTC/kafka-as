package com.kafkaasr.gateway.ws;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "gateway.kafka.enabled=false",
                "gateway.downlink.enabled=false",
                "gateway.auth.enabled=true",
                "gateway.auth.tokens=test-gateway-token"
        })
class AudioWebSocketAuthTests {

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(5);

    @LocalServerPort
    private int port;

    @Test
    void rejectsConnectionWhenTokenMissing() {
        ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();
        AtomicReference<String> errorPayload = new AtomicReference<>();

        Mono<Void> execution = client.execute(wsUri("/ws/audio"), session -> session.receive()
                .next()
                .doOnNext(message -> errorPayload.set(message.getPayloadAsText()))
                .then());

        StepVerifier.create(execution)
                .expectComplete()
                .verify(TEST_TIMEOUT);

        assertNotNull(errorPayload.get());
        assertTrue(errorPayload.get().contains("\"type\":\"session.error\""));
        assertTrue(errorPayload.get().contains("\"code\":\"AUTH_INVALID_TOKEN\""));
    }

    @Test
    void allowsConnectionWhenTokenIsProvidedInQueryParam() {
        ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient();

        Mono<Void> execution = client.execute(
                wsUri("/ws/audio?access_token=test-gateway-token"),
                session -> session.close());

        StepVerifier.create(execution)
                .expectComplete()
                .verify(TEST_TIMEOUT);
    }

    private URI wsUri(String path) {
        return URI.create("ws://localhost:" + port + path);
    }
}
