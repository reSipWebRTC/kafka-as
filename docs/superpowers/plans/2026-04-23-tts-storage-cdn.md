# TTS Storage/CDN Playback URL Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add object storage upload in `tts-orchestrator` and emit `tts.ready.playbackUrl` as a real storage URL.

**Architecture:** Keep existing event modeling (`tts.request` / `tts.chunk` / `tts.ready`) and insert an upload step in consumer processing before `tts.ready` publish. Upload failures bubble as runtime failures so existing retry/backoff/DLQ/compensation semantics remain unchanged.

**Tech Stack:** Spring Boot WebFlux/Kafka, AWS SDK v2 S3 (MinIO-compatible endpoint/path-style), JUnit 5, Mockito.

---

### Task 1: Storage config and uploader component

**Files:**
- Create: `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/storage/TtsStorageProperties.java`
- Create: `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/storage/TtsObjectStorageUploader.java`
- Create: `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/storage/S3TtsObjectStorageUploader.java`
- Create: `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/storage/NoopTtsObjectStorageUploader.java`
- Modify: `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/TtsOrchestratorApplication.java`
- Modify: `services/tts-orchestrator/build.gradle.kts`
- Modify: `services/tts-orchestrator/src/main/resources/application.yml`

- [x] Add storage properties (`tts.storage.*`) for enabled/provider/bucket/endpoint/credentials/path-style/public-base-url/key-prefix.
- [x] Add S3 uploader implementation with endpoint override and path-style support for MinIO compatibility.
- [x] Add noop uploader fallback when storage disabled.
- [x] Wire properties and dependencies.

### Task 2: Integrate upload with ready-event publish

**Files:**
- Modify: `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/kafka/TranslationResultConsumer.java`

- [x] Upload synthesized bytes before publishing `tts.ready`.
- [x] Replace `tts.ready.payload.playbackUrl` with returned storage URL.
- [x] Keep request/chunk/ready publish ordering and existing retry/DLQ/idempotency behavior.

### Task 3: Tests, docs, and verification

**Files:**
- Create: `services/tts-orchestrator/src/test/java/com/kafkaasr/tts/storage/S3TtsObjectStorageUploaderTests.java`
- Modify: `services/tts-orchestrator/src/test/java/com/kafkaasr/tts/kafka/TranslationResultConsumerTests.java`
- Modify: `services/tts-orchestrator/src/test/java/com/kafkaasr/tts/TtsOrchestratorApplicationTests.java`
- Modify: `services/tts-orchestrator/README.md`
- Modify: `docs/implementation-status.md`
- Modify: `docs/services.md`
- Modify: `docs/roadmap.md`

- [x] Add uploader unit tests for URL/key generation and config behavior.
- [x] Add consumer tests for upload success/failure path.
- [x] Update docs to mark storage/CDN integration baseline in-progress/implemented scope.
- [x] Run `./gradlew :services:tts-orchestrator:test` and `bash tools/verify.sh`.
