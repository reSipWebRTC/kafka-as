package com.kafkaasr.translation.pipeline;

import com.kafkaasr.translation.events.AsrFinalEvent;
import com.kafkaasr.translation.events.AsrFinalPayload;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Primary
@Component
@ConditionalOnProperty(prefix = "translation.engine", name = "mode", havingValue = "http")
public class HttpTranslationEngine implements TranslationEngine {

    private static final String FALLBACK_LANGUAGE = "und";

    private final TranslationEngineProperties properties;
    private final WebClient webClient;

    public HttpTranslationEngine(
            TranslationEngineProperties properties,
            WebClient.Builder webClientBuilder) {
        this.properties = properties;
        String endpoint = properties.getHttp().getEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalStateException(
                    "translation.engine.http.endpoint must be set when translation.engine.mode=http");
        }
        this.webClient = webClientBuilder.baseUrl(endpoint).build();
    }

    @Override
    public TranslationResult translate(AsrFinalEvent asrFinalEvent, String targetLang) {
        AsrFinalPayload payload = asrFinalEvent.payload();
        if (payload == null) {
            throw new IllegalArgumentException("Missing asr.final payload for session " + asrFinalEvent.sessionId());
        }

        String sourceText = payload.text() == null ? "" : payload.text();
        String sourceLang = normalizeLanguage(payload.language());
        String normalizedTargetLang = normalizeLanguage(targetLang);

        HttpTranslationResponse response = webClient
                .post()
                .uri(properties.getHttp().getPath())
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    String authToken = properties.getHttp().getAuthToken();
                    if (authToken != null && !authToken.isBlank()) {
                        headers.setBearerAuth(authToken);
                    }
                })
                .bodyValue(new HttpTranslationRequest(
                        asrFinalEvent.sessionId(),
                        asrFinalEvent.traceId(),
                        asrFinalEvent.tenantId(),
                        asrFinalEvent.seq(),
                        sourceText,
                        sourceLang,
                        normalizedTargetLang))
                .retrieve()
                .bodyToMono(HttpTranslationResponse.class)
                .timeout(Duration.ofMillis(properties.getHttp().getTimeoutMs()))
                .block();

        String translatedText = sourceText;
        String resultSourceLang = sourceLang;
        String resultTargetLang = normalizedTargetLang;
        String resultEngine = properties.getHttp().getEngineName();

        if (response != null) {
            if (response.translatedText() != null && !response.translatedText().isBlank()) {
                translatedText = response.translatedText();
            }
            if (response.sourceLang() != null && !response.sourceLang().isBlank()) {
                resultSourceLang = normalizeLanguage(response.sourceLang());
            }
            if (response.targetLang() != null && !response.targetLang().isBlank()) {
                resultTargetLang = normalizeLanguage(response.targetLang());
            }
            if (response.engine() != null && !response.engine().isBlank()) {
                resultEngine = response.engine();
            }
        }

        return new TranslationResult(translatedText, resultSourceLang, resultTargetLang, resultEngine);
    }

    private String normalizeLanguage(String language) {
        if (language == null || language.isBlank() || language.length() < 2) {
            return FALLBACK_LANGUAGE;
        }
        return language;
    }

    record HttpTranslationRequest(
            String sessionId,
            String traceId,
            String tenantId,
            long seq,
            String sourceText,
            String sourceLang,
            String targetLang) {
    }

    record HttpTranslationResponse(
            String translatedText,
            String sourceLang,
            String targetLang,
            String engine) {
    }
}
