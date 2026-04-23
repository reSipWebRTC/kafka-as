package com.kafkaasr.tts.pipeline;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "tts.synthesis")
public class TtsSynthesisProperties {

    @NotBlank
    private String mode = "placeholder";

    @Valid
    private Http http = new Http();

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

    public static class Http {

        private String endpoint = "";

        @NotBlank
        private String path = "/v1/tts/synthesize";

        @Min(100)
        private long timeoutMs = 2000L;

        @Min(1)
        private int maxConcurrentRequests = 16;

        @Valid
        private Health health = new Health();

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

        public String getAuthToken() {
            return authToken;
        }

        public void setAuthToken(String authToken) {
            this.authToken = authToken;
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
