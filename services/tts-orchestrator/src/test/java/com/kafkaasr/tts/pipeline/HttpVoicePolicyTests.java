package com.kafkaasr.tts.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

class HttpVoicePolicyTests {

    @Test
    void resolveVoiceUsesHttpResultWhenAvailable() {
        AtomicReference<ClientRequest> capturedRequest = new AtomicReference<>();
        ExchangeFunction exchangeFunction = request -> {
            capturedRequest.set(request);
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body("{\"voice\":\"en-US-neural-b\"}")
                    .build());
        };

        HttpVoicePolicy policy = new HttpVoicePolicy(
                httpProperties("token-voice"),
                WebClient.builder().exchangeFunction(exchangeFunction),
                new RuleBasedVoicePolicy());

        String voice = policy.resolveVoice(sampleEvent(), "en-US", "en-US-neural-a");

        assertEquals("en-US-neural-b", voice);
        assertEquals(HttpMethod.POST, capturedRequest.get().method());
        assertEquals("/v1/voice/select", capturedRequest.get().url().getPath());
        assertEquals("Bearer token-voice", capturedRequest.get().headers().getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void resolveVoiceFallsBackToRuleBasedWhenHttpFails() {
        ExchangeFunction exchangeFunction = request -> Mono.error(new RuntimeException("upstream unavailable"));

        HttpVoicePolicy policy = new HttpVoicePolicy(
                httpProperties(""),
                WebClient.builder().exchangeFunction(exchangeFunction),
                new RuleBasedVoicePolicy());

        String voice = policy.resolveVoice(sampleEvent(), "zh-CN", "");

        assertEquals("zh-CN-standard-A", voice);
    }

    private static TtsVoicePolicyProperties httpProperties(String authToken) {
        TtsVoicePolicyProperties properties = new TtsVoicePolicyProperties();
        properties.setMode("http");
        TtsVoicePolicyProperties.Http http = properties.getHttp();
        http.setEndpoint("http://voice-policy.local");
        http.setPath("/v1/voice/select");
        http.setTimeoutMs(1200L);
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
