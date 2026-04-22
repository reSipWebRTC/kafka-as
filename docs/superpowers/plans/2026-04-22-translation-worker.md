# Translation Worker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the first runnable `translation-worker` pipeline: consume `asr.final` events from Kafka and publish `translation.result` events to Kafka with stable v1 envelope fields.

**Architecture:** Introduce a new `services/translation-worker` Spring Boot module with one inbound Kafka consumer, one pipeline service, one outbound publisher, and a pluggable translation engine interface. In this phase, translation can be deterministic placeholder logic, but event shape and flow must match frozen contracts.

**Tech Stack:** Spring Boot 3, Spring Kafka, Jackson, Reactor, JUnit 5, Mockito.

**Source of truth:**
- `docs/contracts.md`
- `docs/event-model.md`
- `docs/services.md`
- `api/json-schema/asr.final.v1.json`
- `api/json-schema/translation.result.v1.json`

---

### Task 1: Scaffold `translation-worker` Module

**Files:**
- Modify: `settings.gradle.kts`
- Create: `services/translation-worker/build.gradle.kts`
- Create: `services/translation-worker/README.md`
- Create: `services/translation-worker/src/main/java/com/kafkaasr/translation/TranslationWorkerApplication.java`
- Create: `services/translation-worker/src/main/resources/application.yml`
- Create: `services/translation-worker/src/test/java/com/kafkaasr/translation/TranslationWorkerApplicationTests.java`

- [x] Register `:services:translation-worker` in Gradle settings.
- [x] Add module dependencies (`spring-boot-starter-actuator`, `spring-kafka`, `spring-boot-starter-validation`, tests).
- [x] Add baseline app bootstrap and health readiness config.
- [x] Document scope and out-of-scope in module README.

### Task 2: Implement Contract-Aligned Event Models and Pipeline Core

**Files:**
- Create: `services/translation-worker/src/main/java/com/kafkaasr/translation/events/AsrFinalEvent.java`
- Create: `services/translation-worker/src/main/java/com/kafkaasr/translation/events/AsrFinalPayload.java`
- Create: `services/translation-worker/src/main/java/com/kafkaasr/translation/events/TranslationResultEvent.java`
- Create: `services/translation-worker/src/main/java/com/kafkaasr/translation/events/TranslationResultPayload.java`
- Create: `services/translation-worker/src/main/java/com/kafkaasr/translation/events/TranslationKafkaProperties.java`
- Create: `services/translation-worker/src/main/java/com/kafkaasr/translation/pipeline/TranslationEngine.java`
- Create: `services/translation-worker/src/main/java/com/kafkaasr/translation/pipeline/PlaceholderTranslationEngine.java`
- Create: `services/translation-worker/src/main/java/com/kafkaasr/translation/pipeline/TranslationPipelineService.java`

- [x] Define strict Java models matching v1 schemas for `asr.final` input and `translation.result` output.
- [x] Add Kafka topic/config properties (`asr.final`, `translation.result`, producer id, target language, enabled toggle).
- [x] Implement pipeline service that maps one `asr.final` event to one `translation.result` event while preserving trace/session/tenant/seq semantics.
- [x] Keep translation behind `TranslationEngine` to allow later real engine integration without changing Kafka edge logic.

### Task 3: Wire Kafka Consumer and Publisher Flow

**Files:**
- Create: `services/translation-worker/src/main/java/com/kafkaasr/translation/kafka/AsrFinalConsumer.java`
- Create: `services/translation-worker/src/main/java/com/kafkaasr/translation/kafka/TranslationResultPublisher.java`
- Create: `services/translation-worker/src/main/java/com/kafkaasr/translation/kafka/KafkaTranslationResultPublisher.java`
- Create: `services/translation-worker/src/main/java/com/kafkaasr/translation/kafka/NoopTranslationResultPublisher.java`

- [x] Consume raw JSON from `asr.final` and deserialize safely.
- [x] Process through `TranslationPipelineService` and publish JSON to `translation.result` keyed by `sessionId`.
- [x] On malformed input, fail fast with clear logs and avoid emitting invalid `translation.result` payloads.
- [x] Keep flow single-responsibility: consumer transport, pipeline transform, publisher output.

### Task 4: Add Tests and Run Full Verification

**Files:**
- Create: `services/translation-worker/src/test/java/com/kafkaasr/translation/pipeline/TranslationPipelineServiceTests.java`
- Create: `services/translation-worker/src/test/java/com/kafkaasr/translation/kafka/KafkaTranslationResultPublisherTests.java`
- Create: `services/translation-worker/src/test/java/com/kafkaasr/translation/kafka/AsrFinalConsumerTests.java`

- [x] Test mapping for required envelope/payload fields.
- [x] Test invalid JSON behavior in consumer.
- [x] Test publisher serialization and topic/key usage.
- [x] Run:
  - `./gradlew :services:translation-worker:test`
  - `tools/verify.sh`

## Non-goals in This Feature

- No real LLM/MT engine integration yet.
- No terminology glossary or context memory integration yet.
- No websocket subtitle downlink changes in this branch.

## Exit Criteria

- New `translation-worker` module builds and tests pass.
- `asr.final` -> `translation.result` pipeline works with contract-aligned event JSON.
- Repository verification passes with `tools/verify.sh`.
