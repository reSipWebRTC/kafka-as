# Real ASR Engine V2 Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move `asr-worker` FunASR integration from "adapter exists" to "production联调可用的工程化基线", with stronger response compatibility, explicit provider error mapping, and runnable verification.

**Source of truth:**
- `docs/roadmap.md`
- `docs/implementation-status.md`
- `docs/services.md`
- `services/asr-worker/*`

---

### Task 1: Harden FunASR response compatibility and error semantics

**Files:**
- `services/asr-worker/src/main/java/com/kafkaasr/asr/pipeline/FunasrAsrInferenceEngine.java`

- [x] Support more FunASR response shapes (`sentences`, nested arrays, string-number fields).
- [x] Normalize provider success/failure semantics (non-success code/status should fail fast with explicit reason).
- [x] Support boolean-like `is_final` values (`true/false`, `1/0`, `"true"/"false"`).

### Task 2: Add regression tests for real-engine edge cases

**Files:**
- `services/asr-worker/src/test/java/com/kafkaasr/asr/pipeline/FunasrAsrInferenceEngineTests.java`

- [x] Add tests for provider business-error responses (non-success code/status).
- [x] Add tests for alternate success payloads (`sentences`, string confidence, numeric final markers).
- [x] Keep existing tests green and backward compatible.

### Task 3: Docs sync and verification

**Files:**
- `docs/implementation-status.md`
- `docs/services.md`

- [x] Reflect "FunASR adapter v2 compatibility hardening" as completed baseline work.
- [x] Keep "FunASR 生产联调" marked as not fully completed.

## Verification

- [x] `./gradlew :services:asr-worker:test`
- [x] `tools/verify.sh`
