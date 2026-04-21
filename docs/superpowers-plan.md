# Superpowers Plan

## 1. Purpose

This document fixes the actual `superpowers` workflow for this repository.

The goal is to avoid ambiguity between:

- product/design refinement
- technical contract freezing
- worktree isolation
- implementation execution
- review and branch cleanup

## 2. What Superpowers Is Responsible For

In this repository, `superpowers` is the execution workflow layer.

It is responsible for:

- refining feature scope when requirements are still fuzzy
- creating isolated worktrees
- writing implementation plans
- executing plan tasks
- requesting review between tasks or batches
- finishing and cleaning up feature branches

It is not the technical source of truth.

## 3. Technical Source of Truth

The authoritative technical spec lives here:

- `docs/architecture.md`
- `docs/contracts.md`
- `docs/event-model.md`
- `docs/services.md`
- `docs/observability.md`
- `docs/roadmap.md`
- `api/protobuf/realtime_speech.proto`
- `api/json-schema/*.json`

If `openspec` is added later, use it for feature proposal or requirements drafting only. Do not let it replace `docs/contracts.md` or `api/`.

## 4. Default Workflow

### Step 1: Confirm whether the change needs design work

Use `superpowers` brainstorming only when:

- the feature is new
- the boundaries are unclear
- there are multiple valid architecture choices

Skip brainstorming when:

- the task is already specified in `docs/`
- the task is a narrow implementation step inside an approved design

### Step 2: Freeze contract changes before code

Before implementation starts, update the relevant source-of-truth files if the feature changes:

- event envelope
- WebSocket protocol
- JSON schema
- protobuf
- service boundaries

This repository is spec-first. Code should not lead contract changes.

### Step 3: Create an isolated worktree

Use `superpowers` `using-git-worktrees` for every feature branch.

Project-local worktrees live under:

- `.worktrees/`

Examples:

- `.worktrees/feature-gateway`
- `.worktrees/feature-session-orchestrator`
- `.worktrees/feature-asr-pipeline`

The matching branch names should be:

- `feature/gateway`
- `feature/session-orchestrator`
- `feature/asr-pipeline`
- `feature/translation-worker`
- `feature/tts-orchestrator`

### Step 4: Write the implementation plan inside the worktree

Use `superpowers` `writing-plans`.

Save plans under:

- `docs/superpowers/plans/YYYY-MM-DD-<feature-name>.md`

Each plan must reference exact files in this repository. Plans should follow the already approved architecture and contracts rather than inventing a second design.

### Step 5: Execute in one of two modes

Recommended default:

- `subagent-driven-development`

Alternative:

- `executing-plans`

Choose `subagent-driven-development` when:

- the work is multi-step
- the repository already has enough structure for parallel or isolated tasks
- review between tasks matters

Choose `executing-plans` when:

- the task is short
- the file set is small
- subagent overhead is not worth it

### Step 6: Review between tasks

Use `requesting-code-review`:

- after each meaningful task
- after each execution batch
- before merge

In this repository, review should always check against:

- the current plan document
- `docs/contracts.md`
- `docs/services.md`
- `docs/architecture.md`

### Step 7: Finish the development branch

Use `finishing-a-development-branch` after:

- implementation is complete
- docs and contracts are synced
- verification is done or explicitly blocked by environment limits

Preferred order:

1. Verify changes in the worktree
2. Merge branch back to `main`
3. Remove the worktree
4. Delete the merged feature branch

## 5. Repository-Specific Rules

### Rule 1: No direct feature work on `main`

All non-trivial implementation work must happen in a worktree-backed branch.

### Rule 2: Contracts before code

If behavior changes externally, update `docs/contracts.md` and `api/` before or together with code.

### Rule 3: One worktree, one concern

Do not mix unrelated features in the same worktree.

### Rule 4: HTML files are reference material only

The HTML articles are inputs and historical context. Active engineering decisions belong in `docs/` and `api/`.

### Rule 5: Environment constraints must be called out

If Java, Gradle, Kafka, Docker, or other runtime dependencies are missing, the workflow should still proceed, but the limitation must be explicitly recorded in the result.

## 6. Feature Workflow for This Repository

For the current roadmap, use this sequence:

1. `feature/gateway`
2. `feature/session-orchestrator`
3. `feature/asr-pipeline`
4. `feature/translation-worker`
5. `feature/observability-baseline`
6. `feature/tts-orchestrator`
7. `feature/control-plane`

Each feature should:

1. start from `main`
2. get its own worktree
3. update docs/contracts if needed
4. ship as a focused branch

## 7. Recommended Prompt Pattern

When starting a feature, use this shape:

```text
Use superpowers workflow for this repository.
Create a worktree for feature/gateway.
Treat docs/ and api/ as the technical source of truth.
If contracts change, update docs/contracts.md and api/ first.
Then write the implementation plan and execute it.
```

## 8. Current Status

The repository is already prepared for this workflow:

- git repository initialized
- `main` branch created
- `.worktrees/` reserved and ignored
- `feature/gateway` worktree already created

