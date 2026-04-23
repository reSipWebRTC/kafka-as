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

- `build/reports/loadtest/gateway-pipeline-loadtest-aggregate.json`
- `build/reports/loadtest/gateway-pipeline-loadtest-summary.md`
- `build/reports/loadtest/gateway-pipeline-loadtest-<scenario>.json`
- `build/reports/loadtest/gateway-pipeline-loadtest-<scenario>.log`
- `build/reports/loadtest/gateway-pipeline-loadtest.json` (legacy compatibility path)

Scenario defaults:

- `smoke`
- `baseline`
- `stress`

Scenario-specific overrides:

- `LOADTEST_<SCENARIO>_SESSIONS`
- `LOADTEST_<SCENARIO>_FRAMES_PER_SESSION`
- `LOADTEST_<SCENARIO>_MIN_SUCCESS_RATIO`
- `LOADTEST_<SCENARIO>_MAX_P95_LATENCY_MS`

### Run fault-drill closure baseline

Run from repository root or from a feature worktree:

```bash
tools/fault-drill-closure.sh
```

Outputs:

- `build/reports/fault-drill/fault-drill-closure.json`
- `build/reports/fault-drill/fault-drill-closure-summary.md`
- `build/reports/fault-drill/fault-drill-<scenario>.log`

Scenario filter:

- `FAULT_DRILL_SCENARIOS="asr-engine-fault-mapping translation-engine-fault-mapping tts-engine-fault-mapping"`

### Run preprod drill closure

Run from repository root or from a feature worktree:

```bash
PREPROD_TARGET=preprod-cn-a \
PREPROD_ALERTMANAGER_URL="https://alertmanager.preprod.example.com" \
PREPROD_LOADTEST_COMMAND="bash scripts/preprod/loadtest.sh" \
PREPROD_FAULT_DRILL_COMMAND="bash scripts/preprod/fault-drill.sh" \
tools/preprod-drill-closure.sh
```

Outputs:

- `build/reports/preprod-drill/preprod-drill-closure.json`
- `build/reports/preprod-drill/preprod-drill-closure-summary.md`
- `build/reports/preprod-drill/preprod-loadtest.log`
- `build/reports/preprod-drill/preprod-fault-drill.log`
- `build/reports/preprod-drill/alertmanager-*.json`

Useful flags:

- `PREPROD_WATCH_ALERTS="GatewayWsErrorRateHigh,PipelineErrorRateHigh,KafkaConsumerLagHigh"`
- `PREPROD_RECOVERY_TIMEOUT_SECONDS=900`
- `PREPROD_RECOVERY_POLL_SECONDS=30`
- `PREPROD_SKIP_ALERT_CAPTURE=1` (local integration run without Alertmanager dependency)
- `PREPROD_DRY_RUN=1` (script flow validation only)

Related docs:

- `docs/reports/loadtest/2026-04-22-baseline.md`
- `docs/reports/loadtest/2026-04-23-closure.md`
- `docs/reports/loadtest/2026-04-23-preprod-closure.md`
- `docs/runbooks/loadtest-alert-closure.md`

### Run control-plane IAM precheck

Run from repository root:

```bash
tools/control-plane-iam-precheck.sh --env-file .secrets/control-plane-iam.env
```

Outputs:

- `build/reports/control-plane-iam-precheck/control-plane-iam-precheck.json`
- `build/reports/control-plane-iam-precheck/control-plane-iam-precheck-summary.md`

Useful flags:

- `--skip-network` (validate config only)
- `--allow-placeholder-values` (template smoke check)
- `--print-env` (write resolved env snapshot under report dir)

Related docs:

- `deploy/env/control-plane-iam.env.template`
- `docs/runbooks/control-plane-iam-provider-integration.md`

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
