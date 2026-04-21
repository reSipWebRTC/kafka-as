# Automation

## 1. Goal

This repository automates the repetitive parts of feature development around a single default path:

1. create a worktree-backed branch
2. implement in isolation
3. verify locally with one shared script
4. let CI run the same verification
5. merge and clean up

The goal is not unattended coding on `main`. The goal is deterministic execution around `docs/` and `api/` as the source of truth.

## 2. Local Entry Points

### Create a feature worktree

```bash
tools/new-feature.sh feature/session-orchestrator
```

Behavior:

- creates branch `feature/session-orchestrator` from `main` by default
- creates worktree `.worktrees/feature-session-orchestrator`
- prepares `docs/superpowers/plans/` inside the new worktree

### Verify the current tree

Run from the repository root or from a feature worktree:

```bash
tools/verify.sh
```

Checks:

- `git diff --check`
- JSON contract files under `api/json-schema/`
- required contract files exist
- `./gradlew test --no-daemon`

### Finish a feature branch

```bash
tools/finish-feature.sh feature/session-orchestrator
```

Behavior:

- verifies the feature worktree is clean
- verifies the `main` worktree is clean
- runs `tools/verify.sh` in the feature worktree
- merges the branch into `main`
- removes the feature worktree
- deletes the merged local branch

Useful flags:

- `--skip-verify`
- `--keep-worktree`

## 3. CI

GitHub Actions runs the same verification entry point:

- workflow file: `.github/workflows/ci.yml`
- Java: `21`
- execution command: `tools/verify.sh`

That keeps local verification and CI aligned. If `tools/verify.sh` changes, CI changes with it automatically.

## 4. Recommended Daily Flow

```bash
# 1. Create isolated branch/worktree
tools/new-feature.sh feature/asr-pipeline

# 2. Move into the new tree
cd .worktrees/feature-asr-pipeline

# 3. Update docs/api first if contracts change

# 4. Implement and verify
./tools/verify.sh

# 5. Merge and clean up from a non-feature path
cd /home/david/work/kafka-asr
tools/finish-feature.sh feature/asr-pipeline
```

## 5. Boundaries

This automation is intentionally narrow:

- it does not replace design review
- it does not replace contract updates in `docs/` and `api/`
- it does not auto-resolve merge conflicts
- it does not publish artifacts or deploy services

Those steps stay explicit on purpose.
