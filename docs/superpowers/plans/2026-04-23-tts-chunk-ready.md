# TTS Chunk/Ready Distribution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close `tts.request -> tts.chunk / tts.ready` in `tts-orchestrator` with contract-aligned schemas, code path, and tests.

**Architecture:** Keep `translation.result` as the single input. Build three output events (`tts.request`, `tts.chunk`, `tts.ready`) from one pipeline mapping, then publish all three in one consumer success path so existing retry/DLQ/idempotency semantics stay coherent.

**Tech Stack:** Spring Boot, Spring Kafka, Jackson, Reactor, JUnit 5, Mockito.

---

### Task 1: Event contracts and config surface

**Files:**
- Create: `api/json-schema/tts.chunk.v1.json`
- Create: `api/json-schema/tts.ready.v1.json`
- Modify: `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/events/TtsKafkaProperties.java`
- Modify: `services/tts-orchestrator/src/main/resources/application.yml`

- [x] Add `tts.chunk` and `tts.ready` JSON schemas with v1 envelope + strict payload fields.
- [x] Extend `TtsKafkaProperties` with chunk/ready topic names and payload defaults needed by pipeline.
- [x] Wire new properties in `application.yml` with env var overrides.

### Task 2: Pipeline and publisher implementation

**Files:**
- Create: `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/events/TtsChunkEvent.java`
- Create: `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/events/TtsReadyEvent.java`
- Create: `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/events/TtsReadyPayload.java`
- Create: `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/kafka/TtsChunkPublisher.java`
- Create: `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/kafka/TtsReadyPublisher.java`
- Create: `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/kafka/KafkaTtsChunkPublisher.java`
- Create: `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/kafka/KafkaTtsReadyPublisher.java`
- Create: `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/kafka/NoopTtsChunkPublisher.java`
- Create: `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/kafka/NoopTtsReadyPublisher.java`
- Modify: `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/pipeline/TtsRequestPipelineService.java`
- Modify: `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/kafka/TranslationResultConsumer.java`

- [x] Add event records for chunk/ready.
- [x] Add Kafka + noop publishers for chunk/ready following existing request publisher pattern.
- [x] Extend pipeline to produce all three events in one mapping output.
- [x] Update consumer to publish request/chunk/ready and keep existing retry/DLQ/idempotency behavior unchanged.

### Task 3: Tests, docs, and verification

**Files:**
- Create: `services/tts-orchestrator/src/test/java/com/kafkaasr/tts/kafka/KafkaTtsChunkPublisherTests.java`
- Create: `services/tts-orchestrator/src/test/java/com/kafkaasr/tts/kafka/KafkaTtsReadyPublisherTests.java`
- Modify: `services/tts-orchestrator/src/test/java/com/kafkaasr/tts/pipeline/TtsRequestPipelineServiceTests.java`
- Modify: `services/tts-orchestrator/src/test/java/com/kafkaasr/tts/kafka/TranslationResultConsumerTests.java`
- Modify: `docs/contracts.md`
- Modify: `docs/event-model.md`
- Modify: `docs/implementation-status.md`
- Modify: `docs/services.md`
- Modify: `services/tts-orchestrator/README.md`

- [x] Add/adjust unit tests for new pipeline output and three-topic publish path.
- [x] Update docs and status from “planned” to “implemented in repo”.
- [x] Run module tests and `tools/verify.sh`.
