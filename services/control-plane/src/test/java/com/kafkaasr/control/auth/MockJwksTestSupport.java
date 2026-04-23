package com.kafkaasr.control.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.util.StringUtils;

final class MockJwksTestSupport {

    private MockJwksTestSupport() {
    }

    static RSAKey generateRsaKey(String keyId) {
        try {
            RSAKey key = new RSAKeyGenerator(2048).keyID(StringUtils.hasText(keyId) ? keyId : "test-kid").generate();
            return key.toRSAKey();
        } catch (JOSEException exception) {
            throw new IllegalStateException("Failed to generate RSA key", exception);
        }
    }

    static String issueToken(
            RSAKey privateKey,
            String issuer,
            String audience,
            String permissionClaim,
            Object permissions,
            String tenantClaim,
            Object tenantScopes,
            Duration ttl) {
        try {
            Instant now = Instant.now();
            Duration effectiveTtl = (ttl == null || ttl.isNegative() || ttl.isZero()) ? Duration.ofMinutes(5) : ttl;

            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .audience(audience)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plus(effectiveTtl)))
                    .jwtID("jwt-" + now.toEpochMilli());

            if (permissions != null && StringUtils.hasText(permissionClaim)) {
                claims.claim(permissionClaim, permissions);
            }
            if (tenantScopes != null && StringUtils.hasText(tenantClaim)) {
                claims.claim(tenantClaim, tenantScopes);
            }

            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .type(JOSEObjectType.JWT)
                    .keyID(privateKey.getKeyID())
                    .build();
            SignedJWT jwt = new SignedJWT(header, claims.build());
            jwt.sign(new RSASSASigner(privateKey));
            return jwt.serialize();
        } catch (JOSEException exception) {
            throw new IllegalStateException("Failed to issue JWT", exception);
        }
    }

    static MockJwksServer startJwksServer(RSAKey key, Duration responseDelay) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            byte[] jwksPayload = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            long delayMs = responseDelay == null ? 0 : Math.max(0, responseDelay.toMillis());

            server.createContext("/jwks", exchange -> respondWithJwks(exchange, jwksPayload, delayMs));

            AtomicInteger threadCounter = new AtomicInteger();
            ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable);
                    thread.setName("mock-jwks-server-" + threadCounter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }
            });
            server.setExecutor(executor);
            server.start();
            return new MockJwksServer(server, executor);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to start mock JWKS server", exception);
        }
    }

    static List<String> tenants(String... values) {
        return List.of(values);
    }

    private static void respondWithJwks(HttpExchange exchange, byte[] payload, long delayMs) throws IOException {
        try {
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(payload);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            exchange.sendResponseHeaders(500, -1);
        } finally {
            exchange.close();
        }
    }

    static final class MockJwksServer implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor;

        private MockJwksServer(HttpServer server, ExecutorService executor) {
            this.server = server;
            this.executor = executor;
        }

        String jwksUri() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
