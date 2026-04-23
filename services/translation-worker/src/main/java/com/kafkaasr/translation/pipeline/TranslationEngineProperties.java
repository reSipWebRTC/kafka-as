package com.kafkaasr.translation.pipeline;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "translation.engine")
public class TranslationEngineProperties {

    @NotBlank
    private String mode = "placeholder";

    @Valid
    private Http http = new Http();

    @Valid
    private Openai openai = new Openai();

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

    public Openai getOpenai() {
        return openai;
    }

    public void setOpenai(Openai openai) {
        this.openai = openai;
    }

    public static class Http {

        private String endpoint = "";

        @NotBlank
        private String path = "/v1/translate";

        @Min(100)
        private long timeoutMs = 1500L;

        private String authToken = "";

        @NotBlank
        private String engineName = "http-translation";

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

        public String getEngineName() {
            return engineName;
        }

        public void setEngineName(String engineName) {
            this.engineName = engineName;
        }
    }

    public static class Openai {

        private String endpoint = "";

        @NotBlank
        private String path = "/v1/chat/completions";

        @Min(100)
        private long timeoutMs = 3000L;

        private String apiKey = "";

        @NotBlank
        private String model = "gpt-4o-mini";

        @DecimalMin("0.0")
        @DecimalMax("2.0")
        private double temperature = 0.0d;

        @Min(1)
        private int maxTokens = 512;

        @Min(1)
        private int maxConcurrentRequests = 16;

        @Valid
        private Health health = new Health();

        @NotBlank
        private String engineName = "openai-translation";

        @NotBlank
        private String systemPrompt = "You are a translation engine. Return only translated text without explanation.";

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

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
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

        public String getEngineName() {
            return engineName;
        }

        public void setEngineName(String engineName) {
            this.engineName = engineName;
        }

        public String getSystemPrompt() {
            return systemPrompt;
        }

        public void setSystemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
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
}
