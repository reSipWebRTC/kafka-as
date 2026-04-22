# Monitoring Baseline

This directory contains repository-managed observability assets for local validation:

- `docker-compose.yml`: launches Prometheus + Grafana
- `prometheus/prometheus.yml`: scrape config for all six services
- `prometheus/alerts/kafka-asr-alerts.yml`: baseline alert rules
- `grafana/provisioning/*`: auto-provision datasource + dashboards
- `grafana/dashboards/kafka-asr-overview.json`: pipeline overview dashboard

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
