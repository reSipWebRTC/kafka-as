# ASR Partial Hardening Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden `asr.partial` behavior so non-stable hypotheses emit only `asr.partial` and stable/end-of-stream hypotheses emit only `asr.final`, reducing duplicate downstream work and aligning event semantics.

**Source of truth:**
- `docs/contracts.md`
- `docs/event-model.md`
- `docs/services.md`
- `services/asr-worker/*`

---

### Task 1: Pipeline semantics

**Files:**
- `services/asr-worker/src/main/java/com/kafkaasr/asr/pipeline/AsrPipelineService.java`
- `services/asr-worker/src/main/java/com/kafkaasr/asr/kafka/AudioIngressConsumer.java`

- [x] Emit `asr.partial` only when result is non-stable.
- [x] Emit `asr.final` only when result is stable or ingress frame is `endOfStream=true`.
- [x] Keep publish order when both events exist (`partial` then `final`) and allow single-event publish path.

### Task 2: Tests

**Files:**
- `services/asr-worker/src/test/java/com/kafkaasr/asr/pipeline/AsrPipelineServiceTests.java`
- `services/asr-worker/src/test/java/com/kafkaasr/asr/kafka/AudioIngressConsumerTests.java`

- [x] Update pipeline tests for partial-only and final-only routing.
- [x] Add consumer tests for optional partial/final publish behavior.

### Task 3: Docs and verification

**Files:**
- `docs/contracts.md`
- `docs/services.md`
- `docs/implementation-status.md`

- [x] Clarify `asr.partial`/`asr.final` emission semantics.
- [x] Run `tools/verify.sh`.

## Verification

- [x] `./gradlew :services:asr-worker:test`
- [x] `tools/verify.sh`
