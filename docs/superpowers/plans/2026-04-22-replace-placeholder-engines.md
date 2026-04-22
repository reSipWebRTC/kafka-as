# Replace Placeholder Engines Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace placeholder-only inference/translation/voice selection paths with configurable HTTP adapters while preserving placeholder fallback as the default-safe runtime mode.

**Source of truth:**
- `docs/roadmap.md`
- `docs/services.md`
- `docs/implementation-status.md`
- `services/asr-worker/*`
- `services/translation-worker/*`
- `services/tts-orchestrator/*`

---

### Task 1: ASR inference adapter

**Files:**
- `services/asr-worker/src/main/java/**`
- `services/asr-worker/src/main/resources/application.yml`
- `services/asr-worker/src/test/java/**`

- [x] Add configurable `asr.inference` properties for mode selection and HTTP endpoint settings.
- [x] Add HTTP-based `AsrInferenceEngine` implementation and keep placeholder implementation as fallback default.
- [x] Add tests for HTTP engine request/response mapping and failure behavior.

### Task 2: Translation engine adapter

**Files:**
- `services/translation-worker/src/main/java/**`
- `services/translation-worker/src/main/resources/application.yml`
- `services/translation-worker/src/test/java/**`

- [x] Add configurable `translation.engine` properties for mode selection and HTTP endpoint settings.
- [x] Add HTTP-based `TranslationEngine` implementation and keep placeholder implementation as fallback default.
- [x] Add tests for HTTP engine request/response mapping and failure behavior.

### Task 3: TTS voice policy adapter

**Files:**
- `services/tts-orchestrator/src/main/java/**`
- `services/tts-orchestrator/src/main/resources/application.yml`
- `services/tts-orchestrator/src/test/java/**`

- [x] Add configurable `tts.voice-policy` properties for mode selection and HTTP endpoint settings.
- [x] Add HTTP-based `VoicePolicy` implementation and keep rule-based fallback default.
- [x] Add tests for HTTP voice selection mapping and fallback behavior.

### Task 4: Docs sync and verification

**Files:**
- `docs/services.md`
- `docs/implementation-status.md`

- [x] Update docs to reflect “HTTP adapter + fallback” state instead of placeholder-only wording.
- [x] Run targeted service tests for modified modules.
- [x] Run `tools/verify.sh`.

## Verification

- [x] `./gradlew :services:asr-worker:test :services:translation-worker:test :services:tts-orchestrator:test`
- [x] `tools/verify.sh`
