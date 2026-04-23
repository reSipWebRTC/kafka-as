package com.kafkaasr.asr.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.asr.events.AudioIngressRawEvent;
import com.kafkaasr.asr.events.AudioIngressRawPayload;
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

class FunasrAsrInferenceEngineTests {

    @Test
    void inferParsesArrayResultAndUsesAuthorizationHeader() {
        AtomicReference<ClientRequest> capturedRequest = new AtomicReference<>();
        ExchangeFunction exchangeFunction = request -> {
            capturedRequest.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body("{\"result\":[{\"text\":\"你好世界\"}],\"lang\":\"zh-CN\",\"confidence\":0.91,\"is_final\":true}")
                    .build());
        };

        FunasrAsrInferenceEngine engine = new FunasrAsrInferenceEngine(
                funasrProperties("token-funasr"),
                WebClient.builder().exchangeFunction(exchangeFunction),
                new ObjectMapper(),
                new SimpleMeterRegistry());

        AsrInferenceEngine.AsrInferenceResult result = engine.infer(sampleIngressEvent(false));

        assertEquals("你好世界", result.text());
        assertEquals("zh-CN", result.language());
        assertEquals(0.91d, result.confidence());
        assertTrue(result.stable());
        assertEquals(HttpMethod.POST, capturedRequest.get().method());
        assertEquals("/v1/asr", capturedRequest.get().url().getPath());
        assertEquals("Bearer token-funasr", capturedRequest.get().headers().getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void inferUsesFallbackConfidenceAndStableWhenOmitted() {
        ExchangeFunction exchangeFunction = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body("{\"data\":{\"transcript\":\"hello\",\"language\":\"en-US\"}}")
                .build());

        FunasrAsrInferenceEngine engine = new FunasrAsrInferenceEngine(
                funasrProperties(""),
                WebClient.builder().exchangeFunction(exchangeFunction),
                new ObjectMapper(),
                new SimpleMeterRegistry());

        AsrInferenceEngine.AsrInferenceResult result = engine.infer(sampleIngressEvent(false));

        assertEquals("hello", result.text());
        assertEquals("en-US", result.language());
        assertEquals(0.6d, result.confidence());
        assertFalse(result.stable());
    }

    @Test
    void inferRejectsEmptyTranscriptResponse() {
        ExchangeFunction exchangeFunction = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body("{\"result\":[]}")
                .build());

        FunasrAsrInferenceEngine engine = new FunasrAsrInferenceEngine(
                funasrProperties(""),
                WebClient.builder().exchangeFunction(exchangeFunction),
                new ObjectMapper(),
                new SimpleMeterRegistry());

        AsrEngineException exception = assertThrows(
                AsrEngineException.class,
                () -> engine.infer(sampleIngressEvent(true)));
        assertEquals("ASR_PROVIDER_EMPTY_TRANSCRIPT", exception.errorCode());
        assertTrue(exception.getMessage().contains("Empty FunASR transcript"));
    }

    @Test
    void inferRejectsProviderBusinessErrorCode() {
        ExchangeFunction exchangeFunction = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body("{\"code\":500,\"msg\":\"engine overloaded\"}")
                .build());

        FunasrAsrInferenceEngine engine = new FunasrAsrInferenceEngine(
                funasrProperties(""),
                WebClient.builder().exchangeFunction(exchangeFunction),
                new ObjectMapper(),
                new SimpleMeterRegistry());

        AsrEngineException exception = assertThrows(
                AsrEngineException.class,
                () -> engine.infer(sampleIngressEvent(false)));
        assertEquals("ASR_PROVIDER_FAILURE", exception.errorCode());
        assertTrue(exception.retryable());
    }

    @Test
    void inferParsesSentenceArrayAndBooleanLikeFields() {
        ExchangeFunction exchangeFunction = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body("{\"status\":\"0\",\"sentences\":[{\"sentence\":\"hello world\",\"confidence\":\"0.82\",\"is_final\":1}],\"lang\":\"en-US\"}")
                .build());

        FunasrAsrInferenceEngine engine = new FunasrAsrInferenceEngine(
                funasrProperties(""),
                WebClient.builder().exchangeFunction(exchangeFunction),
                new ObjectMapper(),
                new SimpleMeterRegistry());

        AsrInferenceEngine.AsrInferenceResult result = engine.infer(sampleIngressEvent(false));

        assertEquals("hello world", result.text());
        assertEquals("en-US", result.language());
        assertEquals(0.82d, result.confidence());
        assertTrue(result.stable());
    }

    @Test
    void inferFailsFastWhenHealthCheckIsDown() {
        AsrInferenceProperties properties = funasrProperties("");
        properties.getFunasr().getHealth().setEnabled(true);
        properties.getFunasr().getHealth().setFailOpenOnError(false);
        properties.getFunasr().getHealth().setPath("/health");

        ExchangeFunction exchangeFunction = request -> {
            if ("/health".equals(request.url().getPath())) {
                return Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .body("{\"status\":\"DOWN\"}")
                        .build());
            }
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body("{\"result\":[{\"text\":\"hello\"}],\"is_final\":true}")
                    .build());
        };

        FunasrAsrInferenceEngine engine = new FunasrAsrInferenceEngine(
                properties,
                WebClient.builder().exchangeFunction(exchangeFunction),
                new ObjectMapper(),
                new SimpleMeterRegistry());

        AsrEngineException exception = assertThrows(
                AsrEngineException.class,
                () -> engine.infer(sampleIngressEvent(false)));
        assertEquals("ASR_PROVIDER_UNHEALTHY", exception.errorCode());
    }

    @Test
    void inferMapsTimeoutAsAsrTimeout() {
        AsrInferenceProperties properties = funasrProperties("");
        properties.getFunasr().setTimeoutMs(50L);

        ExchangeFunction exchangeFunction = request -> Mono.never();

        FunasrAsrInferenceEngine engine = new FunasrAsrInferenceEngine(
                properties,
                WebClient.builder().exchangeFunction(exchangeFunction),
                new ObjectMapper(),
                new SimpleMeterRegistry());

        AsrEngineException exception = assertThrows(
                AsrEngineException.class,
                () -> engine.infer(sampleIngressEvent(false)));
        assertEquals("ASR_TIMEOUT", exception.errorCode());
        assertTrue(exception.retryable());
    }

    @Test
    void inferRejectsWhenConcurrencyLimitReached() throws Exception {
        AsrInferenceProperties properties = funasrProperties("");
        properties.getFunasr().setMaxConcurrentRequests(1);
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
                    .body("{\"result\":[{\"text\":\"hello\"}],\"is_final\":true}")
                    .build());
        });

        FunasrAsrInferenceEngine engine = new FunasrAsrInferenceEngine(
                properties,
                WebClient.builder().exchangeFunction(exchangeFunction),
                new ObjectMapper(),
                new SimpleMeterRegistry());

        CompletableFuture<Void> firstCall = CompletableFuture.runAsync(() -> engine.infer(sampleIngressEvent(false)));
        assertTrue(firstRequestStarted.await(1, TimeUnit.SECONDS));

        AsrEngineException exception = assertThrows(
                AsrEngineException.class,
                () -> engine.infer(sampleIngressEvent(false)));
        assertEquals("ASR_PROVIDER_BUSY", exception.errorCode());
        assertTrue(exception.retryable());

        releaseFirstRequest.countDown();
        firstCall.get(2, TimeUnit.SECONDS);
    }

    private static AsrInferenceProperties funasrProperties(String authToken) {
        AsrInferenceProperties properties = new AsrInferenceProperties();
        properties.setMode("funasr");
        AsrInferenceProperties.Funasr funasr = properties.getFunasr();
        funasr.setEndpoint("http://funasr.local");
        funasr.setPath("/v1/asr");
        funasr.setTimeoutMs(2500L);
        funasr.setAuthToken(authToken);
        funasr.setModel("paraformer-zh");
        funasr.setLanguage("auto");
        funasr.setAudioFormat("pcm");
        funasr.setDefaultSampleRate(16000);
        funasr.setEnableInverseTextNormalization(true);
        funasr.setMaxConcurrentRequests(16);
        return properties;
    }

    private static AudioIngressRawEvent sampleIngressEvent(boolean endOfStream) {
        return new AudioIngressRawEvent(
                "evt-in-1",
                "audio.ingress.raw",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                "room-1",
                "speech-gateway",
                1L,
                1713744000000L,
                "sess-1:audio.ingress.raw:1",
                new AudioIngressRawPayload("pcm16le", 16000, 1, "AQID", endOfStream));
    }
}
