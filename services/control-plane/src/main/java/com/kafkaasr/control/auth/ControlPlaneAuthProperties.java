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

    public enum Mode {
        STATIC,
        EXTERNAL_IAM,
        HYBRID
    }

    private boolean enabled = false;
    private Mode mode = Mode.STATIC;
    private List<String> tokens = new ArrayList<>();
    private List<Credential> credentials = new ArrayList<>();
    private External external = new External();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.STATIC : mode;
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

    public External getExternal() {
        return external;
    }

    public void setExternal(External external) {
        this.external = external == null ? new External() : external;
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

    public static class External {

        private static final int DEFAULT_CONNECT_TIMEOUT_MS = 1000;
        private static final int DEFAULT_READ_TIMEOUT_MS = 1000;

        private String issuer = "";
        private String audience = "";
        private String jwksUri = "";
        private String permissionClaim = "scp";
        private String tenantClaim = "tenant_ids";
        private String readPermission = "control.policy.read";
        private String writePermission = "control.policy.write";
        private int connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
        private int readTimeoutMs = DEFAULT_READ_TIMEOUT_MS;

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer == null ? "" : issuer;
        }

        public String getAudience() {
            return audience;
        }

        public void setAudience(String audience) {
            this.audience = audience == null ? "" : audience;
        }

        public String getJwksUri() {
            return jwksUri;
        }

        public void setJwksUri(String jwksUri) {
            this.jwksUri = jwksUri == null ? "" : jwksUri;
        }

        public String getPermissionClaim() {
            return permissionClaim;
        }

        public void setPermissionClaim(String permissionClaim) {
            this.permissionClaim = permissionClaim == null ? "scp" : permissionClaim;
        }

        public String getTenantClaim() {
            return tenantClaim;
        }

        public void setTenantClaim(String tenantClaim) {
            this.tenantClaim = tenantClaim == null ? "tenant_ids" : tenantClaim;
        }

        public String getReadPermission() {
            return readPermission;
        }

        public void setReadPermission(String readPermission) {
            this.readPermission = readPermission == null ? "control.policy.read" : readPermission;
        }

        public String getWritePermission() {
            return writePermission;
        }

        public void setWritePermission(String writePermission) {
            this.writePermission = writePermission == null ? "control.policy.write" : writePermission;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            if (connectTimeoutMs <= 0) {
                this.connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
                return;
            }
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(int readTimeoutMs) {
            if (readTimeoutMs <= 0) {
                this.readTimeoutMs = DEFAULT_READ_TIMEOUT_MS;
                return;
            }
            this.readTimeoutMs = readTimeoutMs;
        }
    }
}
