# Loadtest Alert Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the missing "load testing + alert calibration" closure by adding a repeatable loadtest entrypoint, generating baseline evidence, and calibrating Prometheus alert thresholds with an operational runbook.

**Architecture:** Add a deterministic in-repo loadtest harness for the gateway ingress/downlink chain (long-lived sessions + frame burst + subtitle downlink) and wrap it with a tool script that emits machine-readable report JSON. Use the baseline report to calibrate alert thresholds in `deploy/monitoring/prometheus/alerts/kafka-asr-alerts.yml`, then document repeatable execution and on-call actions.

**Tech Stack:** Spring Boot test harness, JUnit 5, Micrometer, Bash, Python 3 stdlib, Prometheus alert rules.

---

### Task 1: Add repeatable loadtest harness and tooling

**Files:**
- `services/speech-gateway/src/test/java/com/kafkaasr/gateway/ws/downlink/GatewayPipelineLoadHarnessTests.java` (new)
- `tools/loadtest-alert-closure.sh` (new)

- [x] Add a gateway pipeline loadtest harness that simulates long-lived sessions, high-frequency `audio.frame`, and subtitle downlink events.
- [x] Emit structured report JSON with throughput, success ratio, and latency percentiles.
- [x] Add a single command entrypoint script to run the harness and print a concise summary.

### Task 2: Baseline report and runbook

**Files:**
- `docs/reports/loadtest/2026-04-22-baseline.md` (new)
- `docs/runbooks/loadtest-alert-closure.md` (new)

- [x] Execute the loadtest tooling and capture baseline metrics snapshot.
- [x] Record baseline values and interpreted risks in a versioned report document.
- [x] Add an operator runbook covering execution cadence, pass/fail gates, and escalation steps.

### Task 3: Alert threshold calibration and docs sync

**Files:**
- `deploy/monitoring/prometheus/alerts/kafka-asr-alerts.yml`
- `deploy/monitoring/README.md`
- `docs/observability.md`
- `docs/automation.md`

- [x] Calibrate alert thresholds using the new baseline and current SLO target framing.
- [x] Add explicit references to loadtest evidence and runbook from observability/automation docs.
- [x] Keep wording clear about what is baseline guidance vs production re-calibration requirements.

## Verification

- [x] `./gradlew :services:speech-gateway:test --tests "com.kafkaasr.gateway.ws.downlink.GatewayPipelineLoadHarnessTests"`
- [x] `tools/loadtest-alert-closure.sh`
- [x] `tools/verify.sh`
