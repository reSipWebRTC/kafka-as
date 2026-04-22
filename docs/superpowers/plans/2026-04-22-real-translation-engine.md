# Real Translation Engine Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a real-engine-ready translation provider path (`openai` mode) in `translation-worker` while preserving existing `placeholder/http` behavior.

**Source of truth:**
- `docs/roadmap.md`
- `docs/implementation-status.md`
- `docs/services.md`
- `services/translation-worker/*`

---

### Task 1: OpenAI mode configuration and engine

**Files:**
- `services/translation-worker/src/main/java/com/kafkaasr/translation/pipeline/**`
- `services/translation-worker/src/main/resources/application.yml`

- [x] Extend `translation.engine` properties with `openai` provider config.
- [x] Add `OpenaiTranslationEngine` behind `translation.engine.mode=openai`.
- [x] Keep `placeholder/http` modes intact and backward-compatible.

### Task 2: Tests

**Files:**
- `services/translation-worker/src/test/java/com/kafkaasr/translation/pipeline/**`

- [x] Add tests for OpenAI request mapping and auth header behavior.
- [x] Add tests for OpenAI response parsing and fallback/error behavior.

### Task 3: Docs and verification

**Files:**
- `docs/implementation-status.md`
- `docs/services.md`
- `docs/roadmap.md`
- `docs/README.md`
- `services/translation-worker/README.md`

- [x] Update docs to reflect translation real-engine adapter availability.
- [x] Run `tools/verify.sh`.

## Verification

- [x] `./gradlew :services:translation-worker:test`
- [x] `tools/verify.sh`
