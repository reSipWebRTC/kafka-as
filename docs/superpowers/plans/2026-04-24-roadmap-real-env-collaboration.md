# Roadmap Real Environment Collaboration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add explicit real-environment collaboration guidance so the roadmap tells the user what must be provided and how repository scripts should be used when work moves from simulated/local baselines to real preprod or production-like environments.

**Architecture:** This is a docs-only change. Update the roadmap with a high-level collaboration section, add a runbook with concrete environment inputs and responsibility split, and link the new runbook from the docs index so future real-engine / real-IAM / preprod closure work can reuse one source of truth.

**Tech Stack:** Markdown docs, existing shell automation under `tools/`, current service environment variables under `services/*/src/main/resources/application.yml`.

---

### Task 1: Add Roadmap Collaboration Guidance

**Files:**
- Modify: `docs/roadmap.md`
- Reference: `docs/automation.md`
- Reference: `docs/runbooks/control-plane-iam-provider-integration.md`

- [x] Add a roadmap section that states when real environment cooperation becomes mandatory.
- [x] Document the split of responsibilities between the repository automation and the user-provided environment.
- [x] Link the detailed real-environment collaboration runbook from the roadmap.

### Task 2: Add Real Environment Collaboration Runbook

**Files:**
- Create: `docs/runbooks/real-environment-collaboration.md`
- Reference: `services/asr-worker/src/main/resources/application.yml`
- Reference: `services/translation-worker/src/main/resources/application.yml`
- Reference: `services/tts-orchestrator/src/main/resources/application.yml`
- Reference: `deploy/env/control-plane-iam.env.template`

- [x] Document the general cooperation model: secrets stay out of git, simulated baselines first, real environment only after local verification passes.
- [x] List what the user must provide for real ASR/translation/TTS, control-plane IAM, and preprod observability/alerting work.
- [x] List what the agent will do after those inputs are available: template env files, precheck/drill commands, closure execution, report interpretation, and rollback guidance.

### Task 3: Link and Verify

**Files:**
- Modify: `docs/README.md`
- Verify: `docs/roadmap.md`
- Verify: `docs/runbooks/real-environment-collaboration.md`

- [x] Add the new runbook to the docs index.
- [x] Run `tools/verify.sh`.
- [x] Review final diff for wording drift or overlap with existing IAM/loadtest runbooks.
