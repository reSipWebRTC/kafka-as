#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

usage() {
  cat <<'USAGE'
Usage:
  tools/alert-ops-validate.sh [--env-file <path>] [--report-dir <path>] [--skip-render]

Purpose:
  Validate alert operationalization baselines:
  - threshold env completeness and warning/critical ordering
  - Prometheus alert severity grading and required rules
  - Alertmanager warning/critical/escalation notification chain

Outputs:
  build/reports/alert-ops/alert-ops-validation.json
  build/reports/alert-ops/alert-ops-validation-summary.md
USAGE
}

env_file="${ALERT_OPS_ENV_FILE:-$repo_root/deploy/monitoring/alert-ops.env}"
report_dir="${ALERT_OPS_REPORT_DIR:-$repo_root/build/reports/alert-ops}"
report_path="${ALERT_OPS_REPORT_PATH:-$report_dir/alert-ops-validation.json}"
summary_path="${ALERT_OPS_SUMMARY_PATH:-$report_dir/alert-ops-validation-summary.md}"
alerts_path="$repo_root/deploy/monitoring/prometheus/alerts/kafka-asr-alerts.yml"
alertmanager_path="$repo_root/deploy/monitoring/alertmanager/alertmanager.yml"
skip_render=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env-file)
      if [[ $# -lt 2 ]]; then
        echo "--env-file requires a value" >&2
        exit 1
      fi
      env_file="$2"
      shift 2
      ;;
    --report-dir)
      if [[ $# -lt 2 ]]; then
        echo "--report-dir requires a value" >&2
        exit 1
      fi
      report_dir="$2"
      report_path="$report_dir/alert-ops-validation.json"
      summary_path="$report_dir/alert-ops-validation-summary.md"
      shift 2
      ;;
    --skip-render)
      skip_render=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

mkdir -p "$report_dir"
mkdir -p "$(dirname "$report_path")"
mkdir -p "$(dirname "$summary_path")"

if [[ "$skip_render" != "1" ]]; then
  tools/render-monitoring-config.sh --env-file "$env_file"
fi

python3 - <<'PY' \
  "$env_file" \
  "$alerts_path" \
  "$alertmanager_path" \
  "$report_path" \
  "$summary_path" \
  "$skip_render"
import json
import pathlib
import re
from datetime import datetime, timezone
import sys
import yaml

(
    env_file_raw,
    alerts_path_raw,
    alertmanager_path_raw,
    report_path_raw,
    summary_path_raw,
    skip_render_raw,
) = sys.argv[1:]

env_file = pathlib.Path(env_file_raw)
alerts_path = pathlib.Path(alerts_path_raw)
alertmanager_path = pathlib.Path(alertmanager_path_raw)
report_path = pathlib.Path(report_path_raw)
summary_path = pathlib.Path(summary_path_raw)
skip_render = skip_render_raw == "1"

checks: list[dict] = []

def add_check(check_id: str, status: str, detail: str) -> None:
    checks.append(
        {
            "checkId": check_id,
            "status": status,
            "detail": detail,
        }
    )

env_map: dict[str, str] = {}
if env_file.exists():
    for raw in env_file.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        env_map[key.strip()] = value.strip()
else:
    add_check("env:file", "FAIL", f"missing env file: {env_file}")

if env_file.exists():
    add_check("env:file", "PASS", f"env file found: {env_file}")

ordering_pairs = [
    ("ALERT_GATEWAY_WS_ERROR_RATIO_WARNING", "ALERT_GATEWAY_WS_ERROR_RATIO_CRITICAL"),
    ("ALERT_DOWNLINK_ERROR_RATIO_WARNING", "ALERT_DOWNLINK_ERROR_RATIO_CRITICAL"),
    ("ALERT_COMMAND_DISPATCH_SUCCESS_RATIO_CRITICAL", "ALERT_COMMAND_DISPATCH_SUCCESS_RATIO_WARNING"),
    ("ALERT_COMMAND_CONFIRM_TIMEOUT_RATIO_WARNING", "ALERT_COMMAND_CONFIRM_TIMEOUT_RATIO_CRITICAL"),
    ("ALERT_COMMAND_EXECUTION_FAILURE_RATIO_WARNING", "ALERT_COMMAND_EXECUTION_FAILURE_RATIO_CRITICAL"),
    ("ALERT_COMMAND_E2E_P95_SECONDS_WARNING", "ALERT_COMMAND_E2E_P95_SECONDS_CRITICAL"),
    ("ALERT_PIPELINE_ERROR_RATIO_WARNING", "ALERT_PIPELINE_ERROR_RATIO_CRITICAL"),
    ("ALERT_PIPELINE_P95_SECONDS_WARNING", "ALERT_PIPELINE_P95_SECONDS_CRITICAL"),
    ("ALERT_KAFKA_LAG_WARNING", "ALERT_KAFKA_LAG_CRITICAL"),
    ("ALERT_ORCHESTRATOR_FALLBACK_RPS_WARNING", "ALERT_ORCHESTRATOR_FALLBACK_RPS_CRITICAL"),
    ("ALERT_CONTROL_AUTH_DENY_RATIO_WARNING", "ALERT_CONTROL_AUTH_DENY_RATIO_CRITICAL"),
    ("ALERT_CONTROL_EXTERNAL_UNAVAILABLE_RPS_WARNING", "ALERT_CONTROL_EXTERNAL_UNAVAILABLE_RPS_CRITICAL"),
    ("ALERT_CONTROL_HYBRID_FALLBACK_RPS_WARNING", "ALERT_CONTROL_HYBRID_FALLBACK_RPS_CRITICAL"),
]

for warning_key, critical_key in ordering_pairs:
    warning_raw = env_map.get(warning_key)
    critical_raw = env_map.get(critical_key)
    if warning_raw is None or critical_raw is None:
        add_check(
            f"env:ordering:{warning_key}->{critical_key}",
            "FAIL",
            "missing threshold variable",
        )
        continue
    try:
        warning_value = float(warning_raw)
        critical_value = float(critical_raw)
    except ValueError:
        add_check(
            f"env:ordering:{warning_key}->{critical_key}",
            "FAIL",
            f"non-numeric values: warning={warning_raw} critical={critical_raw}",
        )
        continue

    if warning_key == "ALERT_COMMAND_DISPATCH_SUCCESS_RATIO_CRITICAL":
        if warning_value < critical_value:
            add_check(
                f"env:ordering:{warning_key}->{critical_key}",
                "PASS",
                f"critical({warning_value}) < warning({critical_value})",
            )
        else:
            add_check(
                f"env:ordering:{warning_key}->{critical_key}",
                "FAIL",
                f"critical({warning_value}) must be < warning({critical_value})",
            )
        continue

    if critical_value > warning_value:
        add_check(
            f"env:ordering:{warning_key}->{critical_key}",
            "PASS",
            f"critical({critical_value}) > warning({warning_value})",
        )
    else:
        add_check(
            f"env:ordering:{warning_key}->{critical_key}",
            "FAIL",
            f"critical({critical_value}) must be > warning({warning_value})",
        )

alert_docs = []
if alerts_path.exists():
    try:
        parsed = yaml.safe_load(alerts_path.read_text(encoding="utf-8"))
        if isinstance(parsed, dict):
            alert_docs.append(parsed)
            add_check("alerts:parse", "PASS", f"parsed alert rules: {alerts_path}")
        else:
            add_check("alerts:parse", "FAIL", "unexpected YAML structure")
    except yaml.YAMLError as exc:
        add_check("alerts:parse", "FAIL", f"invalid YAML: {exc}")
else:
    add_check("alerts:parse", "FAIL", f"missing alerts file: {alerts_path}")

rule_entries = []
for doc in alert_docs:
    for group in doc.get("groups", []):
        for rule in group.get("rules", []):
            if isinstance(rule, dict) and "alert" in rule:
                rule_entries.append(rule)

if rule_entries:
    add_check("alerts:rule-count", "PASS", f"rule count={len(rule_entries)}")
else:
    add_check("alerts:rule-count", "FAIL", "no alert rules found")

severity_counts = {"warning": 0, "critical": 0}
missing_severity_rules = []
for rule in rule_entries:
    labels = rule.get("labels", {})
    severity = labels.get("severity")
    if severity in severity_counts:
        severity_counts[severity] += 1
    else:
        missing_severity_rules.append(rule.get("alert", "<unknown>"))

if missing_severity_rules:
    add_check(
        "alerts:severity-labels",
        "FAIL",
        f"rules missing/invalid severity labels: {', '.join(missing_severity_rules)}",
    )
else:
    add_check(
        "alerts:severity-labels",
        "PASS",
        f"warning={severity_counts['warning']} critical={severity_counts['critical']}",
    )

required_alerts = {
    "GatewayWsErrorRateHigh",
    "GatewayWsErrorRateCritical",
    "DownlinkErrorRateHigh",
    "DownlinkErrorRateCritical",
    "CommandDispatchSuccessRateLow",
    "CommandDispatchSuccessRateCritical",
    "CommandConfirmTimeoutRateHigh",
    "CommandConfirmTimeoutRateCritical",
    "CommandExecutionFailureRateHigh",
    "CommandExecutionFailureRateCritical",
    "CommandPipelineE2EP95LatencyHigh",
    "CommandPipelineE2EP95LatencyCritical",
    "PipelineErrorRateHigh",
    "PipelineErrorRateCritical",
    "KafkaConsumerLagHigh",
    "KafkaConsumerLagCritical",
    "ControlPlaneAuthDenyRateHigh",
    "ControlPlaneAuthDenyRateCritical",
    "ControlPlaneExternalIamUnavailableWarning",
    "ControlPlaneExternalIamUnavailableSpike",
    "ControlPlaneHybridFallbackSpike",
    "ControlPlaneHybridFallbackCritical",
}
actual_alerts = {rule.get("alert") for rule in rule_entries}
missing_alerts = sorted(alert for alert in required_alerts if alert not in actual_alerts)
if missing_alerts:
    add_check("alerts:required-alerts", "FAIL", f"missing alerts: {', '.join(missing_alerts)}")
else:
    add_check("alerts:required-alerts", "PASS", f"required alerts present={len(required_alerts)}")

alertmanager_config = {}
if alertmanager_path.exists():
    try:
        parsed = yaml.safe_load(alertmanager_path.read_text(encoding="utf-8"))
        if isinstance(parsed, dict):
            alertmanager_config = parsed
            add_check("alertmanager:parse", "PASS", f"parsed config: {alertmanager_path}")
        else:
            add_check("alertmanager:parse", "FAIL", "unexpected YAML structure")
    except yaml.YAMLError as exc:
        add_check("alertmanager:parse", "FAIL", f"invalid YAML: {exc}")
else:
    add_check("alertmanager:parse", "FAIL", f"missing alertmanager file: {alertmanager_path}")

route = alertmanager_config.get("route", {})
if route.get("receiver") == "oncall-default":
    add_check("alertmanager:default-receiver", "PASS", "default receiver is oncall-default")
else:
    add_check("alertmanager:default-receiver", "FAIL", "default receiver must be oncall-default")

routes = route.get("routes", [])
def route_matches(candidate: dict, severity: str, receiver: str) -> bool:
    if candidate.get("receiver") != receiver:
        return False
    for matcher in candidate.get("matchers", []):
        if re.search(rf'severity\s*=\s*"{severity}"', matcher):
            return True
    return False

critical_route = next((item for item in routes if route_matches(item, "critical", "oncall-critical")), None)
critical_escalation_route = next((item for item in routes if route_matches(item, "critical", "oncall-critical-escalation")), None)
warning_route = next((item for item in routes if route_matches(item, "warning", "oncall-warning")), None)

if critical_route:
    if critical_route.get("continue") is True:
        add_check("alertmanager:critical-route", "PASS", "critical route with continue=true exists")
    else:
        add_check("alertmanager:critical-route", "FAIL", "critical route must set continue=true for escalation chain")
else:
    add_check("alertmanager:critical-route", "FAIL", "missing critical route")

if critical_escalation_route:
    add_check("alertmanager:critical-escalation-route", "PASS", "critical escalation route exists")
else:
    add_check("alertmanager:critical-escalation-route", "FAIL", "missing critical escalation route")

if warning_route:
    add_check("alertmanager:warning-route", "PASS", "warning route exists")
else:
    add_check("alertmanager:warning-route", "FAIL", "missing warning route")

receivers = alertmanager_config.get("receivers", [])
receiver_names = {entry.get("name") for entry in receivers if isinstance(entry, dict)}
for required_receiver in ("oncall-default", "oncall-warning", "oncall-critical", "oncall-critical-escalation"):
    if required_receiver in receiver_names:
        add_check(f"alertmanager:receiver:{required_receiver}", "PASS", "receiver present")
    else:
        add_check(f"alertmanager:receiver:{required_receiver}", "FAIL", "receiver missing")

pass_count = sum(1 for item in checks if item["status"] == "PASS")
fail_count = sum(1 for item in checks if item["status"] == "FAIL")
warn_count = sum(1 for item in checks if item["status"] == "WARN")
overall_pass = fail_count == 0

report = {
    "generatedAt": datetime.now(timezone.utc).isoformat(),
    "mode": "alert-ops-validation",
    "overallPass": overall_pass,
    "config": {
        "envFile": str(env_file),
        "alertsPath": str(alerts_path),
        "alertmanagerPath": str(alertmanager_path),
        "skipRender": skip_render,
    },
    "summary": {
        "total": len(checks),
        "pass": pass_count,
        "fail": fail_count,
        "warn": warn_count,
    },
    "checks": checks,
}
report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

lines = [
    "# Alert Ops Validation Summary",
    "",
    f"- generatedAt: {report['generatedAt']}",
    f"- mode: {report['mode']}",
    f"- overallPass: {str(overall_pass).lower()}",
    f"- pass/fail/warn: {pass_count}/{fail_count}/{warn_count}",
    f"- envFile: {env_file}",
    f"- alertsPath: {alerts_path}",
    f"- alertmanagerPath: {alertmanager_path}",
    f"- reportPath: {report_path}",
    "",
    "| checkId | status | detail |",
    "| --- | --- | --- |",
]

for check in checks:
    lines.append(
        "| {check_id} | {status} | {detail} |".format(
            check_id=check["checkId"],
            status=check["status"],
            detail=check["detail"].replace("|", "\\|"),
        )
    )

summary_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
print(summary_path.read_text(encoding="utf-8"))
sys.exit(0 if overall_pass else 1)
PY
