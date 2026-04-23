#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

usage() {
  cat <<'USAGE'
Usage:
  tools/control-plane-jwks-jwt-drill.sh [--report-dir <path>] [--skip-tests]

Purpose:
  Run local simulated/mock end-to-end drill for control-plane external-iam/hybrid auth:
  - external-iam with real JWKS + signed JWT
  - hybrid fallback when external IAM is unavailable

Outputs:
  build/reports/preprod-drill/control-plane-jwks-jwt-drill.json
  build/reports/preprod-drill/control-plane-jwks-jwt-drill-summary.md
USAGE
}

report_dir="${CONTROL_AUTH_JWKS_JWT_DRILL_REPORT_DIR:-$repo_root/build/reports/preprod-drill}"
report_path="${CONTROL_AUTH_JWKS_JWT_DRILL_REPORT_PATH:-$report_dir/control-plane-jwks-jwt-drill.json}"
summary_path="${CONTROL_AUTH_JWKS_JWT_DRILL_SUMMARY_PATH:-$report_dir/control-plane-jwks-jwt-drill-summary.md}"
skip_tests="${CONTROL_AUTH_JWKS_JWT_DRILL_SKIP_TESTS:-0}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --report-dir)
      if [[ $# -lt 2 ]]; then
        echo "--report-dir requires a value" >&2
        exit 1
      fi
      report_dir="$2"
      report_path="$report_dir/control-plane-jwks-jwt-drill.json"
      summary_path="$report_dir/control-plane-jwks-jwt-drill-summary.md"
      shift 2
      ;;
    --skip-tests)
      skip_tests=1
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
  local status="$2"
  local started_at="$3"
  local ended_at="$4"
  local duration_ms="$5"
  local detail="$6"
  local log_path="$7"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$scenario" \
    "$status" \
    "$started_at" \
    "$ended_at" \
    "$duration_ms" \
    "$detail" \
    "$log_path" >> "$records_file"
}

run_scenario() {
  local scenario="$1"
  local test_selector="$2"
  local log_path="$report_dir/control-plane-jwks-jwt-${scenario}.log"

  local started_at end_ms start_ms ended_at duration_ms exit_code status detail
  started_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  start_ms="$(date +%s%3N)"

  if [[ "$skip_tests" == "1" ]]; then
    ended_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
    end_ms="$(date +%s%3N)"
    duration_ms=$((end_ms - start_ms))
    record_result "$scenario" "SKIP" "$started_at" "$ended_at" "$duration_ms" "skipped by --skip-tests" "$log_path"
    return
  fi

  cmd=(
    ./gradlew
    :services:control-plane:test
    --rerun-tasks
    --tests
    "$test_selector"
  )

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

  record_result "$scenario" "$status" "$started_at" "$ended_at" "$duration_ms" "$detail" "$log_path"
}

echo "Running control-plane local JWKS + JWT drill"
echo "report_dir=$report_dir"

run_scenario \
  "external-iam-jwks-jwt-e2e" \
  "com.kafkaasr.control.api.TenantPolicyControllerExternalIamJwksIntegrationTests"

run_scenario \
  "hybrid-external-unavailable-fallback-e2e" \
  "com.kafkaasr.control.api.TenantPolicyControllerHybridFallbackIntegrationTests"

set +e
python3 - <<'PY' "$records_file" "$report_path" "$summary_path" "$skip_tests"
import json
import pathlib
from datetime import datetime, timezone
import sys

records_path = pathlib.Path(sys.argv[1])
report_path = pathlib.Path(sys.argv[2])
summary_path = pathlib.Path(sys.argv[3])
skip_tests = sys.argv[4] == "1"

rows = []
for line in records_path.read_text(encoding="utf-8").splitlines():
    scenario, status, started_at, ended_at, duration_ms, detail, log_path = line.split("\t")
    rows.append(
        {
            "scenario": scenario,
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
        "skipTests": skip_tests,
    },
    "scenarios": rows,
}
report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

lines = [
    "# Control-Plane Local JWKS + JWT Drill Summary",
    "",
    f"- generatedAt: {report['generatedAt']}",
    f"- mode: {report['mode']}",
    f"- overallPass: {str(overall_pass).lower()}",
    f"- pass/fail/skip: {pass_count}/{fail_count}/{skip_count}",
    f"- reportPath: {report_path}",
    "",
    "| scenario | status | duration(ms) | detail |",
    "| --- | --- | ---: | --- |",
]
for row in rows:
    lines.append(
        "| {scenario} | {status} | {duration} | {detail} |".format(
            scenario=row["scenario"],
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

echo "Control-plane local JWKS/JWT drill report: $report_path"
echo "Control-plane local JWKS/JWT drill summary: $summary_path"
exit "$status"
