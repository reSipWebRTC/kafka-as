package com.kafkaasr.control.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

class JwksExternalIamTokenDecoderTests {

    @Test
    void reportsUnavailableWhenJwksUriMissing() {
        ControlPlaneAuthProperties.External external = new ControlPlaneAuthProperties.External();
        external.setJwksUri("");

        JwksExternalIamTokenDecoder decoder = new JwksExternalIamTokenDecoder(external);

        ExternalIamTokenDecoderException exception = assertThrows(
                ExternalIamTokenDecoderException.class,
                () -> decoder.decode("token"));
        assertEquals(ExternalIamTokenDecoderException.Category.UNAVAILABLE, exception.category());
    }

    @Test
    void mapsRetrieveRemoteJwkSetFailureAsUnavailable() {
        JwksExternalIamTokenDecoder decoder = decoderThrowing("Failed to retrieve remote JWK set");

        ExternalIamTokenDecoderException exception = assertThrows(
                ExternalIamTokenDecoderException.class,
                () -> decoder.decode("token"));
        assertEquals(ExternalIamTokenDecoderException.Category.UNAVAILABLE, exception.category());
    }

    @Test
    void mapsConnectTimeoutAsUnavailable() {
        JwksExternalIamTokenDecoder decoder = decoderThrowing("Connect timed out");

        ExternalIamTokenDecoderException exception = assertThrows(
                ExternalIamTokenDecoderException.class,
                () -> decoder.decode("token"));
        assertEquals(ExternalIamTokenDecoderException.Category.UNAVAILABLE, exception.category());
    }

    @Test
    void mapsReadTimeoutAsUnavailable() {
        JwksExternalIamTokenDecoder decoder = decoderThrowing("Read timed out");

        ExternalIamTokenDecoderException exception = assertThrows(
                ExternalIamTokenDecoderException.class,
                () -> decoder.decode("token"));
        assertEquals(ExternalIamTokenDecoderException.Category.UNAVAILABLE, exception.category());
    }

    @Test
    void mapsJwtValidationFailureAsInvalidToken() {
        JwksExternalIamTokenDecoder decoder = decoderThrowing("Signed JWT rejected: Invalid signature");

        ExternalIamTokenDecoderException exception = assertThrows(
                ExternalIamTokenDecoderException.class,
                () -> decoder.decode("token"));
        assertEquals(ExternalIamTokenDecoderException.Category.INVALID_TOKEN, exception.category());
    }

    private static JwksExternalIamTokenDecoder decoderThrowing(String message) {
        ControlPlaneAuthProperties.External external = new ControlPlaneAuthProperties.External();
        external.setJwksUri("https://iam-preprod.example.com/.well-known/jwks.json");

        JwksExternalIamTokenDecoder decoder = new JwksExternalIamTokenDecoder(external);
        injectDecoder(decoder, token -> {
            throw new JwtException(message);
        });
        return decoder;
    }

    private static void injectDecoder(JwksExternalIamTokenDecoder decoder, JwtDecoder jwtDecoder) {
        try {
            Field field = JwksExternalIamTokenDecoder.class.getDeclaredField("decoderRef");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            AtomicReference<JwtDecoder> decoderRef = (AtomicReference<JwtDecoder>) field.get(decoder);
            decoderRef.set(jwtDecoder);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to inject decoder for test", exception);
        }
    }
}
