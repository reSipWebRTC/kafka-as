#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

report_dir="${MOCK_IAM_DRILL_REPORT_DIR:-$repo_root/build/reports/mock-iam-drill}"
report_path="${MOCK_IAM_DRILL_REPORT_PATH:-$report_dir/mock-iam-rbac-drill.json}"
summary_path="${MOCK_IAM_DRILL_SUMMARY_PATH:-$report_dir/mock-iam-rbac-drill-summary.md}"

chain_test_command="${MOCK_IAM_DRILL_CHAIN_TEST_COMMAND:-./gradlew :services:control-plane:test --no-daemon --tests com.kafkaasr.control.auth.ExternalIamJwksChainIntegrationTests}"
matrix_test_command="${MOCK_IAM_DRILL_MATRIX_TEST_COMMAND:-./gradlew :services:control-plane:test --no-daemon --tests com.kafkaasr.control.auth.ExternalIamClaimMatrixTests}"
failure_test_command="${MOCK_IAM_DRILL_FAILURE_TEST_COMMAND:-./gradlew :services:control-plane:test --no-daemon --tests com.kafkaasr.control.auth.ExternalIamFailureDrillTests}"
preprod_dry_run_command="${MOCK_IAM_DRILL_PREPROD_DRY_RUN_COMMAND:-PREPROD_DRY_RUN=1 PREPROD_AUTH_DRILL_REQUIRED=1 PREPROD_AUTH_DRILL_COMMAND=tools/control-plane-auth-drill.sh tools/preprod-drill-closure.sh}"

alert_rules_file="${MOCK_IAM_DRILL_ALERT_RULES_FILE:-$repo_root/deploy/monitoring/prometheus/alerts/kafka-asr-alerts.yml}"
required_alerts_raw="${MOCK_IAM_DRILL_REQUIRED_ALERTS:-ControlPlaneAuthDenyRateHigh,ControlPlaneExternalIamUnavailableSpike,ControlPlaneHybridFallbackSpike}"

mkdir -p "$report_dir"
mkdir -p "$(dirname "$report_path")"
mkdir -p "$(dirname "$summary_path")"

phase_records="$(mktemp)"
trap 'rm -f "$phase_records"' EXIT

utc_now() {
  date -u +"%Y-%m-%dT%H:%M:%SZ"
}

run_phase() {
  local phase="$1"
  local command="$2"
  local log_path="$report_dir/mock-iam-${phase}.log"
  local started_at
  started_at="$(utc_now)"

  local exit_code=0
  set +e
  bash -lc "$command" 2>&1 | tee "$log_path"
  exit_code="${PIPESTATUS[0]}"
  set -e

  local ended_at
  ended_at="$(utc_now)"
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$phase" \
    "$command" \
    "$log_path" \
    "$exit_code" \
    "$started_at" \
    "$ended_at" >> "$phase_records"

  return "$exit_code"
}

run_alert_rule_phase() {
  local phase="alert-rules-check"
  local command="python3 verify-alert-rules"
  local log_path="$report_dir/mock-iam-${phase}.log"
  local started_at
  started_at="$(utc_now)"
  local exit_code=0

  set +e
  python3 - <<'PY' "$alert_rules_file" "$required_alerts_raw" 2>&1 | tee "$log_path"
import pathlib
import re
import sys

alert_file = pathlib.Path(sys.argv[1])
required = [entry.strip() for entry in sys.argv[2].split(",") if entry.strip()]

content = alert_file.read_text(encoding="utf-8")
alerts = set(re.findall(r"(?m)^\s*-\s+alert:\s+([A-Za-z0-9_]+)\s*$", content))

missing = [name for name in required if name not in alerts]
if missing:
    print("Missing alerts:", ",".join(missing))
    raise SystemExit(1)

print("Alert rules present:", ",".join(required))
PY
  exit_code="${PIPESTATUS[0]}"
  set -e

  local ended_at
  ended_at="$(utc_now)"
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$phase" \
    "$command" \
    "$log_path" \
    "$exit_code" \
    "$started_at" \
    "$ended_at" >> "$phase_records"

  return "$exit_code"
}

echo "Running mock IAM/RBAC drill (simulated/mock evidence mode)"
echo "report_dir=$report_dir"

run_phase "jwks-jwt-chain" "$chain_test_command"
run_phase "claim-matrix" "$matrix_test_command"
run_phase "failure-drill" "$failure_test_command"
run_alert_rule_phase
run_phase "preprod-closure-dry-run" "$preprod_dry_run_command"

set +e
python3 - <<'PY' \
  "$report_path" \
  "$summary_path" \
  "$phase_records" \
  "$alert_rules_file" \
  "$required_alerts_raw"
import json
import pathlib
from datetime import datetime, timezone
import sys

(
    report_path_raw,
    summary_path_raw,
    phase_records_raw,
    alert_rules_file,
    required_alerts_raw,
) = sys.argv[1:]

report_path = pathlib.Path(report_path_raw)
summary_path = pathlib.Path(summary_path_raw)
phase_records_path = pathlib.Path(phase_records_raw)
required_alerts = [entry.strip() for entry in required_alerts_raw.split(",") if entry.strip()]

phases = []
for line in phase_records_path.read_text(encoding="utf-8").splitlines():
    phase, command, log_path, exit_code, started_at, ended_at = line.split("\t")
    phases.append(
        {
            "phase": phase,
            "command": command,
            "logPath": log_path,
            "result": {
                "exitCode": int(exit_code),
                "pass": int(exit_code) == 0,
            },
            "startedAt": started_at,
            "endedAt": ended_at,
        }
    )

overall_pass = all(item["result"]["pass"] for item in phases) if phases else False

report = {
    "generatedAt": datetime.now(timezone.utc).isoformat(),
    "mode": "simulated/mock",
    "scope": {
        "jwksJwtChain": True,
        "claimMatrix": True,
        "failureDrill": True,
        "alertRuleValidation": True,
        "preprodClosureDryRun": True,
    },
    "config": {
        "alertRulesFile": alert_rules_file,
        "requiredAlerts": required_alerts,
    },
    "phases": phases,
    "overallPass": overall_pass,
}
report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

lines = [
    "# Mock IAM/RBAC Drill Summary",
    "",
    f"- generatedAt: {report['generatedAt']}",
    f"- mode: {report['mode']}",
    f"- overallPass: {str(overall_pass).lower()}",
    f"- reportPath: {report_path}",
    f"- alertRulesFile: {alert_rules_file}",
    f"- requiredAlerts: {', '.join(required_alerts)}",
    "",
    "| phase | pass | exitCode | command | logPath |",
    "| --- | --- | ---: | --- | --- |",
]
for item in phases:
    lines.append(
        "| {phase} | {status} | {exit_code} | `{command}` | `{log_path}` |".format(
            phase=item["phase"],
            status="PASS" if item["result"]["pass"] else "FAIL",
            exit_code=item["result"]["exitCode"],
            command=item["command"],
            log_path=item["logPath"],
        )
    )

summary = "\n".join(lines) + "\n"
summary_path.write_text(summary, encoding="utf-8")
print(summary)
sys.exit(0 if overall_pass else 1)
PY
status="$?"
set -e

echo "Mock IAM/RBAC drill report: $report_path"
echo "Mock IAM/RBAC drill summary: $summary_path"
exit "$status"
