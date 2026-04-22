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

### Activate Superpowers for this repository

```bash
tools/activate-superpowers.sh
```

Behavior:

- checks that the home-local `superpowers` plugin already exists
- links it into `./plugins/superpowers`
- registers this repository as a Codex plugin marketplace
- lets future Codex sessions pick up both `AGENTS.md` and the plugin-driven workflow

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

### Run downlink E2E smoke verification

Run from repository root or from a feature worktree:

```bash
tools/downlink-e2e-smoke.sh
```

Checks:

- deterministic downlink integration harness (`DownlinkE2EStabilityTests`)
- user-visible downlink payload mapping (`subtitle.partial` / `subtitle.final` / `session.closed`)
- duplicate / malformed payload stability regressions in downlink consumers

### Run loadtest + alert closure baseline

Run from repository root or from a feature worktree:

```bash
tools/loadtest-alert-closure.sh
```

Outputs:

- `build/reports/loadtest/gateway-pipeline-loadtest.json`
- `build/reports/loadtest/gateway-pipeline-loadtest-summary.md`

Related docs:

- `docs/reports/loadtest/2026-04-22-baseline.md`
- `docs/runbooks/loadtest-alert-closure.md`

### Start local monitoring baseline

Run from repository root:

```bash
tools/monitoring-up.sh
```

Access:

- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000` (`admin/admin`)

Stop:

```bash
tools/monitoring-down.sh
```

Assets:

- `deploy/monitoring/prometheus/prometheus.yml`
- `deploy/monitoring/prometheus/alerts/kafka-asr-alerts.yml`
- `deploy/monitoring/grafana/dashboards/kafka-asr-overview.json`

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
# 0. Activate repo-local Superpowers wiring once per machine/session setup
tools/activate-superpowers.sh

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

## 5. Second Codex Session Template

Use a second Codex session for parallel work that does not modify the same files as the current feature branch.

Example target:

- current session: `feature/session-orchestrator`
- second session: `feature/gateway-session-control`

Bootstrap commands for the second session:

```bash
cd /home/david/work/kafka-asr
tools/new-feature.sh feature/gateway-session-control
cd .worktrees/feature-gateway-session-control
tools/activate-superpowers.sh
```

Suggested first prompt for the second session:

```text
Use superpowers workflow for this repository. Implement feature/gateway-session-control: wire speech-gateway to call session-orchestrator start/stop APIs, keep audio.frame path unchanged, add config + client + error mapping + tests, then run tools/verify.sh.
```

Finish flow for the second session:

```bash
tools/verify.sh
cd /home/david/work/kafka-asr
tools/finish-feature.sh feature/gateway-session-control
```

## 6. Boundaries

This automation is intentionally narrow:

- it does not replace design review
- it does not replace contract updates in `docs/` and `api/`
- it does not auto-resolve merge conflicts
- it does not publish artifacts or deploy services

Those steps stay explicit on purpose.
