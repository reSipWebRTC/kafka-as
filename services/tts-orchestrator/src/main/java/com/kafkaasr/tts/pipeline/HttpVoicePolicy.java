package com.kafkaasr.tts.pipeline;

import com.kafkaasr.tts.events.TranslationResultEvent;
import com.kafkaasr.tts.events.TranslationResultPayload;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Primary
@Component
@ConditionalOnProperty(prefix = "tts.voice-policy", name = "mode", havingValue = "http")
public class HttpVoicePolicy implements VoicePolicy {

    private final TtsVoicePolicyProperties properties;
    private final WebClient webClient;
    private final RuleBasedVoicePolicy fallbackPolicy;

    public HttpVoicePolicy(
            TtsVoicePolicyProperties properties,
            WebClient.Builder webClientBuilder,
            RuleBasedVoicePolicy fallbackPolicy) {
        this.properties = properties;
        this.fallbackPolicy = fallbackPolicy;
        String endpoint = properties.getHttp().getEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalStateException(
                    "tts.voice-policy.http.endpoint must be set when tts.voice-policy.mode=http");
        }
        this.webClient = webClientBuilder.baseUrl(endpoint).build();
    }

    @Override
    public String resolveVoice(TranslationResultEvent event, String language, String defaultVoice) {
        String fallback = fallbackPolicy.resolveVoice(event, language, defaultVoice);
        TranslationResultPayload payload = event == null ? null : event.payload();

        try {
            HttpVoiceResponse response = webClient
                    .post()
                    .uri(properties.getHttp().getPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        String authToken = properties.getHttp().getAuthToken();
                        if (authToken != null && !authToken.isBlank()) {
                            headers.setBearerAuth(authToken);
                        }
                    })
                    .bodyValue(new HttpVoiceRequest(
                            event == null ? "" : event.sessionId(),
                            event == null ? "" : event.traceId(),
                            event == null ? "" : event.tenantId(),
                            event == null ? "" : event.roomId(),
                            language,
                            defaultVoice,
                            payload == null ? "" : payload.sourceLang(),
                            payload == null ? "" : payload.targetLang(),
                            payload == null ? "" : payload.translatedText(),
                            payload == null ? "" : payload.sourceText()))
                    .retrieve()
                    .bodyToMono(HttpVoiceResponse.class)
                    .timeout(Duration.ofMillis(properties.getHttp().getTimeoutMs()))
                    .block();

            if (response == null || response.voice() == null || response.voice().isBlank()) {
                return fallback;
            }
            return response.voice();
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    record HttpVoiceRequest(
            String sessionId,
            String traceId,
            String tenantId,
            String roomId,
            String language,
            String defaultVoice,
            String sourceLang,
            String targetLang,
            String translatedText,
            String sourceText) {
    }

    record HttpVoiceResponse(String voice) {
    }
}
