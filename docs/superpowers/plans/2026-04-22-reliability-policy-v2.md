# Reliability Policy V2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Roll out tenant-policy-driven retry and DLQ controls, starting with control-plane contract extension and asr-worker runtime enforcement.

**Architecture:** Extend control-plane tenant policy schema with reliability fields and keep backward-compatible defaults. In asr-worker, add a tenant policy resolver (control-plane + cache + fallback) and switch runtime retry/DLQ behavior from static config to tenant policy values. Keep Kafka listener bindings unchanged and preserve existing compensation signaling.

**Tech Stack:** Spring Boot 3, Spring Kafka, Spring WebFlux/WebClient, Micrometer, JUnit 5, Mockito.

---

### Task 1: Extend tenant policy contract for reliability fields

**Files:**
- `services/control-plane/src/main/java/com/kafkaasr/control/api/TenantPolicyUpsertRequest.java`
- `services/control-plane/src/main/java/com/kafkaasr/control/api/TenantPolicyResponse.java`
- `services/control-plane/src/main/java/com/kafkaasr/control/policy/TenantPolicyState.java`
- `services/control-plane/src/main/java/com/kafkaasr/control/service/TenantPolicyService.java`
- `services/session-orchestrator/src/main/java/com/kafkaasr/orchestrator/policy/TenantPolicy.java`
- `services/session-orchestrator/src/main/java/com/kafkaasr/orchestrator/policy/ControlPlaneTenantPolicyResponse.java`

- [x] Add `retryMaxAttempts`, `retryBackoffMs`, `dlqTopicSuffix` to tenant policy request/response/state records.
- [x] Keep backward compatibility: if new fields absent in upsert request, use defaults (`3`, `200`, `.dlq`).
- [x] Propagate new fields to session-orchestrator policy DTO mapping to keep control-plane contract alignment.

### Task 2: Apply tenant reliability policy in asr-worker runtime

**Files:**
- `services/asr-worker/src/main/java/com/kafkaasr/asr/AsrWorkerApplication.java`
- `services/asr-worker/src/main/java/com/kafkaasr/asr/kafka/AudioIngressConsumer.java`
- `services/asr-worker/src/main/java/com/kafkaasr/asr/kafka/AsrKafkaConsumerConfig.java`
- `services/asr-worker/src/main/java/com/kafkaasr/asr/kafka/AsrCompensationPublisher.java`
- `services/asr-worker/src/main/java/com/kafkaasr/asr/events/AsrKafkaProperties.java`
- `services/asr-worker/src/main/resources/application.yml`
- `services/asr-worker/src/main/java/com/kafkaasr/asr/policy/*` (new)

- [x] Add asr control-plane policy client properties + resolver with cache and default fallback.
- [x] Resolve tenant reliability policy per message and drive retry attempts/backoff from policy values.
- [x] Carry tenant-specific DLQ suffix into Kafka DLT routing path (tenant-aware exception + error handler destination resolver).
- [x] Include resolved DLQ topic in compensation signal payload for ops tracing.

### Task 3: Tests, docs, verification

**Files:**
- `services/control-plane/src/test/java/com/kafkaasr/control/service/TenantPolicyServiceTests.java`
- `services/control-plane/src/test/java/com/kafkaasr/control/api/TenantPolicyControllerTests.java`
- `services/session-orchestrator/src/test/java/com/kafkaasr/orchestrator/policy/ControlPlaneTenantPolicyClientTests.java`
- `services/asr-worker/src/test/java/com/kafkaasr/asr/kafka/AudioIngressConsumerTests.java`
- `services/asr-worker/src/test/java/com/kafkaasr/asr/policy/*` (new)
- `docs/contracts.md`
- `docs/services.md`
- `docs/implementation-status.md`

- [x] Add/adjust tests for new policy fields and compatibility defaults.
- [x] Add asr-worker tests for tenant-driven retry threshold and DLQ suffix behavior.
- [x] Update docs to mark tenant-policy-driven retry/DLQ as asr-worker-first rollout.
- [x] Run targeted and full verification commands.

## Verification

- [x] `./gradlew :services/control-plane:test`
- [x] `./gradlew :services/asr-worker:test`
- [x] `tools/verify.sh`
