package com.kafkaasr.tts.storage;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
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
        return new UploadResult(objectKey, buildPlaybackUrl(objectKey));
    }

    private void validateProperties(TtsStorageProperties properties) {
        if (!"s3".equalsIgnoreCase(properties.getProvider())) {
            throw new IllegalStateException("Unsupported tts.storage.provider: " + properties.getProvider());
        }
        if (properties.getBucket() == null || properties.getBucket().isBlank()) {
            throw new IllegalStateException("tts.storage.bucket is required when storage is enabled");
        }
    }

    private String buildObjectKey(UploadRequest request) {
        String prefix = trimSlashes(storageProperties.getKeyPrefix());
        String tenant = sanitizeSegment(request.tenantId());
        String cacheKey = sanitizeSegment(request.cacheKey());
        String suffix = sanitizeSegment(storageProperties.getObjectSuffix().toLowerCase(Locale.ROOT));
        String fileName = cacheKey + "." + suffix;
        if (prefix.isBlank()) {
            return tenant + "/" + fileName;
        }
        return prefix + "/" + tenant + "/" + fileName;
    }

    private String buildPlaybackUrl(String objectKey) {
        String url;
        if (storageProperties.getPublicBaseUrl() != null && !storageProperties.getPublicBaseUrl().isBlank()) {
            url = joinUrl(storageProperties.getPublicBaseUrl(), objectKey);
        } else if (storageProperties.getEndpoint() != null && !storageProperties.getEndpoint().isBlank()) {
            url = joinUrl(storageProperties.getEndpoint(), storageProperties.getBucket() + "/" + objectKey);
        } else {
            String region = storageProperties.getRegion();
            url = "https://" + storageProperties.getBucket() + ".s3." + region + ".amazonaws.com/" + objectKey;
        }
        return maybeSignUrl(url);
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
