# ASR Partial Path Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce `asr.partial` as a first-class event in the running pipeline, and make `speech-gateway` consume `asr.partial` for `subtitle.partial` downlink to reduce subtitle jitter and keep event semantics aligned.

**Source of truth:**
- `docs/contracts.md`
- `docs/event-model.md`
- `api/json-schema/*`
- `api/protobuf/realtime_speech.proto`
- `services/asr-worker/*`
- `services/speech-gateway/*`

---

### Task 1: Add `asr.partial` publishing in `asr-worker`

**Files:**
- `services/asr-worker/src/main/java/com/kafkaasr/asr/events/AsrPartialEvent.java`
- `services/asr-worker/src/main/java/com/kafkaasr/asr/events/AsrPartialPayload.java`
- `services/asr-worker/src/main/java/com/kafkaasr/asr/events/AsrKafkaProperties.java`
- `services/asr-worker/src/main/java/com/kafkaasr/asr/pipeline/AsrPipelineService.java`
- `services/asr-worker/src/main/java/com/kafkaasr/asr/kafka/AudioIngressConsumer.java`
- `services/asr-worker/src/main/java/com/kafkaasr/asr/kafka/AsrPartialPublisher.java`
- `services/asr-worker/src/main/java/com/kafkaasr/asr/kafka/KafkaAsrPartialPublisher.java`
- `services/asr-worker/src/main/java/com/kafkaasr/asr/kafka/NoopAsrPartialPublisher.java`
- `services/asr-worker/src/main/resources/application.yml`
- `services/asr-worker/src/test/java/com/kafkaasr/asr/pipeline/AsrPipelineServiceTests.java`
- `services/asr-worker/src/test/java/com/kafkaasr/asr/kafka/AudioIngressConsumerTests.java`
- `services/asr-worker/src/test/java/com/kafkaasr/asr/kafka/KafkaAsrPartialPublisherTests.java`

- [x] Add `asr.partial` event model and Kafka publisher abstraction.
- [x] Extend ASR Kafka config with `asr-partial-topic`.
- [x] Emit `asr.partial` and `asr.final` from one pipeline pass, preserving envelope semantics.
- [x] Publish in order: `asr.partial` first, then `asr.final`.
- [x] Add/adjust unit tests for pipeline mapping, publish order, and Kafka serialization.

### Task 2: Switch subtitle partial downlink source to `asr.partial`

**Files:**
- `services/speech-gateway/src/main/java/com/kafkaasr/gateway/ws/downlink/AsrPartialDownlinkConsumer.java`
- `services/speech-gateway/src/main/java/com/kafkaasr/gateway/ws/downlink/events/AsrPartialEvent.java`
- `services/speech-gateway/src/main/java/com/kafkaasr/gateway/ws/downlink/events/AsrPartialPayload.java`
- `services/speech-gateway/src/main/resources/application.yml`
- `services/speech-gateway/src/test/java/com/kafkaasr/gateway/ws/downlink/AsrPartialDownlinkConsumerTests.java`

- [x] Replace the former `asr.final` subtitle-partial consumer with `asr.partial`.
- [x] Rename downlink event model classes from final to partial to keep semantics explicit.
- [x] Update config key/env var to `gateway.downlink.asr-partial-topic`.
- [x] Update tests to validate `asr.partial -> subtitle.partial`.

### Task 3: Sync contracts/docs and verify

**Files:**
- `api/json-schema/asr.partial.v1.json`
- `api/protobuf/realtime_speech.proto`
- `docs/contracts.md`
- `docs/event-model.md`
- `docs/implementation-status.md`
- `docs/services.md`
- `docs/architecture.md`
- `docs/roadmap.md`
- `docs/README.md`

- [x] Add `asr.partial` JSON schema.
- [x] Add `AsrPartialPayload` / `AsrPartialEvent` protobuf messages.
- [x] Update docs to move `asr.partial` from planned to implemented.
- [x] Run `tools/verify.sh`.

## Verification

- [x] `tools/verify.sh`
