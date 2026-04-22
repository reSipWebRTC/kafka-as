# Observability Alerts Baseline Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the roadmap priority "Kafka lag / latency / error-rate dashboards and alerts" as repository-managed baseline assets.

**Source of truth:**
- `docs/roadmap.md`
- `docs/observability.md`
- `docs/implementation-status.md`
- `deploy/`
- `tools/`

---

### Task 1: Add monitoring stack assets under deploy

**Files:**
- `deploy/monitoring/**`

- [x] Add Prometheus baseline config with scrape jobs for all services.
- [x] Add alert rules for error-rate, latency and Kafka lag signals.
- [x] Add a Grafana dashboard JSON focused on pipeline + downlink health.

### Task 2: Add operator entrypoint scripts

**Files:**
- `tools/**`

- [x] Add local helper scripts to start/stop monitoring stack.
- [x] Keep scripts idempotent and aligned with existing tooling style.

### Task 3: Sync docs and verify

**Files:**
- `docs/automation.md`
- `docs/observability.md`
- `docs/implementation-status.md`

- [x] Document how to run and access dashboards/alerts locally.
- [x] Update status docs to reflect monitoring asset baseline.
- [x] Run `tools/verify.sh`.

## Verification

- [x] `tools/verify.sh`
