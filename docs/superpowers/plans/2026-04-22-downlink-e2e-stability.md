# Downlink E2E Stability Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete roadmap priority #1 by hardening downlink behaviors and adding repository-level end-to-end verification for `subtitle.partial` / `subtitle.final` / `session.closed`.

**Source of truth:**
- `docs/roadmap.md`
- `docs/contracts.md`
- `docs/implementation-status.md`
- `services/speech-gateway/*`
- `services/asr-worker/*`
- `services/translation-worker/*`
- `services/session-orchestrator/*`

---

### Task 1: Build deterministic downlink E2E test harness

**Files:**
- `services/speech-gateway/src/test/java/**`
- `services/asr-worker/src/test/java/**`
- `services/translation-worker/src/test/java/**`
- `services/session-orchestrator/src/test/java/**`

- [x] Add an integration-style test path that simulates upstream events and asserts WebSocket downlink ordering.
- [x] Verify event mapping contract for `asr.partial -> subtitle.partial`, `translation.result -> subtitle.final`, `session.control(CLOSED) -> session.closed`.
- [x] Keep the harness deterministic and CI-safe (no external infra dependency).

### Task 2: Strengthen downlink stability guarantees

**Files:**
- `services/speech-gateway/src/main/java/com/kafkaasr/gateway/ws/**`
- `services/speech-gateway/src/test/java/**`

- [x] Add/verify ordering and terminal semantics after `session.closed`.
- [x] Add explicit tests for duplicate and late-message handling in downlink path.
- [x] Add telemetry assertions for downlink success/error/duplicate counters.

### Task 3: Add operator-facing E2E demo/verification entrypoint

**Files:**
- `tools/*`
- `docs/automation.md`
- `docs/README.md`

- [x] Add a runnable script or documented command flow for local downlink E2E smoke verification.
- [x] Ensure this flow validates user-visible behavior, not just unit-level internals.
- [x] Keep the script idempotent and aligned with current dev workflow.

### Task 4: Sync docs and verify

**Files:**
- `docs/contracts.md`
- `docs/implementation-status.md`
- `docs/services.md`
- `docs/roadmap.md`

- [x] Update docs to reflect concrete E2E evidence and remaining gaps.
- [x] Run full repository verification.

## Verification

- [x] `tools/verify.sh`
