package com.kafkaasr.translation.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.kafkaasr.translation.events.TranslationRequestEvent;
import com.kafkaasr.translation.events.TranslationRequestPayload;
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

class HttpTranslationEngineTests {

    @Test
    void translateMapsHttpResponseAndAuthorizationHeader() {
        AtomicReference<ClientRequest> capturedRequest = new AtomicReference<>();
        ExchangeFunction exchangeFunction = request -> {
            capturedRequest.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body("{\"translatedText\":\"hello\",\"sourceLang\":\"zh-CN\",\"targetLang\":\"en-US\",\"engine\":\"mt-v2\"}")
                    .build());
        };

        TranslationEngineProperties properties = httpProperties("token-xyz");
        HttpTranslationEngine engine = new HttpTranslationEngine(
                properties,
                WebClient.builder().exchangeFunction(exchangeFunction));

        TranslationEngine.TranslationResult result = engine.translate(sampleTranslationRequestEvent());

        assertEquals("hello", result.translatedText());
        assertEquals("zh-CN", result.sourceLang());
        assertEquals("en-US", result.targetLang());
        assertEquals("mt-v2", result.engine());
        assertEquals(HttpMethod.POST, capturedRequest.get().method());
        assertEquals("/v1/translate", capturedRequest.get().url().getPath());
        assertEquals("Bearer token-xyz", capturedRequest.get().headers().getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void translateFallsBackWhenResponseOmitsFields() {
        ExchangeFunction exchangeFunction = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .body("{}")
                .build());

        TranslationEngineProperties properties = httpProperties("");
        HttpTranslationEngine engine = new HttpTranslationEngine(
                properties,
                WebClient.builder().exchangeFunction(exchangeFunction));

        TranslationEngine.TranslationResult result = engine.translate(sampleTranslationRequestEvent());

        assertEquals("你好", result.translatedText());
        assertEquals("zh-CN", result.sourceLang());
        assertEquals("en-US", result.targetLang());
        assertEquals("http-translation", result.engine());
    }

    private static TranslationEngineProperties httpProperties(String authToken) {
        TranslationEngineProperties properties = new TranslationEngineProperties();
        properties.setMode("http");
        TranslationEngineProperties.Http http = properties.getHttp();
        http.setEndpoint("http://translation-engine.local");
        http.setPath("/v1/translate");
        http.setTimeoutMs(2000L);
        http.setAuthToken(authToken);
        http.setEngineName("http-translation");
        return properties;
    }

    private static TranslationRequestEvent sampleTranslationRequestEvent() {
        return new TranslationRequestEvent(
                "evt-req-1",
                "translation.request",
                "v1",
                "trc-1",
                "sess-1",
                "tenant-a",
                "room-1",
                "translation-worker",
                7L,
                1713744000000L,
                "sess-1:translation.request:7",
                new TranslationRequestPayload("你好", "zh-CN", "en-US"));
    }
}
