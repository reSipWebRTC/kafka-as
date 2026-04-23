package com.kafkaasr.tts.storage;

import java.net.URI;
import java.util.Locale;
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

    public S3TtsObjectStorageUploader(TtsStorageProperties storageProperties) {
        this(storageProperties, createClient(storageProperties));
    }

    S3TtsObjectStorageUploader(TtsStorageProperties storageProperties, S3Client s3Client) {
        this.storageProperties = storageProperties;
        this.s3Client = s3Client;
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
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(storageProperties.getBucket())
                .key(objectKey)
                .contentType(normalizeContentType(request.codec()))
                .build();

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
        if (storageProperties.getPublicBaseUrl() != null && !storageProperties.getPublicBaseUrl().isBlank()) {
            return joinUrl(storageProperties.getPublicBaseUrl(), objectKey);
        }
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
}
