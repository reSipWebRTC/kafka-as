# Real TTS Engine Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a real-engine-ready TTS synthesis provider path (`http` mode) in `tts-orchestrator` while preserving existing rule/placeholder behavior and current `tts.request` contract.

**Source of truth:**
- `docs/roadmap.md`
- `docs/implementation-status.md`
- `docs/services.md`
- `services/tts-orchestrator/*`

---

### Task 1: TTS synthesis mode configuration and engine

**Files:**
- `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/pipeline/**`
- `services/tts-orchestrator/src/main/resources/application.yml`
- `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/TtsOrchestratorApplication.java`

- [x] Add `tts.synthesis` properties with `mode` and `http` provider config.
- [x] Add `TtsSynthesisEngine` abstraction and HTTP implementation behind `tts.synthesis.mode=http`.
- [x] Keep existing pipeline output contract (`tts.request`) unchanged and backward-compatible.

### Task 2: Tests

**Files:**
- `services/tts-orchestrator/src/test/java/com/kafkaasr/tts/pipeline/**`

- [x] Add tests for HTTP synthesis request mapping and auth header behavior.
- [x] Add tests for HTTP synthesis response parsing and fallback/error behavior.
- [x] Update pipeline tests for synthesis-engine integration path.

### Task 3: Docs and verification

**Files:**
- `docs/implementation-status.md`
- `docs/services.md`
- `docs/roadmap.md`
- `docs/README.md`
- `services/tts-orchestrator/README.md`

- [x] Update docs to reflect TTS real-engine adapter availability.
- [x] Run `tools/verify.sh`.

## Verification

- [x] `./gradlew :services:tts-orchestrator:test`
- [x] `tools/verify.sh`
