package com.kafkaasr.asr.pipeline;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "asr.inference")
public class AsrInferenceProperties {

    @NotBlank
    private String mode = "placeholder";

    @Valid
    private Http http = new Http();

    @Valid
    private Funasr funasr = new Funasr();

    @Valid
    private Vad vad = new Vad();

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public Http getHttp() {
        return http;
    }

    public void setHttp(Http http) {
        this.http = http;
    }

    public Funasr getFunasr() {
        return funasr;
    }

    public void setFunasr(Funasr funasr) {
        this.funasr = funasr;
    }

    public Vad getVad() {
        return vad;
    }

    public void setVad(Vad vad) {
        this.vad = vad;
    }

    public static class Http {

        private String endpoint = "";

        @NotBlank
        private String path = "/v1/asr/infer";

        @Min(100)
        private long timeoutMs = 1500L;

        private String authToken = "";

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public String getAuthToken() {
            return authToken;
        }

        public void setAuthToken(String authToken) {
            this.authToken = authToken;
        }
    }

    public static class Funasr {

        private String endpoint = "";

        @NotBlank
        private String path = "/v1/asr";

        @Min(100)
        private long timeoutMs = 3000L;

        private String authToken = "";

        @NotBlank
        private String model = "paraformer-zh";

        @NotBlank
        private String language = "auto";

        private String hotwords = "";

        @NotBlank
        private String audioFormat = "pcm";

        @Min(8000)
        private int defaultSampleRate = 16000;

        private boolean enableInverseTextNormalization = true;

        @Min(1)
        private int maxConcurrentRequests = 16;

        @Valid
        private Health health = new Health();

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public String getAuthToken() {
            return authToken;
        }

        public void setAuthToken(String authToken) {
            this.authToken = authToken;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getLanguage() {
            return language;
        }

        public void setLanguage(String language) {
            this.language = language;
        }

        public String getHotwords() {
            return hotwords;
        }

        public void setHotwords(String hotwords) {
            this.hotwords = hotwords;
        }

        public String getAudioFormat() {
            return audioFormat;
        }

        public void setAudioFormat(String audioFormat) {
            this.audioFormat = audioFormat;
        }

        public int getDefaultSampleRate() {
            return defaultSampleRate;
        }

        public void setDefaultSampleRate(int defaultSampleRate) {
            this.defaultSampleRate = defaultSampleRate;
        }

        public boolean isEnableInverseTextNormalization() {
            return enableInverseTextNormalization;
        }

        public void setEnableInverseTextNormalization(boolean enableInverseTextNormalization) {
            this.enableInverseTextNormalization = enableInverseTextNormalization;
        }

        public int getMaxConcurrentRequests() {
            return maxConcurrentRequests;
        }

        public void setMaxConcurrentRequests(int maxConcurrentRequests) {
            this.maxConcurrentRequests = maxConcurrentRequests;
        }

        public Health getHealth() {
            return health;
        }

        public void setHealth(Health health) {
            this.health = health;
        }
    }

    public static class Health {

        private boolean enabled = false;

        @NotBlank
        private String path = "/health";

        @Min(100)
        private long timeoutMs = 800L;

        @Min(100)
        private long cacheTtlMs = 3000L;

        private boolean failOpenOnError = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public long getCacheTtlMs() {
            return cacheTtlMs;
        }

        public void setCacheTtlMs(long cacheTtlMs) {
            this.cacheTtlMs = cacheTtlMs;
        }

        public boolean isFailOpenOnError() {
            return failOpenOnError;
        }

        public void setFailOpenOnError(boolean failOpenOnError) {
            this.failOpenOnError = failOpenOnError;
        }
    }

    public static class Vad {

        private boolean enabled = true;

        @Min(1)
        private int silenceFramesToFinalize = 8;

        @Min(1)
        private int minActiveFramesPerSegment = 2;

        @Min(0)
        private int silenceAmplitudeThreshold = 512;

        @NotBlank
        private String audioCodec = "pcm16le";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getSilenceFramesToFinalize() {
            return silenceFramesToFinalize;
        }

        public void setSilenceFramesToFinalize(int silenceFramesToFinalize) {
            this.silenceFramesToFinalize = silenceFramesToFinalize;
        }

        public int getMinActiveFramesPerSegment() {
            return minActiveFramesPerSegment;
        }

        public void setMinActiveFramesPerSegment(int minActiveFramesPerSegment) {
            this.minActiveFramesPerSegment = minActiveFramesPerSegment;
        }

        public int getSilenceAmplitudeThreshold() {
            return silenceAmplitudeThreshold;
        }

        public void setSilenceAmplitudeThreshold(int silenceAmplitudeThreshold) {
            this.silenceAmplitudeThreshold = silenceAmplitudeThreshold;
        }

        public String getAudioCodec() {
            return audioCodec;
        }

        public void setAudioCodec(String audioCodec) {
            this.audioCodec = audioCodec;
        }
    }
}
