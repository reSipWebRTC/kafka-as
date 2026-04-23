# Real Translation Engine V2 Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move `translation-worker` OpenAI integration from "adapter exists" to "production联调可用的工程化基线", with stronger response compatibility and explicit provider error semantics.

**Source of truth:**
- `docs/roadmap.md`
- `docs/implementation-status.md`
- `docs/services.md`
- `services/translation-worker/*`

---

### Task 1: Harden OpenAI response compatibility and provider errors

**Files:**
- `services/translation-worker/src/main/java/com/kafkaasr/translation/pipeline/OpenaiTranslationEngine.java`

- [x] Support additional OpenAI output shapes (chat completion content arrays, responses `output` variants).
- [x] Normalize provider error semantics (`error` object / failed status should fail fast with clear reason).
- [x] Preserve backward compatibility for existing chat-completions shape.

### Task 2: Regression tests

**Files:**
- `services/translation-worker/src/test/java/com/kafkaasr/translation/pipeline/OpenaiTranslationEngineTests.java`

- [x] Add tests for provider error payload handling.
- [x] Add tests for additional success payload variants.
- [x] Keep existing tests green.

### Task 3: Docs sync and verification

**Files:**
- `docs/implementation-status.md`
- `docs/services.md`

- [x] Reflect "OpenAI adapter v2 compatibility hardening" as completed baseline work.
- [x] Keep "OpenAI 生产联调" marked as not fully completed.

## Verification

- [x] `./gradlew :services:translation-worker:test`
- [x] `tools/verify.sh`
