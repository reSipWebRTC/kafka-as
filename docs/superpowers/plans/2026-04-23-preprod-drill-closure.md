# Preprod Drill Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a preprod-oriented drill entrypoint that orchestrates loadtest, fault-drill, and alert recovery evidence capture into one repeatable closure report.

**Architecture:** Keep existing local harness scripts unchanged, and add a new preprod wrapper script that executes configurable preprod commands, snapshots Alertmanager state, polls recovery for watched alerts, and emits machine-readable JSON + Markdown summary artifacts. Update runbook/automation/status docs and add a versioned preprod report template.

**Tech Stack:** Bash, Python 3 stdlib (`json`, `pathlib`, `datetime`), existing Gradle and monitoring tooling.

---

### Task 1: Add preprod closure wrapper script

**Files:**
- Add: `tools/preprod-drill-closure.sh`

- [x] Add one-command preprod closure workflow with configurable loadtest/fault-drill command hooks.
- [x] Capture Alertmanager status/alerts snapshots before and after each phase.
- [x] Implement watched-alert recovery polling and aggregate pass/fail output.
- [x] Emit `build/reports/preprod-drill/preprod-drill-closure.json` and markdown summary.

### Task 2: Docs and report sync

**Files:**
- Modify: `docs/runbooks/loadtest-alert-closure.md`
- Modify: `docs/automation.md`
- Modify: `docs/implementation-status.md`
- Modify: `docs/roadmap.md`
- Add: `docs/reports/loadtest/2026-04-23-preprod-closure.md`

- [x] Document preprod execution variables, gates, and generated artifacts.
- [x] Clarify current status as “preprod入口已具备，待真实环境证据回填”.
- [x] Add versioned report template for preprod closure evidence.

### Task 3: Verification and closure

**Files:**
- Modify: `docs/superpowers/plans/2026-04-23-preprod-drill-closure.md`

- [x] Run shell lint (`bash -n`) on new/updated scripts.
- [x] Execute `tools/preprod-drill-closure.sh` in dry-run mode to validate flow and artifacts.
- [x] Run `tools/verify.sh`.
- [x] Update this plan checklist to reflect execution completion.
