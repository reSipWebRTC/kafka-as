# Loadtest & Fault Drill Closure Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the "压测与告警闭环证据不足" gap by upgrading loadtest closure to multi-scenario execution and adding a repeatable fault-drill closure entrypoint for ASR/Translation/TTS reliability paths.

**Source of truth:**
- `docs/roadmap.md`
- `docs/implementation-status.md`
- `docs/runbooks/loadtest-alert-closure.md`
- `docs/automation.md`

---

### Task 1: Multi-scenario loadtest closure tooling

**Files:**
- `tools/loadtest-alert-closure.sh`

- [x] Upgrade loadtest entrypoint from single scenario to deterministic `smoke/baseline/stress` tiers.
- [x] Generate per-scenario artifacts and one aggregate machine-readable report.
- [x] Preserve legacy report output path compatibility.

### Task 2: Fault-drill closure tooling

**Files:**
- `tools/fault-drill-closure.sh`

- [x] Add one-command fault-drill closure for ASR/Translation/TTS engine + consumer reliability scenarios.
- [x] Emit machine-readable JSON and concise Markdown summary for audit evidence.
- [x] Support scenario filtering through environment variables.

### Task 3: Docs and evidence sync

**Files:**
- `docs/runbooks/loadtest-alert-closure.md`
- `docs/automation.md`
- `docs/roadmap.md`
- `docs/implementation-status.md`
- `docs/reports/loadtest/2026-04-23-closure.md`

- [x] Document new tooling entrypoints, outputs, and pass/fail gates.
- [x] Sync roadmap/status wording to reflect repo-level closure baseline.
- [x] Add a versioned closure report template with execution evidence.

## Verification

- [x] `tools/loadtest-alert-closure.sh`
- [x] `tools/fault-drill-closure.sh`
- [x] `tools/verify.sh`
