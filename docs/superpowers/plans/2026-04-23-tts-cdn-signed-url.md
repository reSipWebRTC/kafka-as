# TTS CDN Signed URL and Cache Strategy Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend TTS object-storage playback delivery with configurable CDN signed URLs and cache-control policy.

**Architecture:** Keep current `tts.ready` storage upload pipeline unchanged, and add URL-signing + cache headers at uploader layer so retry/DLQ behavior remains untouched.

**Tech Stack:** Spring Boot, AWS SDK v2 S3, JUnit 5, Mockito.

---

### Task 1: Storage config and uploader extension

**Files:**
- Modify: `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/storage/TtsStorageProperties.java`
- Modify: `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/storage/S3TtsObjectStorageUploader.java`
- Modify: `services/tts-orchestrator/src/main/resources/application.yml`

- [x] Add `tts.storage.cache-control` and CDN signing fields.
- [x] Apply `cache-control` on uploaded object metadata.
- [x] Add deterministic URL signing (`expires` + `sig`) with configurable key/ttl/param names.

### Task 2: Tests and docs

**Files:**
- Modify: `services/tts-orchestrator/src/test/java/com/kafkaasr/tts/storage/S3TtsObjectStorageUploaderTests.java`
- Modify: `services/tts-orchestrator/README.md`
- Modify: `docs/implementation-status.md`
- Modify: `docs/services.md`
- Modify: `docs/roadmap.md`

- [x] Add tests for cache-control propagation and signed playback URL generation.
- [x] Update docs to reflect signed URL/cache policy baseline.

### Task 3: Verification

**Files:**
- Modify: `docs/superpowers/plans/2026-04-23-tts-cdn-signed-url.md`

- [x] Run `./gradlew :services:tts-orchestrator:test`.
- [x] Run `bash tools/verify.sh`.
