# Reliability Controls Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the next roadmap reliability slice: Kafka consumer retry + DLQ handling, plus gateway-side rate limiting and backpressure controls on high-frequency audio ingress.

**Source of truth:**
- `docs/roadmap.md`
- `docs/contracts.md`
- `docs/implementation-status.md`
- `docs/services.md`
- `docs/architecture.md`
- `services/speech-gateway/*`
- `services/asr-worker/*`
- `services/translation-worker/*`
- `services/tts-orchestrator/*`

---

### Task 1: Add gateway rate limiting and backpressure controls

**Files:**
- `services/speech-gateway/src/main/java/com/kafkaasr/gateway/flow/GatewayFlowControlProperties.java`
- `services/speech-gateway/src/main/java/com/kafkaasr/gateway/flow/GatewayAudioFrameFlowController.java`
- `services/speech-gateway/src/main/java/com/kafkaasr/gateway/ws/protocol/GatewayMessageRouter.java`
- `services/speech-gateway/src/main/java/com/kafkaasr/gateway/SpeechGatewayApplication.java`
- `services/speech-gateway/src/main/resources/application.yml`
- `services/speech-gateway/src/test/java/com/kafkaasr/gateway/ws/protocol/GatewayMessageRouterTests.java`
- `services/speech-gateway/src/test/java/com/kafkaasr/gateway/flow/GatewayAudioFrameFlowControllerTests.java`

- [x] Add configurable per-session frame rate and max in-flight controls.
- [x] Return contract-aligned error codes: `RATE_LIMITED` and `BACKPRESSURE_DROP`.
- [x] Ensure in-flight counters are released in all success/error paths.
- [x] Add tests for rate-limit and backpressure behaviors.

### Task 2: Add Kafka retry + DLQ policies for consumer services

**Files:**
- `services/asr-worker/src/main/java/com/kafkaasr/asr/events/AsrKafkaProperties.java`
- `services/asr-worker/src/main/java/com/kafkaasr/asr/kafka/AsrKafkaConsumerConfig.java`
- `services/asr-worker/src/main/resources/application.yml`
- `services/translation-worker/src/main/java/com/kafkaasr/translation/events/TranslationKafkaProperties.java`
- `services/translation-worker/src/main/java/com/kafkaasr/translation/kafka/TranslationKafkaConsumerConfig.java`
- `services/translation-worker/src/main/resources/application.yml`
- `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/events/TtsKafkaProperties.java`
- `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/kafka/TtsKafkaConsumerConfig.java`
- `services/tts-orchestrator/src/main/resources/application.yml`
- `services/speech-gateway/src/main/java/com/kafkaasr/gateway/ws/downlink/GatewayDownlinkProperties.java`
- `services/speech-gateway/src/main/java/com/kafkaasr/gateway/ws/downlink/GatewayDownlinkKafkaConsumerConfig.java`
- `services/speech-gateway/src/main/java/com/kafkaasr/gateway/ws/downlink/AsrPartialDownlinkConsumer.java`
- `services/speech-gateway/src/main/java/com/kafkaasr/gateway/ws/downlink/TranslationResultDownlinkConsumer.java`
- `services/speech-gateway/src/main/java/com/kafkaasr/gateway/ws/downlink/SessionControlDownlinkConsumer.java`
- `services/speech-gateway/src/main/resources/application.yml`

- [x] Add retry max attempts, backoff ms, and DLQ suffix settings in each consumer service.
- [x] Configure common error handlers with `DeadLetterPublishingRecoverer`.
- [x] Mark malformed payload exceptions (`IllegalArgumentException`) as non-retryable and direct-to-DLQ.
- [x] Keep existing Kafka listener topic/group bindings working with property refs.

### Task 3: Sync docs and verify

**Files:**
- `docs/implementation-status.md`
- `docs/services.md`
- `docs/architecture.md`
- `docs/roadmap.md`
- `docs/contracts.md`

- [x] Update status docs to mark retry/DLQ/rate-limit/backpressure as implemented baseline.
- [x] Keep unresolved gaps explicit (full compensating workflows, advanced adaptive control, etc.).
- [x] Run full repository verification.

## Verification

- [x] `tools/verify.sh`
