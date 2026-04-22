package com.kafkaasr.tts.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kafkaasr.tts.events.TranslationResultEvent;
import com.kafkaasr.tts.events.TranslationResultPayload;
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
                new ObjectMapper());

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
    void synthesizeFallsBackWhenHttpFails() {
        ExchangeFunction exchangeFunction = request -> Mono.error(new RuntimeException("upstream unavailable"));

        HttpTtsSynthesisEngine engine = new HttpTtsSynthesisEngine(
                httpProperties(""),
                WebClient.builder().exchangeFunction(exchangeFunction),
                new ObjectMapper());

        TtsSynthesisEngine.SynthesisPlan plan = engine.synthesize(
                sampleEvent(),
                new TtsSynthesisEngine.SynthesisInput("hello", "en-US", "en-US-neural-a", true));

        assertEquals("hello", plan.text());
        assertEquals("en-US", plan.language());
        assertEquals("en-US-neural-a", plan.voice());
        assertEquals(true, plan.stream());
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
                new ObjectMapper());

        engine.synthesize(
                sampleEvent(),
                new TtsSynthesisEngine.SynthesisInput("hello", "en-US", "en-US-neural-a", true));

        assertNull(capturedRequest.get().headers().getFirst(HttpHeaders.AUTHORIZATION));
    }

    private static TtsSynthesisProperties httpProperties(String authToken) {
        TtsSynthesisProperties properties = new TtsSynthesisProperties();
        properties.setMode("http");
        TtsSynthesisProperties.Http http = properties.getHttp();
        http.setEndpoint("http://tts-engine.local");
        http.setPath("/v1/tts/synthesize");
        http.setTimeoutMs(2000L);
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
