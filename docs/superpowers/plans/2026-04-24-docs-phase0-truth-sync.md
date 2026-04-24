# Docs Phase 0 Truth Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Align the main architecture/status roadmap docs with the current repository truth so HTML source materials remain design input instead of competing facts.

**Architecture:** This change is documentation-only. Update the high-signal docs that describe current implementation shape and forward plan, using `implementation-status.md`, `contracts.md`, `api/`, and tested service behavior as the source of truth. Keep future capabilities explicitly marked as planned.

**Tech Stack:** Markdown docs, existing contracts under `api/`, current Spring Boot service behavior.

---

### Task 1: Refresh Architecture Snapshot

**Files:**
- Modify: `docs/architecture.md`
- Reference: `docs/implementation-status.md`
- Reference: `docs/contracts.md`

- [x] Update the "当前仓库实现基线" section to reflect the current 2026-04-24 implementation baseline.
- [x] Replace stale statements that still describe `tts-orchestrator` / `control-plane` as purely skeletal.
- [x] Update component summaries so `speech-gateway`, `translation-worker`, `tts-orchestrator`, and `control-plane` match current implemented capabilities and remaining gaps.

### Task 2: Refresh Service Boundary Truth

**Files:**
- Modify: `docs/services.md`
- Reference: `docs/implementation-status.md`

- [x] Update the top-level service table to include current auth, provider, storage/CDN, and rollback orchestration capabilities.
- [x] Fix the stale `control-plane` rollback description so it reflects optional `targetVersion` / `distributionRegions`.
- [x] Update the dependency graph note so object storage / CDN is described as an implemented optional path instead of future-only.

### Task 3: Refresh Observability Baseline

**Files:**
- Modify: `docs/observability.md`
- Reference: `docs/reports/loadtest/`
- Reference: `docs/automation.md`

- [x] Update the baseline date and monitoring asset summary to include Alertmanager routing and operational validation.
- [x] Expand the closure section to cover loadtest aggregate, fault-drill, and preprod closure entrypoints.
- [x] Replace stale "当前仍缺失" items so the remaining gaps focus on real-environment evidence, client-visible metrics, and JSON logging rather than already-landed drill baselines.

### Task 4: Reframe the Roadmap

**Files:**
- Modify: `docs/roadmap.md`
- Reference: `docs/html/README.md`
- Reference: `docs/implementation-status.md`

- [x] Rewrite the roadmap so it starts from today's repository truth instead of the original skeleton phase model.
- [x] Reframe phases around the next real gaps: doc truth sync, real engine closure, user-visible E2E closure, control-plane platformization, event governance, and elastic distribution.
- [x] Update priority and risk sections to match the new phase structure.

### Task 5: Verify

**Files:**
- Verify: `docs/architecture.md`
- Verify: `docs/services.md`
- Verify: `docs/observability.md`
- Verify: `docs/roadmap.md`

- [x] Run `tools/verify.sh`
- [x] Review final diffs for any remaining stale phrases such as "未实现 auth" or "当前无请求体且仅回滚上一版本"
