#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

usage() {
  cat <<'USAGE'
Usage:
  tools/control-plane-auth-failure-drill.sh [--report-dir <path>] [--skip-tests] [--skip-alert-rules]

Purpose:
  Run a simulated/mock failure-policy drill for control-plane auth:
  - JWKS unavailable / timeout classification checks
  - hybrid fallback behavior checks
  - auth metrics instrumentation checks
  - alert rule presence checks

Outputs:
  build/reports/preprod-drill/control-plane-auth-failure-drill.json
  build/reports/preprod-drill/control-plane-auth-failure-drill-summary.md
USAGE
}

report_dir="${CONTROL_AUTH_FAILURE_DRILL_REPORT_DIR:-$repo_root/build/reports/preprod-drill}"
report_path="${CONTROL_AUTH_FAILURE_DRILL_REPORT_PATH:-$report_dir/control-plane-auth-failure-drill.json}"
summary_path="${CONTROL_AUTH_FAILURE_DRILL_SUMMARY_PATH:-$report_dir/control-plane-auth-failure-drill-summary.md}"
alerts_file="${CONTROL_AUTH_FAILURE_DRILL_ALERTS_FILE:-$repo_root/deploy/monitoring/prometheus/alerts/kafka-asr-alerts.yml}"
skip_tests="${CONTROL_AUTH_FAILURE_DRILL_SKIP_TESTS:-0}"
skip_alert_rules="${CONTROL_AUTH_FAILURE_DRILL_SKIP_ALERT_RULES:-0}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --report-dir)
      if [[ $# -lt 2 ]]; then
        echo "--report-dir requires a value" >&2
        exit 1
      fi
      report_dir="$2"
      report_path="$report_dir/control-plane-auth-failure-drill.json"
      summary_path="$report_dir/control-plane-auth-failure-drill-summary.md"
      shift 2
      ;;
    --skip-tests)
      skip_tests=1
      shift
      ;;
    --skip-alert-rules)
      skip_alert_rules=1
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

records_file="$(mktemp)"
trap 'rm -f "$records_file"' EXIT

record_result() {
  local scenario="$1"
  local kind="$2"
  local status="$3"
  local started_at="$4"
  local ended_at="$5"
  local duration_ms="$6"
  local detail="$7"
  local log_path="$8"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$scenario" \
    "$kind" \
    "$status" \
    "$started_at" \
    "$ended_at" \
    "$duration_ms" \
    "$detail" \
    "$log_path" >> "$records_file"
}

run_gradle_scenario() {
  local scenario="$1"
  shift
  local tests=("$@")

  local log_path="$report_dir/control-plane-auth-failure-${scenario}.log"
  local started_at end_ms start_ms ended_at duration_ms exit_code status detail

  started_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  start_ms="$(date +%s%3N)"

  if [[ "$skip_tests" == "1" ]]; then
    ended_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
    end_ms="$(date +%s%3N)"
    duration_ms=$((end_ms - start_ms))
    record_result "$scenario" "gradle-test" "SKIP" "$started_at" "$ended_at" "$duration_ms" "skipped by --skip-tests" "$log_path"
    return
  fi

  cmd=(./gradlew :services:control-plane:test --rerun-tasks)
  for test in "${tests[@]}"; do
    cmd+=(--tests "$test")
  done

  set +e
  "${cmd[@]}" 2>&1 | tee "$log_path"
  exit_code="${PIPESTATUS[0]}"
  set -e

  end_ms="$(date +%s%3N)"
  duration_ms=$((end_ms - start_ms))
  ended_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"

  if [[ "$exit_code" -eq 0 ]]; then
    status="PASS"
    detail="all targeted tests passed"
  else
    status="FAIL"
    detail="gradle exit code=$exit_code"
  fi

  record_result "$scenario" "gradle-test" "$status" "$started_at" "$ended_at" "$duration_ms" "$detail" "$log_path"
}

run_alert_rule_scenario() {
  local scenario="alert-rules-control-plane-auth"
  local log_path="$report_dir/control-plane-auth-failure-${scenario}.log"
  local started_at end_ms start_ms ended_at duration_ms
  started_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  start_ms="$(date +%s%3N)"

  if [[ "$skip_alert_rules" == "1" ]]; then
    ended_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
    end_ms="$(date +%s%3N)"
    duration_ms=$((end_ms - start_ms))
    record_result "$scenario" "alert-rule" "SKIP" "$started_at" "$ended_at" "$duration_ms" "skipped by --skip-alert-rules" "$log_path"
    return
  fi

  if [[ ! -f "$alerts_file" ]]; then
    ended_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
    end_ms="$(date +%s%3N)"
    duration_ms=$((end_ms - start_ms))
    record_result "$scenario" "alert-rule" "FAIL" "$started_at" "$ended_at" "$duration_ms" "alerts file not found: $alerts_file" "$log_path"
    return
  fi

  set +e
  python3 - <<'PY' "$alerts_file" > "$log_path"
import pathlib
import sys

alerts_path = pathlib.Path(sys.argv[1])
content = alerts_path.read_text(encoding="utf-8")

checks = [
    ("ControlPlaneAuthDenyRateHigh rule", "- alert: ControlPlaneAuthDenyRateHigh"),
    ("deny ratio metric", "controlplane_auth_decision_total"),
    ("ControlPlaneExternalIamUnavailableSpike rule", "- alert: ControlPlaneExternalIamUnavailableSpike"),
    ("external unavailable reason selector", 'reason="external_unavailable"'),
    ("ControlPlaneHybridFallbackSpike rule", "- alert: ControlPlaneHybridFallbackSpike"),
    ("hybrid fallback metric", "controlplane_auth_hybrid_fallback_total"),
]

missing = [name for name, pattern in checks if pattern not in content]
for name, pattern in checks:
    present = pattern in content
    print(f"{name}: {'PASS' if present else 'FAIL'} ({pattern})")

if missing:
    print("Missing patterns:")
    for item in missing:
        print(f"- {item}")
    raise SystemExit(1)
PY
  python_status="$?"
  set -e

  end_ms="$(date +%s%3N)"
  duration_ms=$((end_ms - start_ms))
  ended_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"

  if [[ "$python_status" -eq 0 ]]; then
    record_result "$scenario" "alert-rule" "PASS" "$started_at" "$ended_at" "$duration_ms" "required auth alert rules present" "$log_path"
  else
    record_result "$scenario" "alert-rule" "FAIL" "$started_at" "$ended_at" "$duration_ms" "missing required auth alert rules" "$log_path"
  fi
}

echo "Running simulated control-plane auth failure drill"
echo "report_dir=$report_dir"

run_gradle_scenario \
  "jwks-unavailable-classification" \
  "com.kafkaasr.control.auth.JwksExternalIamTokenDecoderTests.reportsUnavailableWhenJwksUriMissing" \
  "com.kafkaasr.control.auth.JwksExternalIamTokenDecoderTests.mapsRetrieveRemoteJwkSetFailureAsUnavailable"

run_gradle_scenario \
  "jwks-timeout-classification" \
  "com.kafkaasr.control.auth.JwksExternalIamTokenDecoderTests.mapsConnectTimeoutAsUnavailable" \
  "com.kafkaasr.control.auth.JwksExternalIamTokenDecoderTests.mapsReadTimeoutAsUnavailable" \
  "com.kafkaasr.control.auth.JwksExternalIamTokenDecoderTests.mapsJwtValidationFailureAsInvalidToken"

run_gradle_scenario \
  "hybrid-fallback-and-metrics" \
  "com.kafkaasr.control.auth.ExternalIamAuthBackendTests.returnsUnauthorizedWhenDecoderUnavailable" \
  "com.kafkaasr.control.auth.ModeSwitchAuthBackendTests.hybridFallsBackToStaticWhenExternalUnavailable" \
  "com.kafkaasr.control.auth.ModeSwitchAuthBackendTests.hybridDoesNotFallbackWhenExternalForbids" \
  "com.kafkaasr.control.auth.AuthMetricsInstrumentationTests.modeSwitchRecordsHybridFallbackCounter"

run_alert_rule_scenario

set +e
python3 - <<'PY' "$records_file" "$report_path" "$summary_path" "$alerts_file" "$skip_tests" "$skip_alert_rules"
import json
import pathlib
from datetime import datetime, timezone
import sys

records_path = pathlib.Path(sys.argv[1])
report_path = pathlib.Path(sys.argv[2])
summary_path = pathlib.Path(sys.argv[3])
alerts_file = sys.argv[4]
skip_tests = sys.argv[5] == "1"
skip_alert_rules = sys.argv[6] == "1"

rows = []
for line in records_path.read_text(encoding="utf-8").splitlines():
    scenario, kind, status, started_at, ended_at, duration_ms, detail, log_path = line.split("\t")
    rows.append(
        {
            "scenario": scenario,
            "kind": kind,
            "status": status,
            "startedAt": started_at,
            "endedAt": ended_at,
            "durationMs": int(duration_ms),
            "detail": detail,
            "logPath": log_path,
        }
    )

pass_count = sum(1 for row in rows if row["status"] == "PASS")
fail_count = sum(1 for row in rows if row["status"] == "FAIL")
skip_count = sum(1 for row in rows if row["status"] == "SKIP")
overall_pass = fail_count == 0 and len(rows) > 0

report = {
    "generatedAt": datetime.now(timezone.utc).isoformat(),
    "mode": "simulated/mock",
    "overallPass": overall_pass,
    "summary": {
        "scenarioCount": len(rows),
        "pass": pass_count,
        "fail": fail_count,
        "skip": skip_count,
    },
    "config": {
        "alertsFile": alerts_file,
        "skipTests": skip_tests,
        "skipAlertRules": skip_alert_rules,
    },
    "scenarios": rows,
}

report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

lines = [
    "# Control-Plane Auth Failure Drill Summary",
    "",
    f"- generatedAt: {report['generatedAt']}",
    f"- mode: {report['mode']}",
    f"- overallPass: {str(overall_pass).lower()}",
    f"- pass/fail/skip: {pass_count}/{fail_count}/{skip_count}",
    f"- alertsFile: {alerts_file}",
    f"- reportPath: {report_path}",
    "",
    "| scenario | kind | status | duration(ms) | detail |",
    "| --- | --- | --- | ---: | --- |",
]

for row in rows:
    lines.append(
        "| {scenario} | {kind} | {status} | {duration} | {detail} |".format(
            scenario=row["scenario"],
            kind=row["kind"],
            status=row["status"],
            duration=row["durationMs"],
            detail=row["detail"].replace("|", "\\|"),
        )
    )

summary = "\n".join(lines) + "\n"
summary_path.write_text(summary, encoding="utf-8")
print(summary)
sys.exit(0 if overall_pass else 1)
PY
status="$?"
set -e

echo "Control-plane auth failure drill report: $report_path"
echo "Control-plane auth failure drill summary: $summary_path"
exit "$status"
