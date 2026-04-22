# Monitoring Baseline

This directory contains repository-managed observability assets for local validation:

- `docker-compose.yml`: launches Prometheus + Grafana
- `prometheus/prometheus.yml`: scrape config for all six services
- `prometheus/alerts/kafka-asr-alerts.yml`: baseline alert rules
- `grafana/provisioning/*`: auto-provision datasource + dashboards
- `grafana/dashboards/kafka-asr-overview.json`: pipeline overview dashboard

Related loadtest + operations docs:

- `tools/loadtest-alert-closure.sh`: gateway loadtest entrypoint
- `docs/reports/loadtest/2026-04-22-baseline.md`: current baseline evidence snapshot
- `docs/runbooks/loadtest-alert-closure.md`: execution cadence, pass/fail gates, escalation

## Quick start

From repository root:

```bash
tools/monitoring-up.sh
```

Access:

- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3000` (`admin` / `admin`)

Stop:

```bash
tools/monitoring-down.sh
```

## Loadtest-assisted alert calibration

Run from repository root:

```bash
tools/loadtest-alert-closure.sh
```

Generated artifacts:

- `build/reports/loadtest/gateway-pipeline-loadtest.json`
- `build/reports/loadtest/gateway-pipeline-loadtest-summary.md`

Current alert thresholds in `prometheus/alerts/kafka-asr-alerts.yml` are baseline-calibrated from the
`2026-04-22` in-repo harness run, and must be re-calibrated with pre-production/production traffic data.
