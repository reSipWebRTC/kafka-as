package com.kafkaasr.control.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.PatternMatchUtils;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "control.auth")
public class ControlPlaneAuthProperties {

    private boolean enabled = false;
    private List<String> tokens = new ArrayList<>();
    private List<Credential> credentials = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getTokens() {
        return tokens;
    }

    public void setTokens(List<String> tokens) {
        this.tokens = tokens == null ? new ArrayList<>() : new ArrayList<>(tokens);
    }

    public List<Credential> getCredentials() {
        return credentials;
    }

    public void setCredentials(List<Credential> credentials) {
        this.credentials = credentials == null ? new ArrayList<>() : new ArrayList<>(credentials);
    }

    public Set<String> tokenSet() {
        return tokens.stream()
                .map(String::trim)
                .filter(token -> !token.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    public Optional<Credential> findCredential(String token) {
        if (!StringUtils.hasText(token)) {
            return Optional.empty();
        }
        String normalized = token.trim();
        return credentials.stream()
                .filter(credential -> credential.matchesToken(normalized))
                .findFirst();
    }

    public static class Credential {

        private String token = "";
        private boolean read = true;
        private boolean write = true;
        private List<String> tenantPatterns = new ArrayList<>();

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token == null ? "" : token;
        }

        public boolean isRead() {
            return read;
        }

        public void setRead(boolean read) {
            this.read = read;
        }

        public boolean isWrite() {
            return write;
        }

        public void setWrite(boolean write) {
            this.write = write;
        }

        public List<String> getTenantPatterns() {
            return tenantPatterns;
        }

        public void setTenantPatterns(List<String> tenantPatterns) {
            this.tenantPatterns = tenantPatterns == null ? new ArrayList<>() : new ArrayList<>(tenantPatterns);
        }

        public boolean matchesToken(String candidateToken) {
            return StringUtils.hasText(token) && token.trim().equals(candidateToken);
        }

        public boolean allowsTenant(String tenantId) {
            if (!StringUtils.hasText(tenantId)) {
                return false;
            }
            List<String> patterns = tenantPatterns.stream()
                    .map(String::trim)
                    .filter(pattern -> !pattern.isBlank())
                    .toList();
            if (patterns.isEmpty()) {
                return true;
            }
            return patterns.stream()
                    .anyMatch(pattern -> PatternMatchUtils.simpleMatch(pattern, tenantId));
        }
    }
}
