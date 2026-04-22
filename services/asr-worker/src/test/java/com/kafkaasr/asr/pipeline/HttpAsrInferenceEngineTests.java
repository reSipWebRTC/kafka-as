package com.kafkaasr.asr.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kafkaasr.asr.events.AudioIngressRawEvent;
import com.kafkaasr.asr.events.AudioIngressRawPayload;
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

class HttpAsrInferenceEngineTests {

    @Test
    void inferMapsHttpResponseAndAuthorizationHeader() {
        AtomicReference<ClientRequest> capturedRequest = new AtomicReference<>();
        ExchangeFunction exchangeFunction = request -> {
            capturedRequest.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body("{\"text\":\"hello\",\"language\":\"en-US\",\"confidence\":0.87,\"stable\":true}")
                    .build());
        };

        AsrInferenceProperties properties = httpProperties("token-abc");
        HttpAsrInferenceEngine engine = new HttpAsrInferenceEngine(
                properties,
                WebClient.builder().exchangeFunction(exchangeFunction));

        AsrInferenceEngine.AsrInferenceResult result = engine.infer(sampleIngressEvent(false));

        assertEquals("hello", result.text());
        assertEquals("en-US", result.language());
        assertEquals(0.87d, result.confidence());
        assertTrue(result.stable());
        assertEquals(HttpMethod.POST, capturedRequest.get().method());
        assertEquals("/v1/asr/infer", capturedRequest.get().url().getPath());
        assertEquals("Bearer token-abc", capturedRequest.get().headers().getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void inferFallsBackToFinalDefaultsWhenResponseOmitsStableAndConfidence() {
        ExchangeFunction exchangeFunction = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body("{\"text\":\"done\",\"language\":\"zh-CN\"}")
                .build());

        AsrInferenceProperties properties = httpProperties("");
        HttpAsrInferenceEngine engine = new HttpAsrInferenceEngine(
                properties,
                WebClient.builder().exchangeFunction(exchangeFunction));

        AsrInferenceEngine.AsrInferenceResult result = engine.infer(sampleIngressEvent(true));

        assertEquals("done", result.text());
        assertEquals("zh-CN", result.language());
        assertEquals(0.95d, result.confidence());
        assertTrue(result.stable());
    }

    private static AsrInferenceProperties httpProperties(String authToken) {
        AsrInferenceProperties properties = new AsrInferenceProperties();
        properties.setMode("http");
        AsrInferenceProperties.Http http = properties.getHttp();
        http.setEndpoint("http://asr-engine.local");
        http.setPath("/v1/asr/infer");
        http.setTimeoutMs(2000L);
        http.setAuthToken(authToken);
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
