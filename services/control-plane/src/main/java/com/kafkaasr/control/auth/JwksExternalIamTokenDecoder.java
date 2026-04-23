package com.kafkaasr.control.auth;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.util.StringUtils;

class JwksExternalIamTokenDecoder implements ExternalIamTokenDecoder {

    private final ControlPlaneAuthProperties.External externalProperties;
    private final AtomicReference<JwtDecoder> decoderRef = new AtomicReference<>();

    JwksExternalIamTokenDecoder(ControlPlaneAuthProperties.External externalProperties) {
        this.externalProperties = externalProperties;
    }

    @Override
    public Map<String, Object> decode(String token) {
        JwtDecoder decoder = decoderRef.updateAndGet(existing -> existing != null ? existing : buildDecoder());
        try {
            Jwt jwt = decoder.decode(token);
            return jwt.getClaims();
        } catch (JwtException exception) {
            if (looksUnavailable(exception)) {
                throw new ExternalIamTokenDecoderException(
                        ExternalIamTokenDecoderException.Category.UNAVAILABLE,
                        "External IAM unavailable",
                        exception);
            }
            throw new ExternalIamTokenDecoderException(
                    ExternalIamTokenDecoderException.Category.INVALID_TOKEN,
                    "External IAM token invalid",
                    exception);
        }
    }

    private JwtDecoder buildDecoder() {
        if (!StringUtils.hasText(externalProperties.getJwksUri())) {
            throw new ExternalIamTokenDecoderException(
                    ExternalIamTokenDecoderException.Category.UNAVAILABLE,
                    "JWKS URI is not configured",
                    null);
        }

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(externalProperties.getJwksUri().trim()).build();
        decoder.setJwtValidator(buildValidator());
        return decoder;
    }

    private OAuth2TokenValidator<Jwt> buildValidator() {
        OAuth2TokenValidator<Jwt> validator = StringUtils.hasText(externalProperties.getIssuer())
                ? JwtValidators.createDefaultWithIssuer(externalProperties.getIssuer().trim())
                : JwtValidators.createDefault();

        if (!StringUtils.hasText(externalProperties.getAudience())) {
            return validator;
        }

        String audience = externalProperties.getAudience().trim();
        OAuth2TokenValidator<Jwt> audienceValidator = jwt -> jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error(
                        "invalid_token",
                        "Audience claim does not contain required value",
                        null));

        return new DelegatingOAuth2TokenValidator<>(validator, audienceValidator);
    }

    private boolean looksUnavailable(JwtException exception) {
        String message = exception.getMessage();
        if (message == null) {
            return false;
        }
        return message.contains("retrieve remote JWK set")
                || message.contains("Connection refused")
                || message.contains("Read timed out")
                || message.contains("I/O error");
    }
}
