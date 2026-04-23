package com.kafkaasr.tts.storage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "tts.storage.enabled", havingValue = "false", matchIfMissing = true)
public class NoopTtsObjectStorageUploader implements TtsObjectStorageUploader {

    @Override
    public UploadResult upload(UploadRequest request) {
        return new UploadResult("", request.fallbackPlaybackUrl());
    }
}
