# Real TTS Engine V2 Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move `tts-orchestrator` HTTP synthesis integration from "adapter exists" to "production联调可用的工程化基线", with stronger response compatibility and explicit provider error semantics.

**Source of truth:**
- `docs/roadmap.md`
- `docs/implementation-status.md`
- `docs/services.md`
- `services/tts-orchestrator/*`

---

### Task 1: Harden synthesis response compatibility and provider errors

**Files:**
- `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/pipeline/HttpTtsSynthesisEngine.java`

- [x] Support additional synthesis response shapes and boolean-like stream fields.
- [x] Normalize provider error semantics (`error` payload / non-success status or code).
- [x] Surface synthesis failures to pipeline retry/DLQ path instead of silently passthrough.

### Task 2: Regression tests

**Files:**
- `services/tts-orchestrator/src/test/java/com/kafkaasr/tts/pipeline/HttpTtsSynthesisEngineTests.java`

- [x] Add tests for provider error payloads and failed status handling.
- [x] Add tests for additional success payload variants.
- [x] Keep existing tests green after behavior alignment.

### Task 3: Docs sync and verification

**Files:**
- `docs/implementation-status.md`
- `docs/services.md`

- [x] Reflect "TTS synthesis adapter v2 compatibility hardening" as completed baseline work.
- [x] Keep "TTS synthesis 生产联调" marked as not fully completed.

## Verification

- [x] `./gradlew :services:tts-orchestrator:test`
- [x] `tools/verify.sh`
