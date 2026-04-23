# Monitoring Baseline

This directory contains repository-managed observability assets for local validation:

- `docker-compose.yml`: launches Alertmanager + Prometheus + Grafana
- `alert-ops.env`: threshold and routing baseline parameters
- `alertmanager/alertmanager.template.yml`: notification routing template
- `alertmanager/alertmanager.yml`: rendered notification routing config
- `prometheus/prometheus.yml`: scrape config for all six services
- `prometheus/alerts/kafka-asr-alerts.template.yml`: alert rules template
- `prometheus/alerts/kafka-asr-alerts.yml`: rendered alert rules
- `grafana/provisioning/*`: auto-provision datasource + dashboards
- `grafana/dashboards/kafka-asr-overview.json`: pipeline overview dashboard

Related loadtest + operations docs:

- `tools/loadtest-alert-closure.sh`: gateway loadtest entrypoint
- `docs/reports/loadtest/2026-04-22-baseline.md`: current baseline evidence snapshot
- `docs/runbooks/loadtest-alert-closure.md`: execution cadence, pass/fail gates, escalation

## Quick start

From repository root:

```bash
tools/render-monitoring-config.sh
tools/monitoring-up.sh
```

Access:

- Alertmanager: `http://localhost:9093`
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

## Alert Ops Validation

Run from repository root:

```bash
tools/alert-ops-validate.sh
```

Generated artifacts:

- `build/reports/alert-ops/alert-ops-validation.json`
- `build/reports/alert-ops/alert-ops-validation-summary.md`

This validates:

- threshold env completeness and warning/critical ordering
- required warning/critical alert rule coverage
- Alertmanager warning/critical/escalation receiver chain

## Notification routing configuration

By default, Alertmanager sends webhook notifications to local placeholder endpoints:

- `http://host.docker.internal:19093/alerts/default`
- `http://host.docker.internal:19093/alerts/warning`
- `http://host.docker.internal:19093/alerts/critical`
- `http://host.docker.internal:19093/alerts/critical-escalation`

Override per environment before `tools/monitoring-up.sh`:

```bash
export ALERTMANAGER_DEFAULT_WEBHOOK_URL="https://alerts.example.com/default"
export ALERTMANAGER_WARNING_WEBHOOK_URL="https://alerts.example.com/warning"
export ALERTMANAGER_CRITICAL_WEBHOOK_URL="https://alerts.example.com/critical"
export ALERTMANAGER_CRITICAL_ESCALATION_WEBHOOK_URL="https://alerts.example.com/critical-escalation"
```
