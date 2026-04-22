# Reliability Policy V2 (Translation + TTS) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend tenant-policy-driven retry and DLQ controls from `asr-worker` to `translation-worker` and `tts-orchestrator`.

**Architecture:** Reuse the same pattern already applied in `asr-worker`: per-message tenant policy resolution from `control-plane`, local cached fallback, in-consumer retry loop controlled by policy, and tenant-aware DLQ topic suffix routed through Kafka error handler via explicit runtime exception.

**Tech Stack:** Spring Boot 3, Spring Kafka, Spring WebFlux/WebClient, Micrometer, JUnit 5, Mockito.

---

### Task 1: Translation worker runtime rollout

**Files:**
- `services/translation-worker/src/main/java/com/kafkaasr/translation/TranslationWorkerApplication.java`
- `services/translation-worker/src/main/resources/application.yml`
- `services/translation-worker/src/main/java/com/kafkaasr/translation/kafka/AsrFinalConsumer.java`
- `services/translation-worker/src/main/java/com/kafkaasr/translation/kafka/TranslationKafkaConsumerConfig.java`
- `services/translation-worker/src/main/java/com/kafkaasr/translation/kafka/TranslationCompensationPublisher.java`
- `services/translation-worker/src/main/java/com/kafkaasr/translation/policy/*` (new)
- `services/translation-worker/src/main/java/com/kafkaasr/translation/kafka/TenantAwareDlqException.java` (new)

- [x] Add translation control-plane properties and tenant reliability policy resolver.
- [x] Switch `AsrFinalConsumer` from fixed-config retry tracking to per-message tenant policy retry/backoff.
- [x] Route DLQ suffix dynamically in Kafka recoverer and include resolved DLQ topic in compensation payload.

### Task 2: TTS orchestrator runtime rollout

**Files:**
- `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/TtsOrchestratorApplication.java`
- `services/tts-orchestrator/src/main/resources/application.yml`
- `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/kafka/TranslationResultConsumer.java`
- `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/kafka/TtsKafkaConsumerConfig.java`
- `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/kafka/TtsCompensationPublisher.java`
- `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/policy/*` (new)
- `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/kafka/TenantAwareDlqException.java` (new)

- [x] Add tts control-plane properties and tenant reliability policy resolver.
- [x] Switch `TranslationResultConsumer` from fixed-config retry tracking to per-message tenant policy retry/backoff.
- [x] Route DLQ suffix dynamically in Kafka recoverer and include resolved DLQ topic in compensation payload.

### Task 3: Tests, docs, verification

**Files:**
- `services/translation-worker/src/test/java/com/kafkaasr/translation/kafka/AsrFinalConsumerTests.java`
- `services/translation-worker/src/test/java/com/kafkaasr/translation/policy/*` (new)
- `services/tts-orchestrator/src/test/java/com/kafkaasr/tts/kafka/TranslationResultConsumerTests.java`
- `services/tts-orchestrator/src/test/java/com/kafkaasr/tts/policy/*` (new)
- `docs/contracts.md`
- `docs/services.md`
- `docs/implementation-status.md`

- [x] Add/adjust tests for translation and tts tenant-policy-driven retry/DLQ behavior.
- [x] Update docs to mark tenant-policy-driven retry/DLQ rollout across all core worker consumers.
- [x] Run repository verification.

## Verification

- [x] `./gradlew :services/translation-worker:test`
- [x] `./gradlew :services/tts-orchestrator:test`
- [x] `tools/verify.sh`
