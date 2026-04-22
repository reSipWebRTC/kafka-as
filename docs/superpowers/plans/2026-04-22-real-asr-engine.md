# Real ASR Engine Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a real-engine-ready ASR provider path (FunASR mode) in `asr-worker` while preserving existing `placeholder/http` fallback behavior.

**Source of truth:**
- `docs/roadmap.md`
- `docs/implementation-status.md`
- `docs/services.md`
- `services/asr-worker/*`

---

### Task 1: FunASR mode configuration and engine

**Files:**
- `services/asr-worker/src/main/java/com/kafkaasr/asr/pipeline/**`
- `services/asr-worker/src/main/resources/application.yml`

- [x] Extend `asr.inference` properties with `funasr` provider config.
- [x] Add `FunasrAsrInferenceEngine` behind `asr.inference.mode=funasr`.
- [x] Keep `placeholder/http` modes intact and backward-compatible.

### Task 2: Tests

**Files:**
- `services/asr-worker/src/test/java/com/kafkaasr/asr/pipeline/**`

- [x] Add tests for FunASR request mapping and auth header behavior.
- [x] Add tests for FunASR response parsing and fallback defaults.

### Task 3: Docs and verification

**Files:**
- `docs/implementation-status.md`
- `docs/services.md`
- `docs/roadmap.md`
- `docs/README.md`

- [x] Update docs to reflect ASR real-engine adapter availability.
- [x] Run `tools/verify.sh`.

## Verification

- [x] `./gradlew :services:asr-worker:test`
- [x] `tools/verify.sh`
