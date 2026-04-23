package com.kafkaasr.tts.storage;

public interface TtsObjectStorageUploader {

    UploadResult upload(UploadRequest request);

    record UploadRequest(
            String tenantId,
            String sessionId,
            long seq,
            String cacheKey,
            String codec,
            byte[] audioBytes,
            String fallbackPlaybackUrl) {
    }

    record UploadResult(
            String objectKey,
            String playbackUrl) {
    }
}
