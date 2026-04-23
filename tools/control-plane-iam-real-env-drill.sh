#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

usage() {
  cat <<'USAGE'
Usage:
  tools/control-plane-iam-real-env-drill.sh [--env-file <path>] [--run-preprod-closure] [--report-dir <path>]

Purpose:
  Run control-plane real IAM drill with strict real-value validation:
  1) IAM precheck (no placeholder values, with network checks)
  2) control-plane auth drill
  3) optional preprod closure (when --run-preprod-closure is set)

Examples:
  tools/control-plane-iam-real-env-drill.sh --env-file .secrets/control-plane-iam.env
  tools/control-plane-iam-real-env-drill.sh --env-file .secrets/control-plane-iam.env --run-preprod-closure
USAGE
}

env_file="${CONTROL_AUTH_REAL_ENV_FILE:-$repo_root/.secrets/control-plane-iam.env}"
report_dir="${CONTROL_AUTH_REAL_ENV_REPORT_DIR:-$repo_root/build/reports/preprod-drill/real-iam-drill}"
report_path="${CONTROL_AUTH_REAL_ENV_REPORT_PATH:-$report_dir/control-plane-iam-real-env-drill.json}"
summary_path="${CONTROL_AUTH_REAL_ENV_SUMMARY_PATH:-$report_dir/control-plane-iam-real-env-drill-summary.md}"
run_preprod_closure="${CONTROL_AUTH_REAL_ENV_RUN_PREPROD_CLOSURE:-0}"

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
    --run-preprod-closure)
      run_preprod_closure=1
      shift
      ;;
    --report-dir)
      if [[ $# -lt 2 ]]; then
        echo "--report-dir requires a value" >&2
        exit 1
      fi
      report_dir="$2"
      report_path="$report_dir/control-plane-iam-real-env-drill.json"
      summary_path="$report_dir/control-plane-iam-real-env-drill-summary.md"
      shift 2
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

if [[ ! -f "$env_file" ]]; then
  echo "IAM env file not found: $env_file" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$env_file"
set +a

mkdir -p "$report_dir"
mkdir -p "$(dirname "$report_path")"
mkdir -p "$(dirname "$summary_path")"

records_file="$(mktemp)"
trap 'rm -f "$records_file"' EXIT

run_step() {
  local step_name="$1"
  shift
  local log_path="$report_dir/${step_name}.log"
  local started_at ended_at start_ms end_ms duration_ms status detail exit_code

  started_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  start_ms="$(date +%s%3N)"

  set +e
  "$@" 2>&1 | tee "$log_path"
  exit_code="${PIPESTATUS[0]}"
  set -e

  end_ms="$(date +%s%3N)"
  duration_ms=$((end_ms - start_ms))
  ended_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"

  if [[ "$exit_code" -eq 0 ]]; then
    status="PASS"
    detail="step completed"
  else
    status="FAIL"
    detail="exit code=$exit_code"
  fi

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$step_name" \
    "$status" \
    "$started_at" \
    "$ended_at" \
    "$duration_ms" \
    "$detail" \
    "$log_path" >> "$records_file"

  return "$exit_code"
}

echo "Running real IAM drill"
echo "env_file=$env_file"
echo "report_dir=$report_dir"

precheck_passed=0
auth_drill_passed=0

if run_step \
  "control-plane-iam-precheck" \
  tools/control-plane-iam-precheck.sh \
  --env-file "$env_file" \
  --report-dir "$report_dir/control-plane-iam-precheck"; then
  precheck_passed=1
fi

if [[ "$precheck_passed" == "1" ]]; then
  if run_step \
    "control-plane-auth-drill" \
    env \
      CONTROL_AUTH_DRILL_REPORT_DIR="$report_dir" \
      CONTROL_AUTH_DRILL_REPORT_PATH="$report_dir/control-plane-auth-drill.json" \
      CONTROL_AUTH_DRILL_SUMMARY_PATH="$report_dir/control-plane-auth-drill-summary.md" \
      tools/control-plane-auth-drill.sh; then
    auth_drill_passed=1
  fi
else
  now="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "control-plane-auth-drill" \
    "SKIP" \
    "$now" \
    "$now" \
    "0" \
    "skipped (precheck failed)" \
    "$report_dir/control-plane-auth-drill.log" >> "$records_file"
fi

if [[ "$run_preprod_closure" == "1" ]]; then
  if [[ "$precheck_passed" == "1" && "$auth_drill_passed" == "1" ]]; then
    run_step \
      "preprod-drill-closure" \
      env \
        PREPROD_REPORT_DIR="$report_dir" \
        PREPROD_REPORT_PATH="$report_dir/preprod-drill-closure.json" \
        PREPROD_SUMMARY_PATH="$report_dir/preprod-drill-closure-summary.md" \
        PREPROD_AUTH_DRILL_REQUIRED=1 \
        PREPROD_AUTH_DRILL_COMMAND="tools/control-plane-auth-drill.sh" \
        tools/preprod-drill-closure.sh || true
  else
    now="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
      "preprod-drill-closure" \
      "SKIP" \
      "$now" \
      "$now" \
      "0" \
      "skipped (precheck/auth drill not passed)" \
      "$report_dir/preprod-drill-closure.log" >> "$records_file"
  fi
else
  now="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "preprod-drill-closure" \
    "SKIP" \
    "$now" \
    "$now" \
    "0" \
    "skipped (use --run-preprod-closure to enable)" \
    "$report_dir/preprod-drill-closure.log" >> "$records_file"
fi

set +e
python3 - <<'PY' "$records_file" "$report_path" "$summary_path" "$env_file" "$run_preprod_closure"
import json
import pathlib
from datetime import datetime, timezone
import sys

records_path = pathlib.Path(sys.argv[1])
report_path = pathlib.Path(sys.argv[2])
summary_path = pathlib.Path(sys.argv[3])
env_file = sys.argv[4]
run_preprod_closure = sys.argv[5] == "1"

steps = []
for line in records_path.read_text(encoding="utf-8").splitlines():
    step_name, status, started_at, ended_at, duration_ms, detail, log_path = line.split("\t")
    steps.append(
        {
            "step": step_name,
            "status": status,
            "startedAt": started_at,
            "endedAt": ended_at,
            "durationMs": int(duration_ms),
            "detail": detail,
            "logPath": log_path,
        }
    )

pass_count = sum(1 for item in steps if item["status"] == "PASS")
fail_count = sum(1 for item in steps if item["status"] == "FAIL")
skip_count = sum(1 for item in steps if item["status"] == "SKIP")
overall_pass = fail_count == 0 and len(steps) > 0

report = {
    "generatedAt": datetime.now(timezone.utc).isoformat(),
    "mode": "real-iam-env",
    "overallPass": overall_pass,
    "summary": {
        "stepCount": len(steps),
        "pass": pass_count,
        "fail": fail_count,
        "skip": skip_count,
    },
    "config": {
        "envFile": env_file,
        "runPreprodClosure": run_preprod_closure,
    },
    "steps": steps,
}
report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

lines = [
    "# Control-Plane Real IAM Drill Summary",
    "",
    f"- generatedAt: {report['generatedAt']}",
    f"- mode: {report['mode']}",
    f"- overallPass: {str(overall_pass).lower()}",
    f"- pass/fail/skip: {pass_count}/{fail_count}/{skip_count}",
    f"- envFile: {env_file}",
    f"- runPreprodClosure: {str(run_preprod_closure).lower()}",
    f"- reportPath: {report_path}",
    "",
    "| step | status | duration(ms) | detail |",
    "| --- | --- | ---: | --- |",
]
for step in steps:
    lines.append(
        "| {step} | {status} | {duration} | {detail} |".format(
            step=step["step"],
            status=step["status"],
            duration=step["durationMs"],
            detail=step["detail"].replace("|", "\\|"),
        )
    )

summary_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
print(summary_path.read_text(encoding="utf-8"))
sys.exit(0 if overall_pass else 1)
PY
status="$?"
set -e

echo "Real IAM drill report: $report_path"
echo "Real IAM drill summary: $summary_path"
exit "$status"
