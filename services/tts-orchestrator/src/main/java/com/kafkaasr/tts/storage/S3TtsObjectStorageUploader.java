package com.kafkaasr.tts.storage;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Component
@ConditionalOnProperty(name = "tts.storage.enabled", havingValue = "true")
public class S3TtsObjectStorageUploader implements TtsObjectStorageUploader {

    private final TtsStorageProperties storageProperties;
    private final S3Client s3Client;
    private final Clock clock;
    private final Map<String, String> tenantRegionMap;
    private final Map<String, String> regionBaseUrlMap;

    public S3TtsObjectStorageUploader(TtsStorageProperties storageProperties) {
        this(storageProperties, createClient(storageProperties), Clock.systemUTC());
    }

    S3TtsObjectStorageUploader(TtsStorageProperties storageProperties, S3Client s3Client) {
        this(storageProperties, s3Client, Clock.systemUTC());
    }

    S3TtsObjectStorageUploader(TtsStorageProperties storageProperties, S3Client s3Client, Clock clock) {
        this.storageProperties = storageProperties;
        this.s3Client = s3Client;
        this.clock = clock;
        validateProperties(storageProperties);
        this.tenantRegionMap = parseDelimitedMap(storageProperties.getCdnRegionTenantMap(), true, true);
        this.regionBaseUrlMap = parseDelimitedMap(storageProperties.getCdnRegionBaseUrls(), true, false);
    }

    @Override
    public UploadResult upload(UploadRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("upload request is required");
        }
        if (request.audioBytes() == null || request.audioBytes().length == 0) {
            throw new IllegalArgumentException("audio bytes are required for storage upload");
        }

        String objectKey = buildObjectKey(request);
        PutObjectRequest.Builder putObjectBuilder = PutObjectRequest.builder()
                .bucket(storageProperties.getBucket())
                .key(objectKey)
                .contentType(normalizeContentType(request.codec()));
        if (storageProperties.getCacheControl() != null && !storageProperties.getCacheControl().isBlank()) {
            putObjectBuilder.cacheControl(storageProperties.getCacheControl().trim());
        }
        PutObjectRequest putObjectRequest = putObjectBuilder.build();

        s3Client.putObject(putObjectRequest, RequestBody.fromBytes(request.audioBytes()));
        return new UploadResult(objectKey, buildPlaybackUrl(request, objectKey));
    }

    private void validateProperties(TtsStorageProperties properties) {
        if (!"s3".equalsIgnoreCase(properties.getProvider())) {
            throw new IllegalStateException("Unsupported tts.storage.provider: " + properties.getProvider());
        }
        if (properties.getBucket() == null || properties.getBucket().isBlank()) {
            throw new IllegalStateException("tts.storage.bucket is required when storage is enabled");
        }
        String cacheScope = normalizeCacheScope(properties.getCacheScope());
        if (!"tenant".equals(cacheScope) && !"global".equals(cacheScope)) {
            throw new IllegalStateException("Unsupported tts.storage.cache-scope: " + properties.getCacheScope());
        }
    }

    private String buildObjectKey(UploadRequest request) {
        String prefix = trimSlashes(storageProperties.getKeyPrefix());
        String tenant = sanitizeSegment(request.tenantId());
        String rawCacheKey = request.cacheKey() == null || request.cacheKey().isBlank()
                ? "unknown"
                : request.cacheKey().trim();
        String cacheKey = sanitizeSegment(rawCacheKey);
        String suffix = sanitizeSegment(storageProperties.getObjectSuffix().toLowerCase(Locale.ROOT));
        String fileName = cacheKey + "." + suffix;
        String shardPrefix = resolveShardPrefix(rawCacheKey);

        StringBuilder objectKeyBuilder = new StringBuilder();
        if (!prefix.isBlank()) {
            objectKeyBuilder.append(prefix).append("/");
        }
        if (isTenantScopedCache()) {
            objectKeyBuilder.append(tenant).append("/");
        }
        if (!shardPrefix.isBlank()) {
            objectKeyBuilder.append(shardPrefix).append("/");
        }
        objectKeyBuilder.append(fileName);
        return objectKeyBuilder.toString();
    }

    private String buildPlaybackUrl(UploadRequest request, String objectKey) {
        String url = null;
        if (storageProperties.isCdnRegionRoutingEnabled()) {
            url = buildRegionalPlaybackUrl(request, objectKey);
            if (url == null && storageProperties.isCdnOriginFallbackEnabled()) {
                url = buildOriginPlaybackUrl(objectKey);
            }
        }
        if (url == null) {
            if (storageProperties.getPublicBaseUrl() != null && !storageProperties.getPublicBaseUrl().isBlank()) {
                url = joinUrl(storageProperties.getPublicBaseUrl(), objectKey);
            } else {
                url = buildOriginPlaybackUrl(objectKey);
            }
        }
        return maybeSignUrl(url);
    }

    private String buildRegionalPlaybackUrl(UploadRequest request, String objectKey) {
        String tenantRegion = resolveTenantRegion(request.tenantId());
        if (tenantRegion.isBlank()) {
            return null;
        }
        String regionalBaseUrl = regionBaseUrlMap.get(tenantRegion);
        if (regionalBaseUrl == null || regionalBaseUrl.isBlank()) {
            return null;
        }
        return joinUrl(regionalBaseUrl, objectKey);
    }

    private String resolveTenantRegion(String tenantId) {
        String normalizedTenant = normalizeRoutingToken(tenantId);
        if (!normalizedTenant.isBlank()) {
            String mappedRegion = tenantRegionMap.get(normalizedTenant);
            if (mappedRegion != null && !mappedRegion.isBlank()) {
                return mappedRegion;
            }
        }
        return normalizeRoutingToken(storageProperties.getCdnRegionDefault());
    }

    private String buildOriginPlaybackUrl(String objectKey) {
        if (storageProperties.getEndpoint() != null && !storageProperties.getEndpoint().isBlank()) {
            return joinUrl(storageProperties.getEndpoint(), storageProperties.getBucket() + "/" + objectKey);
        }
        String region = storageProperties.getRegion();
        return "https://" + storageProperties.getBucket() + ".s3." + region + ".amazonaws.com/" + objectKey;
    }

    private static S3Client createClient(TtsStorageProperties properties) {
        var builder = S3Client.builder()
                .region(Region.of(properties.getRegion()))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.isPathStyleAccess())
                        .build());

        if (properties.getAccessKey() != null
                && !properties.getAccessKey().isBlank()
                && properties.getSecretKey() != null
                && !properties.getSecretKey().isBlank()) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(properties.getAccessKey(), properties.getSecretKey())));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }

        if (properties.getEndpoint() != null && !properties.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(properties.getEndpoint()));
        }
        return builder.build();
    }

    private String normalizeContentType(String codec) {
        if (codec == null || codec.isBlank()) {
            return "application/octet-stream";
        }
        return codec;
    }

    private String sanitizeSegment(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private boolean isTenantScopedCache() {
        return "tenant".equals(normalizeCacheScope(storageProperties.getCacheScope()));
    }

    private String normalizeCacheScope(String cacheScope) {
        if (cacheScope == null || cacheScope.isBlank()) {
            return "tenant";
        }
        return cacheScope.trim().toLowerCase(Locale.ROOT);
    }

    private String resolveShardPrefix(String rawCacheKey) {
        if (!storageProperties.isCacheShardEnabled()) {
            return "";
        }
        String cacheKeyForHash = rawCacheKey == null || rawCacheKey.isBlank() ? "unknown" : rawCacheKey;
        String hash = sha256Hex(cacheKeyForHash);
        int prefixLength = Math.min(storageProperties.getCacheShardPrefixLength(), hash.length());
        return hash.substring(0, prefixLength);
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable for cache sharding", exception);
        }
    }

    private String trimSlashes(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String joinUrl(String base, String suffix) {
        if (base.endsWith("/")) {
            return base + suffix;
        }
        return base + "/" + suffix;
    }

    private Map<String, String> parseDelimitedMap(String raw, boolean normalizeKey, boolean normalizeValue) {
        Map<String, String> parsed = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return parsed;
        }
        for (String entry : raw.split(",")) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String[] pair = entry.split("=", 2);
            if (pair.length != 2) {
                continue;
            }
            String key = pair[0].trim();
            String value = pair[1].trim();
            if (key.isBlank() || value.isBlank()) {
                continue;
            }
            if (normalizeKey) {
                key = normalizeRoutingToken(key);
            }
            if (normalizeValue) {
                value = normalizeRoutingToken(value);
            }
            parsed.put(key, value);
        }
        return parsed;
    }

    private String normalizeRoutingToken(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String maybeSignUrl(String url) {
        if (!storageProperties.isCdnSigningEnabled()) {
            return url;
        }
        if (storageProperties.getCdnSigningKey() == null || storageProperties.getCdnSigningKey().isBlank()) {
            throw new IllegalStateException("tts.storage.cdn-signing-key is required when cdn-signing-enabled=true");
        }
        long expiresAt = Instant.now(clock).getEpochSecond() + storageProperties.getCdnSigningTtlSeconds();
        URI uri = URI.create(url);
        String host = uri.getHost() == null ? "" : uri.getHost();
        String path = uri.getPath() == null ? "" : uri.getPath();
        String payload = host + path + ":" + expiresAt;
        String signature = hmacSha256Base64Url(payload, storageProperties.getCdnSigningKey());
        String separator = url.contains("?") ? "&" : "?";
        return url + separator
                + storageProperties.getCdnSigningExpiresParam() + "=" + expiresAt
                + "&" + storageProperties.getCdnSigningSignatureParam() + "=" + signature;
    }

    private String hmacSha256Base64Url(String payload, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to generate CDN signature", exception);
        }
    }
}
