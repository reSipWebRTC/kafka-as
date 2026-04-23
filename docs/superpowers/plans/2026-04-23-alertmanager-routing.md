# Alertmanager Routing and Ops Closure Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the observability loop by adding in-repo alert notification routing and actionable on-call handoff guidance.

**Architecture:** Keep existing Prometheus rules unchanged, add Alertmanager as delivery layer, and encode notification/escalation wiring in monitoring assets + runbook.

**Tech Stack:** Docker Compose, Prometheus, Alertmanager, Markdown docs.

---

### Task 1: Alertmanager integration in monitoring stack

**Files:**
- Add: `deploy/monitoring/alertmanager/alertmanager.yml`
- Modify: `deploy/monitoring/docker-compose.yml`
- Modify: `deploy/monitoring/prometheus/prometheus.yml`

- [x] Add baseline Alertmanager routing tree and receivers (warning/critical/default).
- [x] Add Alertmanager service into local monitoring compose stack.
- [x] Wire Prometheus alerting target to Alertmanager.

### Task 2: Documentation and runbook closure

**Files:**
- Modify: `deploy/monitoring/README.md`
- Modify: `docs/runbooks/loadtest-alert-closure.md`
- Modify: `docs/implementation-status.md`
- Modify: `docs/roadmap.md`

- [x] Document local notification routing setup and env-driven webhook override.
- [x] Add concrete on-call escalation path and alert-routing checklist.
- [x] Sync implementation and roadmap docs with the new baseline.

### Task 3: Verification and plan update

**Files:**
- Modify: `docs/superpowers/plans/2026-04-23-alertmanager-routing.md`

- [x] Run `docker compose -f deploy/monitoring/docker-compose.yml config` (blocked in this environment because `docker` is unavailable; YAML syntax fallback was validated with `python3 + PyYAML`).
- [x] Run `bash tools/verify.sh`.
- [x] Mark plan tasks done with verification notes.
