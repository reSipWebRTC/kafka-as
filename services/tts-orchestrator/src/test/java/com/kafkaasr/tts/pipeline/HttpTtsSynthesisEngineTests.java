package com.kafkaasr.tts.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.tts.events.TranslationResultEvent;
import com.kafkaasr.tts.events.TranslationResultPayload;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class HttpTtsSynthesisEngineTests {

    @Test
    void synthesizeUsesHttpResponseAndAuthorizationHeader() {
        AtomicReference<ClientRequest> capturedRequest = new AtomicReference<>();
        ExchangeFunction exchangeFunction = request -> {
            capturedRequest.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body("{\"text\":\"hello world\",\"language\":\"en-US\",\"voice\":\"en-US-neural-b\",\"stream\":false}")
                    .build());
        };

        HttpTtsSynthesisEngine engine = new HttpTtsSynthesisEngine(
                httpProperties("token-tts"),
                WebClient.builder().exchangeFunction(exchangeFunction),
                new ObjectMapper(),
                new SimpleMeterRegistry());

        TtsSynthesisEngine.SynthesisPlan plan = engine.synthesize(
                sampleEvent(),
                new TtsSynthesisEngine.SynthesisInput("hello", "en-US", "en-US-neural-a", true));

        assertEquals("hello world", plan.text());
        assertEquals("en-US", plan.language());
        assertEquals("en-US-neural-b", plan.voice());
        assertEquals(false, plan.stream());
        assertEquals(HttpMethod.POST, capturedRequest.get().method());
        assertEquals("/v1/tts/synthesize", capturedRequest.get().url().getPath());
        assertEquals("Bearer token-tts", capturedRequest.get().headers().getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void synthesizeMapsProviderFailureWhenUpstreamThrows() {
        ExchangeFunction exchangeFunction = request -> Mono.error(new RuntimeException("upstream unavailable"));

        HttpTtsSynthesisEngine engine = new HttpTtsSynthesisEngine(
                httpProperties(""),
                WebClient.builder().exchangeFunction(exchangeFunction),
                new ObjectMapper(),
                new SimpleMeterRegistry());

        TtsSynthesisException exception = assertThrows(
                TtsSynthesisException.class,
                () -> engine.synthesize(
                        sampleEvent(),
                        new TtsSynthesisEngine.SynthesisInput("hello", "en-US", "en-US-neural-a", true)));
        assertEquals("TTS_PROVIDER_FAILURE", exception.errorCode());
        assertTrue(exception.retryable());
    }

    @Test
    void synthesizeOmitsAuthorizationHeaderWhenTokenBlank() {
        AtomicReference<ClientRequest> capturedRequest = new AtomicReference<>();
        ExchangeFunction exchangeFunction = request -> {
            capturedRequest.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body("{\"voice\":\"en-US-neural-c\"}")
                    .build());
        };

        HttpTtsSynthesisEngine engine = new HttpTtsSynthesisEngine(
                httpProperties(""),
                WebClient.builder().exchangeFunction(exchangeFunction),
                new ObjectMapper(),
                new SimpleMeterRegistry());

        engine.synthesize(
                sampleEvent(),
                new TtsSynthesisEngine.SynthesisInput("hello", "en-US", "en-US-neural-a", true));

        assertNull(capturedRequest.get().headers().getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void synthesizeParsesDataShapeAndBooleanLikeStream() {
        ExchangeFunction exchangeFunction = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body("{\"code\":\"0\",\"data\":{\"result_text\":\"hello synth\",\"locale\":\"en-US\",\"voice_id\":\"en-US-neural-c\",\"is_stream\":\"0\"}}")
                .build());

        HttpTtsSynthesisEngine engine = new HttpTtsSynthesisEngine(
                httpProperties(""),
                WebClient.builder().exchangeFunction(exchangeFunction),
                new ObjectMapper(),
                new SimpleMeterRegistry());

        TtsSynthesisEngine.SynthesisPlan plan = engine.synthesize(
                sampleEvent(),
                new TtsSynthesisEngine.SynthesisInput("hello", "en-US", "en-US-neural-a", true));

        assertEquals("hello synth", plan.text());
        assertEquals("en-US", plan.language());
        assertEquals("en-US-neural-c", plan.voice());
        assertEquals(false, plan.stream());
    }

    @Test
    void synthesizeRejectsProviderErrorPayload() {
        ExchangeFunction exchangeFunction = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body("{\"error\":{\"message\":\"quota exceeded\"}}")
                .build());

        HttpTtsSynthesisEngine engine = new HttpTtsSynthesisEngine(
                httpProperties(""),
                WebClient.builder().exchangeFunction(exchangeFunction),
                new ObjectMapper(),
                new SimpleMeterRegistry());

        TtsSynthesisException exception = assertThrows(
                TtsSynthesisException.class,
                () -> engine.synthesize(
                        sampleEvent(),
                        new TtsSynthesisEngine.SynthesisInput("hello", "en-US", "en-US-neural-a", true)));
        assertEquals("TTS_PROVIDER_RATE_LIMIT", exception.errorCode());
        assertTrue(exception.retryable());
    }

    @Test
    void synthesizeRejectsFailedProviderStatus() {
        ExchangeFunction exchangeFunction = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body("{\"status\":\"failed\",\"text\":\"hello\"}")
                .build());

        HttpTtsSynthesisEngine engine = new HttpTtsSynthesisEngine(
                httpProperties(""),
                WebClient.builder().exchangeFunction(exchangeFunction),
                new ObjectMapper(),
                new SimpleMeterRegistry());

        TtsSynthesisException exception = assertThrows(
                TtsSynthesisException.class,
                () -> engine.synthesize(
                        sampleEvent(),
                        new TtsSynthesisEngine.SynthesisInput("hello", "en-US", "en-US-neural-a", true)));
        assertEquals("TTS_PROVIDER_FAILURE", exception.errorCode());
        assertTrue(exception.retryable());
    }

    @Test
    void synthesizeFailsFastWhenHealthCheckIsDown() {
        TtsSynthesisProperties properties = httpProperties("");
        properties.getHttp().getHealth().setEnabled(true);
        properties.getHttp().getHealth().setFailOpenOnError(false);
        properties.getHttp().getHealth().setPath("/health");

        ExchangeFunction exchangeFunction = request -> {
            if ("/health".equals(request.url().getPath())) {
                return Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .body("{\"status\":\"DOWN\"}")
                        .build());
            }
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body("{\"text\":\"hello\"}")
                    .build());
        };

        HttpTtsSynthesisEngine engine = new HttpTtsSynthesisEngine(
                properties,
                WebClient.builder().exchangeFunction(exchangeFunction),
                new ObjectMapper(),
                new SimpleMeterRegistry());

        TtsSynthesisException exception = assertThrows(
                TtsSynthesisException.class,
                () -> engine.synthesize(
                        sampleEvent(),
                        new TtsSynthesisEngine.SynthesisInput("hello", "en-US", "en-US-neural-a", true)));
        assertEquals("TTS_PROVIDER_UNHEALTHY", exception.errorCode());
    }

    @Test
    void synthesizeMapsTimeoutAsTtsTimeout() {
        TtsSynthesisProperties properties = httpProperties("");
        properties.getHttp().setTimeoutMs(50L);

        ExchangeFunction exchangeFunction = request -> Mono.never();

        HttpTtsSynthesisEngine engine = new HttpTtsSynthesisEngine(
                properties,
                WebClient.builder().exchangeFunction(exchangeFunction),
                new ObjectMapper(),
                new SimpleMeterRegistry());

        TtsSynthesisException exception = assertThrows(
                TtsSynthesisException.class,
                () -> engine.synthesize(
                        sampleEvent(),
                        new TtsSynthesisEngine.SynthesisInput("hello", "en-US", "en-US-neural-a", true)));
        assertEquals("TTS_TIMEOUT", exception.errorCode());
        assertTrue(exception.retryable());
    }

    @Test
    void synthesizeRejectsWhenConcurrencyLimitReached() throws Exception {
        TtsSynthesisProperties properties = httpProperties("");
        properties.getHttp().setMaxConcurrentRequests(1);
        CountDownLatch firstRequestStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstRequest = new CountDownLatch(1);

        ExchangeFunction exchangeFunction = request -> Mono.create(sink -> {
            firstRequestStarted.countDown();
            try {
                if (!releaseFirstRequest.await(2, TimeUnit.SECONDS)) {
                    sink.error(new IllegalStateException("timed out waiting test release"));
                    return;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                sink.error(exception);
                return;
            }
            sink.success(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body("{\"text\":\"hello\"}")
                    .build());
        });

        HttpTtsSynthesisEngine engine = new HttpTtsSynthesisEngine(
                properties,
                WebClient.builder().exchangeFunction(exchangeFunction),
                new ObjectMapper(),
                new SimpleMeterRegistry());

        CompletableFuture<Void> firstCall = CompletableFuture.runAsync(() -> engine.synthesize(
                sampleEvent(),
                new TtsSynthesisEngine.SynthesisInput("hello", "en-US", "en-US-neural-a", true)));
        assertTrue(firstRequestStarted.await(1, TimeUnit.SECONDS));

        TtsSynthesisException exception = assertThrows(
                TtsSynthesisException.class,
                () -> engine.synthesize(
                        sampleEvent(),
                        new TtsSynthesisEngine.SynthesisInput("hello", "en-US", "en-US-neural-a", true)));
        assertEquals("TTS_PROVIDER_BUSY", exception.errorCode());
        assertTrue(exception.retryable());

        releaseFirstRequest.countDown();
        firstCall.get(2, TimeUnit.SECONDS);
    }

    private static TtsSynthesisProperties httpProperties(String authToken) {
        TtsSynthesisProperties properties = new TtsSynthesisProperties();
        properties.setMode("http");
        TtsSynthesisProperties.Http http = properties.getHttp();
        http.setEndpoint("http://tts-engine.local");
        http.setPath("/v1/tts/synthesize");
        http.setTimeoutMs(2000L);
        http.setMaxConcurrentRequests(16);
        http.setAuthToken(authToken);
        return properties;
    }

    private static TranslationResultEvent sampleEvent() {
        return new TranslationResultEvent(
                "evt-in-1",
                "translation.result",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                "room-1",
                "translation-worker",
                9L,
                1713744000000L,
                "sess-1:translation.result:9",
                new TranslationResultPayload("你好", "hello", "zh-CN", "en-US", "mt-v2"));
    }
}
