# ASR FunASR Production Drill Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the FunASR production-drill gap by adding provider availability probing, bounded concurrency, explicit error semantics, and inference-level observability in `asr-worker`.

**Source of truth:**
- `docs/contracts.md`
- `docs/services.md`
- `docs/implementation-status.md`
- `services/asr-worker/*`

---

### Task 1: FunASR runtime hardening

**Files:**
- `services/asr-worker/src/main/java/com/kafkaasr/asr/pipeline/AsrInferenceProperties.java`
- `services/asr-worker/src/main/java/com/kafkaasr/asr/pipeline/FunasrAsrInferenceEngine.java`
- `services/asr-worker/src/main/java/com/kafkaasr/asr/pipeline/AsrEngineException.java`
- `services/asr-worker/src/main/java/com/kafkaasr/asr/kafka/AudioIngressConsumer.java`
- `services/asr-worker/src/main/resources/application.yml`

- [x] Add FunASR production controls (max concurrency + health probe settings).
- [x] Implement health-probe precheck with short-lived cache and fail-open toggle.
- [x] Add bounded concurrency guard and in-flight/request metrics.
- [x] Map provider/network/timeout/business failures to explicit ASR error codes.
- [x] Propagate engine error code + retryable semantics into consumer retry/DLQ decision.

### Task 2: Tests

**Files:**
- `services/asr-worker/src/test/java/com/kafkaasr/asr/pipeline/FunasrAsrInferenceEngineTests.java`

- [x] Cover health-check fail-fast behavior.
- [x] Cover timeout to `ASR_TIMEOUT` mapping.
- [x] Cover concurrency-limit reject behavior.

### Task 3: Docs and verification

**Files:**
- `services/asr-worker/README.md`
- `docs/services.md`
- `docs/implementation-status.md`
- `docs/roadmap.md`
- `docs/contracts.md`
- `docs/superpowers/plans/2026-04-23-asr-funasr-prod-drill.md`

- [x] Document new FunASR drill capabilities and key config flags.
- [x] Run `./gradlew :services:asr-worker:test`.
- [x] Run `bash tools/verify.sh`.
