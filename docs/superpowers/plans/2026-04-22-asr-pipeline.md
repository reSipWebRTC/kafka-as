# ASR Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the first runnable `asr-worker` pipeline: consume `audio.ingress.raw` from Kafka and publish `asr.final` events to Kafka with stable v1 envelope fields.

**Architecture:** Introduce a new `services/asr-worker` Spring Boot module with one inbound Kafka consumer, one pipeline service, one outbound publisher, and a pluggable inference interface. In this phase, inference can be deterministic placeholder logic, but event shape and flow must match frozen contracts.

**Tech Stack:** Spring Boot 3, Spring Kafka, Jackson, Reactor, JUnit 5, Mockito.

**Source of truth:**
- `docs/contracts.md`
- `docs/event-model.md`
- `docs/services.md`
- `api/json-schema/audio.ingress.raw.v1.json`
- `api/json-schema/asr.final.v1.json`

---

### Task 1: Scaffold `asr-worker` Module

**Files:**
- Modify: `settings.gradle.kts`
- Create: `services/asr-worker/build.gradle.kts`
- Create: `services/asr-worker/README.md`
- Create: `services/asr-worker/src/main/java/com/kafkaasr/asr/AsrWorkerApplication.java`
- Create: `services/asr-worker/src/main/resources/application.yml`
- Create: `services/asr-worker/src/test/java/com/kafkaasr/asr/AsrWorkerApplicationTests.java`

- [x] Register `:services:asr-worker` in Gradle settings.
- [x] Add module dependencies (`spring-boot-starter-actuator`, `spring-kafka`, `spring-boot-starter-validation`, tests).
- [x] Add baseline app bootstrap and health readiness config.
- [x] Document scope and out-of-scope in module README.

### Task 2: Implement Contract-Aligned Event Models and Pipeline Core

**Files:**
- Create: `services/asr-worker/src/main/java/com/kafkaasr/asr/events/AudioIngressRawEvent.java`
- Create: `services/asr-worker/src/main/java/com/kafkaasr/asr/events/AudioIngressRawPayload.java`
- Create: `services/asr-worker/src/main/java/com/kafkaasr/asr/events/AsrFinalEvent.java`
- Create: `services/asr-worker/src/main/java/com/kafkaasr/asr/events/AsrFinalPayload.java`
- Create: `services/asr-worker/src/main/java/com/kafkaasr/asr/events/AsrKafkaProperties.java`
- Create: `services/asr-worker/src/main/java/com/kafkaasr/asr/pipeline/AsrInferenceEngine.java`
- Create: `services/asr-worker/src/main/java/com/kafkaasr/asr/pipeline/PlaceholderAsrInferenceEngine.java`
- Create: `services/asr-worker/src/main/java/com/kafkaasr/asr/pipeline/AsrPipelineService.java`

- [x] Define strict Java models matching v1 schemas for `audio.ingress.raw` input and `asr.final` output.
- [x] Add Kafka topic/config properties (`audio.ingress.raw`, `asr.final`, producer id, enabled toggle).
- [x] Implement pipeline service that maps one ingress event to one final event while preserving `traceId`, `sessionId`, `tenantId`, and sequence semantics.
- [x] Keep inference behind `AsrInferenceEngine` to allow later FunASR integration without changing Kafka edge logic.

### Task 3: Wire Kafka Consumer and Publisher Flow

**Files:**
- Create: `services/asr-worker/src/main/java/com/kafkaasr/asr/kafka/AudioIngressConsumer.java`
- Create: `services/asr-worker/src/main/java/com/kafkaasr/asr/kafka/AsrFinalPublisher.java`
- Create: `services/asr-worker/src/main/java/com/kafkaasr/asr/kafka/KafkaAsrFinalPublisher.java`
- Create: `services/asr-worker/src/main/java/com/kafkaasr/asr/kafka/NoopAsrFinalPublisher.java`

- [x] Consume raw event JSON from `audio.ingress.raw` and deserialize safely.
- [x] Process through `AsrPipelineService` and publish JSON to `asr.final` keyed by `sessionId`.
- [x] On malformed input, fail fast with clear logs and avoid emitting invalid `asr.final` payloads.
- [x] Keep flow single-responsibility: consumer does transport, pipeline does transform, publisher does output.

### Task 4: Add Tests and Run Full Verification

**Files:**
- Create: `services/asr-worker/src/test/java/com/kafkaasr/asr/pipeline/AsrPipelineServiceTests.java`
- Create: `services/asr-worker/src/test/java/com/kafkaasr/asr/kafka/KafkaAsrFinalPublisherTests.java`
- Create: `services/asr-worker/src/test/java/com/kafkaasr/asr/kafka/AudioIngressConsumerTests.java`

- [x] Test ingress -> final mapping for required envelope fields and payload fields.
- [x] Test invalid JSON / invalid schema behavior in consumer.
- [x] Test publisher serialization and topic/key usage.
- [x] Run:
  - `./gradlew :services:asr-worker:test`
  - `tools/verify.sh`

## Non-goals in This Feature

- No real FunASR model runtime integration yet.
- No `asr.partial` publishing yet.
- No translation or websocket downstream wiring in this branch.

## Exit Criteria

- New `asr-worker` module builds and tests pass.
- `audio.ingress.raw` -> `asr.final` pipeline works with contract-aligned event JSON.
- Repository verification passes with `tools/verify.sh`.
