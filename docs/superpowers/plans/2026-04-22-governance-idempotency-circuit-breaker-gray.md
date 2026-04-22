# Governance: Idempotency + Compensation + Circuit Breaker + Gray Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the next roadmap governance slice by adding practical idempotency consumption guards, baseline compensation signaling, and control-plane circuit-breaker + gray fallback controls.

**Source of truth:**
- `docs/roadmap.md`
- `docs/contracts.md`
- `docs/event-model.md`
- `docs/implementation-status.md`
- `docs/services.md`
- `docs/architecture.md`
- `services/session-orchestrator/*`
- `services/control-plane/*`
- `services/asr-worker/*`
- `services/translation-worker/*`
- `services/tts-orchestrator/*`
- `services/speech-gateway/*`

---

### Task 1: Add tenant governance fields for gray/circuit strategy

**Files:**
- `services/control-plane/src/main/java/com/kafkaasr/control/api/TenantPolicyUpsertRequest.java`
- `services/control-plane/src/main/java/com/kafkaasr/control/api/TenantPolicyResponse.java`
- `services/control-plane/src/main/java/com/kafkaasr/control/policy/TenantPolicyState.java`
- `services/control-plane/src/main/java/com/kafkaasr/control/service/TenantPolicyService.java`
- `services/session-orchestrator/src/main/java/com/kafkaasr/orchestrator/policy/ControlPlaneTenantPolicyResponse.java`
- `services/session-orchestrator/src/main/java/com/kafkaasr/orchestrator/policy/TenantPolicy.java`
- `services/control-plane/src/test/java/**`
- `services/session-orchestrator/src/test/java/**`

- [x] Add gray governance fields (for example canary enable + percentage) to tenant policy API/state models.
- [x] Add policy-level control-plane fallback policy fields (for example fail-open flag and cache TTL hints).
- [x] Keep backward compatibility for existing policy payloads and update tests.

### Task 2: Add session-orchestrator circuit breaker + cached fallback policy path

**Files:**
- `services/session-orchestrator/src/main/java/com/kafkaasr/orchestrator/policy/ControlPlaneClientProperties.java`
- `services/session-orchestrator/src/main/java/com/kafkaasr/orchestrator/policy/ControlPlaneTenantPolicyClient.java`
- `services/session-orchestrator/src/main/java/com/kafkaasr/orchestrator/service/SessionLifecycleService.java`
- `services/session-orchestrator/src/main/resources/application.yml`
- `services/session-orchestrator/src/test/java/**`

- [x] Add simple circuit breaker state (closed/open/half-open) around control-plane fetch.
- [x] Cache last-known-good tenant policy per tenant and support configurable fail-open/fail-closed behavior.
- [x] Expose metrics for breaker open transitions, fallback hit rate, and policy fetch failures.
- [x] Keep `session.start` behavior deterministic and contract-safe under control-plane outage.

### Task 3: Add consumer idempotency guard + compensation signal baseline

**Files:**
- `services/asr-worker/src/main/java/com/kafkaasr/asr/kafka/AudioIngressConsumer.java`
- `services/translation-worker/src/main/java/com/kafkaasr/translation/kafka/AsrFinalConsumer.java`
- `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/kafka/TranslationResultConsumer.java`
- `services/speech-gateway/src/main/java/com/kafkaasr/gateway/ws/downlink/*.java`
- `services/*/src/main/resources/application.yml`
- `services/*/src/test/java/**`

- [x] Add idempotency guard based on event `idempotencyKey` with bounded TTL storage.
- [x] Drop duplicated events as success-path no-op and publish metrics tags (`result=duplicate`).
- [x] Add baseline compensation signal for repeated processing failures routed to a dedicated ops topic or DLQ extension.
- [x] Add/refresh tests that cover duplicate replay and compensation emission.

### Task 4: Sync docs and verify

**Files:**
- `docs/contracts.md`
- `docs/event-model.md`
- `docs/implementation-status.md`
- `docs/services.md`
- `docs/architecture.md`
- `docs/roadmap.md`
- `docs/README.md`

- [x] Update docs for newly added governance fields and runtime behavior.
- [x] Mark completed roadmap slice and keep remaining gaps explicit.
- [x] Run full verification and fix regressions.

## Verification

- [x] `tools/verify.sh`
