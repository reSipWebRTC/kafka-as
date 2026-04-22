# TTS Orchestrator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the first runnable `tts-orchestrator` pipeline: consume `translation.result` events from Kafka and publish `tts.request` events to Kafka with stable v1 envelope fields.

**Architecture:** Introduce a new `services/tts-orchestrator` Spring Boot module with one inbound Kafka consumer, one pipeline service, one outbound publisher, and a pluggable voice/cache strategy interface. In this phase, no real TTS engine call is needed; focus on contract-aligned event transformation.

**Tech Stack:** Spring Boot 3, Spring Kafka, Jackson, Reactor, JUnit 5, Mockito.

**Source of truth:**
- `docs/contracts.md`
- `docs/event-model.md`
- `docs/services.md`
- `api/json-schema/translation.result.v1.json`
- `api/json-schema/tts.request.v1.json`

---

### Task 1: Scaffold `tts-orchestrator` Module

**Files:**
- Modify: `settings.gradle.kts`
- Create: `services/tts-orchestrator/build.gradle.kts`
- Create: `services/tts-orchestrator/README.md`
- Create: `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/TtsOrchestratorApplication.java`
- Create: `services/tts-orchestrator/src/main/resources/application.yml`
- Create: `services/tts-orchestrator/src/test/java/com/kafkaasr/tts/TtsOrchestratorApplicationTests.java`

- [x] Register `:services:tts-orchestrator` in Gradle settings.
- [x] Add module dependencies (`spring-boot-starter-actuator`, `spring-kafka`, `spring-boot-starter-validation`, tests).
- [x] Add baseline app bootstrap and health readiness config.
- [x] Document scope and non-goals in module README.

### Task 2: Implement Contract-Aligned Event Models and Pipeline Core

**Files:**
- Create: `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/events/TranslationResultEvent.java`
- Create: `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/events/TranslationResultPayload.java`
- Create: `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/events/TtsRequestEvent.java`
- Create: `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/events/TtsRequestPayload.java`
- Create: `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/events/TtsKafkaProperties.java`
- Create: `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/pipeline/VoicePolicy.java`
- Create: `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/pipeline/PlaceholderVoicePolicy.java`
- Create: `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/pipeline/TtsRequestPipelineService.java`

- [x] Define strict Java models matching v1 schemas for `translation.result` input and `tts.request` output.
- [x] Add Kafka topic/config properties (`translation.result`, `tts.request`, producer id, default voice, enabled toggle).
- [x] Implement pipeline service that maps one `translation.result` event to one `tts.request` event while preserving trace/session/tenant/seq semantics.
- [x] Generate deterministic `cacheKey` and normalize text/language for first-phase request stability.

### Task 3: Wire Kafka Consumer and Publisher Flow

**Files:**
- Create: `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/kafka/TranslationResultConsumer.java`
- Create: `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/kafka/TtsRequestPublisher.java`
- Create: `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/kafka/KafkaTtsRequestPublisher.java`
- Create: `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/kafka/NoopTtsRequestPublisher.java`

- [x] Consume raw JSON from `translation.result` and deserialize safely.
- [x] Process through `TtsRequestPipelineService` and publish JSON to `tts.request` keyed by `sessionId`.
- [x] On malformed input, fail fast with clear logs and avoid emitting invalid `tts.request` payloads.
- [x] Keep flow single-responsibility: consumer transport, pipeline transform, publisher output.

### Task 4: Add Tests and Run Full Verification

**Files:**
- Create: `services/tts-orchestrator/src/test/java/com/kafkaasr/tts/pipeline/TtsRequestPipelineServiceTests.java`
- Create: `services/tts-orchestrator/src/test/java/com/kafkaasr/tts/kafka/KafkaTtsRequestPublisherTests.java`
- Create: `services/tts-orchestrator/src/test/java/com/kafkaasr/tts/kafka/TranslationResultConsumerTests.java`

- [x] Test mapping for required envelope/payload fields (`text`, `language`, `voice`, `cacheKey`, `stream`).
- [x] Test invalid JSON behavior in consumer.
- [x] Test publisher serialization and topic/key usage.
- [x] Run:
  - `./gradlew :services:tts-orchestrator:test`
  - `tools/verify.sh`

## Non-goals in This Feature

- No real TTS engine invocation yet.
- No `tts.chunk` or `tts.ready` event publishing yet.
- No object storage/CDN integration in this branch.

## Exit Criteria

- New `tts-orchestrator` module builds and tests pass.
- `translation.result` -> `tts.request` pipeline works with contract-aligned event JSON.
- Repository verification passes with `tools/verify.sh`.
