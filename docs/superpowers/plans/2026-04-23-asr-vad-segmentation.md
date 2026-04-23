# ASR VAD Segmentation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add configurable VAD-based segment finalization in `asr-worker` so long-running streams can emit `asr.final` on silence boundaries without waiting for `endOfStream`.

**Source of truth:**
- `docs/contracts.md`
- `docs/services.md`
- `docs/implementation-status.md`
- `services/asr-worker/*`

---

### Task 1: VAD segmentation implementation

**Files:**
- `services/asr-worker/src/main/java/com/kafkaasr/asr/pipeline/AsrInferenceProperties.java`
- `services/asr-worker/src/main/java/com/kafkaasr/asr/pipeline/AsrPipelineService.java`
- `services/asr-worker/src/main/java/com/kafkaasr/asr/pipeline/AsrVadSegmenter.java`
- `services/asr-worker/src/main/resources/application.yml`

- [x] Add configurable VAD parameters under `asr.inference.vad`.
- [x] Implement session-aware silence-frame based segmentation decision.
- [x] Integrate VAD decision into partial/final emission path while preserving stable/end-of-stream semantics.

### Task 2: Tests

**Files:**
- `services/asr-worker/src/test/java/com/kafkaasr/asr/pipeline/AsrPipelineServiceTests.java`

- [x] Add tests for VAD-triggered final emission.
- [x] Add tests for VAD state reset behavior across segments.

### Task 3: Docs and verification

**Files:**
- `services/asr-worker/README.md`
- `docs/contracts.md`
- `docs/services.md`
- `docs/implementation-status.md`
- `docs/roadmap.md`
- `docs/superpowers/plans/2026-04-23-asr-vad-segmentation.md`

- [x] Update docs to reflect VAD-based ASR finalization semantics and status.
- [x] Run `./gradlew :services:asr-worker:test`.
- [x] Run `bash tools/verify.sh`.
