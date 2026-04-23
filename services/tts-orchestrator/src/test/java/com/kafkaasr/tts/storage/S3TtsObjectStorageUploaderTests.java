package com.kafkaasr.tts.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

@ExtendWith(MockitoExtension.class)
class S3TtsObjectStorageUploaderTests {

    @Mock
    private S3Client s3Client;

    @Test
    void uploadsAudioAndBuildsPlaybackUrlWithPublicBaseUrl() {
        TtsStorageProperties properties = storageProperties(Map.of(
                "enabled", "true",
                "provider", "s3",
                "bucket", "tts-audio",
                "keyPrefix", "tts-cache",
                "publicBaseUrl", "https://cdn.example.com/tts"));

        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().eTag("etag-1").build());

        S3TtsObjectStorageUploader uploader = new S3TtsObjectStorageUploader(properties, s3Client);
        TtsObjectStorageUploader.UploadResult result = uploader.upload(new TtsObjectStorageUploader.UploadRequest(
                "tenant-a",
                "sess-1",
                7L,
                "tts:v1:abc",
                "audio/pcm",
                "hello".getBytes(),
                "https://fallback.example.com/tts.wav"));

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));
        PutObjectRequest putObjectRequest = requestCaptor.getValue();
        assertEquals("tts-audio", putObjectRequest.bucket());
        assertEquals("audio/pcm", putObjectRequest.contentType());
        assertEquals("tts-cache/tenant-a/tts_v1_abc.wav", putObjectRequest.key());
        assertEquals("public, max-age=31536000, immutable", putObjectRequest.cacheControl());

        assertEquals("tts-cache/tenant-a/tts_v1_abc.wav", result.objectKey());
        assertEquals("https://cdn.example.com/tts/tts-cache/tenant-a/tts_v1_abc.wav", result.playbackUrl());
    }

    @Test
    void buildsEndpointBasedUrlWhenPublicBaseUrlMissing() {
        TtsStorageProperties properties = storageProperties(Map.of(
                "enabled", "true",
                "provider", "s3",
                "bucket", "tts-audio",
                "keyPrefix", "tts-cache",
                "endpoint", "http://minio:9000"));

        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().eTag("etag-2").build());

        S3TtsObjectStorageUploader uploader = new S3TtsObjectStorageUploader(properties, s3Client);
        TtsObjectStorageUploader.UploadResult result = uploader.upload(new TtsObjectStorageUploader.UploadRequest(
                "tenant-b",
                "sess-2",
                8L,
                "tts:v1:def",
                "audio/wav",
                "world".getBytes(),
                "https://fallback.example.com/tts.wav"));

        assertEquals(
                "http://minio:9000/tts-audio/tts-cache/tenant-b/tts_v1_def.wav",
                result.playbackUrl());
    }

    @Test
    void signsPlaybackUrlWhenCdnSigningEnabled() {
        TtsStorageProperties properties = storageProperties(Map.of(
                "enabled", "true",
                "provider", "s3",
                "bucket", "tts-audio",
                "keyPrefix", "tts-cache",
                "publicBaseUrl", "https://cdn.example.com/playback",
                "cdnSigningEnabled", "true",
                "cdnSigningKey", "test-signing-key",
                "cdnSigningTtlSeconds", "120"));
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().eTag("etag-3").build());

        S3TtsObjectStorageUploader uploader = new S3TtsObjectStorageUploader(
                properties,
                s3Client,
                Clock.fixed(Instant.parse("2026-04-23T00:00:00Z"), ZoneOffset.UTC));
        TtsObjectStorageUploader.UploadResult result = uploader.upload(new TtsObjectStorageUploader.UploadRequest(
                "tenant-c",
                "sess-3",
                9L,
                "tts:v1:ghi",
                "audio/wav",
                "signed".getBytes(),
                "https://fallback.example.com/tts.wav"));

        URI signedUri = URI.create(result.playbackUrl());
        assertTrue(signedUri.getQuery().contains("expires=1776902520"));
        assertTrue(signedUri.getQuery().contains("sig="));
        String signature = extractQueryValue(signedUri.getQuery(), "sig");
        assertNotNull(signature);
        assertTrue(signature.length() > 20);
    }

    private static TtsStorageProperties storageProperties(Map<String, String> overrides) {
        TtsStorageProperties properties = new TtsStorageProperties();
        properties.setEnabled(Boolean.parseBoolean(overrides.getOrDefault("enabled", "true")));
        properties.setProvider(overrides.getOrDefault("provider", "s3"));
        properties.setBucket(overrides.getOrDefault("bucket", "tts-audio"));
        properties.setRegion(overrides.getOrDefault("region", "us-east-1"));
        properties.setKeyPrefix(overrides.getOrDefault("keyPrefix", "tts"));
        properties.setEndpoint(overrides.getOrDefault("endpoint", ""));
        properties.setPublicBaseUrl(overrides.getOrDefault("publicBaseUrl", ""));
        properties.setObjectSuffix(overrides.getOrDefault("objectSuffix", "wav"));
        properties.setCacheControl(overrides.getOrDefault("cacheControl", "public, max-age=31536000, immutable"));
        properties.setCdnSigningEnabled(Boolean.parseBoolean(overrides.getOrDefault("cdnSigningEnabled", "false")));
        properties.setCdnSigningKey(overrides.getOrDefault("cdnSigningKey", ""));
        properties.setCdnSigningTtlSeconds(Long.parseLong(overrides.getOrDefault("cdnSigningTtlSeconds", "600")));
        properties.setCdnSigningExpiresParam(overrides.getOrDefault("cdnSigningExpiresParam", "expires"));
        properties.setCdnSigningSignatureParam(overrides.getOrDefault("cdnSigningSignatureParam", "sig"));
        return properties;
    }

    private static String extractQueryValue(String query, String key) {
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && key.equals(pair[0])) {
                return pair[1];
            }
        }
        return null;
    }
}
