package com.kafkaasr.asr.pipeline;

import com.kafkaasr.asr.events.AudioIngressRawEvent;
import com.kafkaasr.asr.events.AudioIngressRawPayload;
import java.time.Duration;
import java.util.Base64;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Primary
@Component
@ConditionalOnProperty(prefix = "asr.inference", name = "mode", havingValue = "http")
public class HttpAsrInferenceEngine implements AsrInferenceEngine {

    private static final double DEFAULT_CONFIDENCE_NON_FINAL = 0.6d;
    private static final double DEFAULT_CONFIDENCE_FINAL = 0.95d;

    private final AsrInferenceProperties properties;
    private final WebClient webClient;

    public HttpAsrInferenceEngine(
            AsrInferenceProperties properties,
            WebClient.Builder webClientBuilder) {
        this.properties = properties;
        String endpoint = properties.getHttp().getEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalStateException("asr.inference.http.endpoint must be set when asr.inference.mode=http");
        }
        this.webClient = webClientBuilder.baseUrl(endpoint).build();
    }

    @Override
    public AsrInferenceResult infer(AudioIngressRawEvent ingressEvent) {
        AudioIngressRawPayload payload = ingressEvent.payload();
        if (payload == null) {
            throw new IllegalArgumentException("Missing audio payload for session " + ingressEvent.sessionId());
        }
        validateBase64(payload.audioBase64(), ingressEvent.sessionId());

        HttpAsrResponse response = webClient
                .post()
                .uri(properties.getHttp().getPath())
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> {
                    String authToken = properties.getHttp().getAuthToken();
                    if (authToken != null && !authToken.isBlank()) {
                        headers.setBearerAuth(authToken);
                    }
                })
                .bodyValue(new HttpAsrRequest(
                        ingressEvent.sessionId(),
                        ingressEvent.traceId(),
                        ingressEvent.seq(),
                        payload.audioCodec(),
                        payload.sampleRate(),
                        payload.channels(),
                        payload.audioBase64(),
                        payload.endOfStream()))
                .retrieve()
                .bodyToMono(HttpAsrResponse.class)
                .timeout(Duration.ofMillis(properties.getHttp().getTimeoutMs()))
                .block();

        if (response == null || response.text() == null || response.text().isBlank()) {
            throw new IllegalStateException("Empty HTTP ASR response for session " + ingressEvent.sessionId());
        }

        double confidence = response.confidence() == null
                ? (payload.endOfStream() ? DEFAULT_CONFIDENCE_FINAL : DEFAULT_CONFIDENCE_NON_FINAL)
                : response.confidence();
        boolean stable = response.stable() == null ? payload.endOfStream() : response.stable();

        return new AsrInferenceResult(response.text(), response.language(), confidence, stable);
    }

    private void validateBase64(String audioBase64, String sessionId) {
        if (audioBase64 == null || audioBase64.isBlank()) {
            throw new IllegalArgumentException("Missing audioBase64 payload for session " + sessionId);
        }
        try {
            Base64.getDecoder().decode(audioBase64);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid audioBase64 payload for session " + sessionId, exception);
        }
    }

    record HttpAsrRequest(
            String sessionId,
            String traceId,
            long seq,
            String audioCodec,
            int sampleRateHz,
            int channels,
            String audioBase64,
            boolean endOfStream) {
    }

    record HttpAsrResponse(
            String text,
            String language,
            Double confidence,
            Boolean stable) {
    }
}
