package com.kafkaasr.control.auth;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "control.auth")
public class ControlPlaneAuthProperties {

    private boolean enabled = false;
    private List<String> tokens = new ArrayList<>();

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

    public Set<String> tokenSet() {
        return tokens.stream()
                .map(String::trim)
                .filter(token -> !token.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }
}
