#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

report_dir="${LOADTEST_REPORT_DIR:-$repo_root/build/reports/loadtest}"
scenario_input="${LOADTEST_SCENARIOS:-smoke baseline stress}"
scenario_input="${scenario_input//,/ }"
read -r -a scenarios <<< "$scenario_input"
if [[ "${#scenarios[@]}" -eq 0 ]]; then
  echo "No loadtest scenarios configured. Set LOADTEST_SCENARIOS." >&2
  exit 1
fi

legacy_report_path="${LOADTEST_REPORT_PATH:-$report_dir/gateway-pipeline-loadtest.json}"
aggregate_report_path="${LOADTEST_AGGREGATE_REPORT_PATH:-$report_dir/gateway-pipeline-loadtest-aggregate.json}"
summary_path="${LOADTEST_SUMMARY_PATH:-$report_dir/gateway-pipeline-loadtest-summary.md}"
capacity_target_scenario="${LOADTEST_CAPACITY_TARGET_SCENARIO:-}"

mkdir -p "$report_dir"
mkdir -p "$(dirname "$summary_path")"
mkdir -p "$(dirname "$aggregate_report_path")"
mkdir -p "$(dirname "$legacy_report_path")"

scenario_records_file="$(mktemp)"
trap 'rm -f "$scenario_records_file"' EXIT

default_sessions_for() {
  case "$1" in
    smoke) echo "12" ;;
    baseline) echo "24" ;;
    stress) echo "48" ;;
    *) echo "24" ;;
  esac
}

default_frames_for() {
  case "$1" in
    smoke) echo "120" ;;
    baseline) echo "180" ;;
    stress) echo "240" ;;
    *) echo "180" ;;
  esac
}

default_min_success_ratio_for() {
  case "$1" in
    smoke) echo "0.999" ;;
    baseline) echo "0.999" ;;
    stress) echo "0.995" ;;
    *) echo "0.999" ;;
  esac
}

default_max_p95_latency_ms_for() {
  case "$1" in
    smoke) echo "1200" ;;
    baseline) echo "1500" ;;
    stress) echo "2000" ;;
    *) echo "1500" ;;
  esac
}

default_min_throughput_fps_for() {
  case "$1" in
    smoke) echo "0" ;;
    baseline) echo "0" ;;
    stress) echo "0" ;;
    *) echo "0" ;;
  esac
}

resolve_value() {
  local scenario="$1"
  local key="$2"
  local fallback="$3"
  local scenario_token="${scenario^^}"
  scenario_token="${scenario_token//[^A-Z0-9]/_}"
  local scenario_key="LOADTEST_${scenario_token}_${key}"
  local scenario_value="${!scenario_key:-}"
  if [[ -n "$scenario_value" ]]; then
    echo "$scenario_value"
    return
  fi
  local global_key="LOADTEST_${key}"
  local global_value="${!global_key:-}"
  if [[ -n "$global_value" ]]; then
    echo "$global_value"
    return
  fi
  echo "$fallback"
}

echo "Running gateway pipeline loadtest harness across scenarios: ${scenarios[*]}"

for raw_scenario in "${scenarios[@]}"; do
  scenario="$(echo "$raw_scenario" | tr '[:upper:]' '[:lower:]')"
  if [[ ! "$scenario" =~ ^[a-z0-9._-]+$ ]]; then
    echo "Invalid scenario name: $scenario" >&2
    exit 1
  fi

  sessions="$(resolve_value "$scenario" "SESSIONS" "$(default_sessions_for "$scenario")")"
  frames_per_session="$(resolve_value "$scenario" "FRAMES_PER_SESSION" "$(default_frames_for "$scenario")")"
  min_success_ratio="$(resolve_value "$scenario" "MIN_SUCCESS_RATIO" "$(default_min_success_ratio_for "$scenario")")"
  max_p95_latency_ms="$(resolve_value "$scenario" "MAX_P95_LATENCY_MS" "$(default_max_p95_latency_ms_for "$scenario")")"
  min_throughput_fps="$(resolve_value "$scenario" "MIN_THROUGHPUT_FPS" "$(default_min_throughput_fps_for "$scenario")")"
  report_path="$report_dir/gateway-pipeline-loadtest-${scenario}.json"
  log_path="$report_dir/gateway-pipeline-loadtest-${scenario}.log"

  echo ""
  echo "[$scenario] sessions=$sessions frames_per_session=$frames_per_session min_success_ratio=$min_success_ratio max_p95_latency_ms=$max_p95_latency_ms min_throughput_fps=$min_throughput_fps"

  set +e
  ./gradlew \
    :services:speech-gateway:test \
    --rerun-tasks \
    --tests "com.kafkaasr.gateway.ws.downlink.GatewayPipelineLoadHarnessTests" \
    -Dgateway.loadtest.sessions="$sessions" \
    -Dgateway.loadtest.framesPerSession="$frames_per_session" \
    -Dgateway.loadtest.minSuccessRatio="$min_success_ratio" \
    -Dgateway.loadtest.maxP95LatencyMs="$max_p95_latency_ms" \
    -Dgateway.loadtest.report="$report_path" \
    2>&1 | tee "$log_path"
  exit_code="${PIPESTATUS[0]}"
  set -e

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$scenario" \
    "$sessions" \
    "$frames_per_session" \
    "$min_success_ratio" \
    "$max_p95_latency_ms" \
    "$min_throughput_fps" \
    "$exit_code" \
    "$report_path" \
    "$log_path" >> "$scenario_records_file"
done

set +e
python3 - <<'PY' "$scenario_records_file" "$aggregate_report_path" "$summary_path" "$legacy_report_path" "$capacity_target_scenario"
import json
import math
import pathlib
import shutil
from datetime import datetime, timezone
import sys

records_path = pathlib.Path(sys.argv[1])
aggregate_report_path = pathlib.Path(sys.argv[2])
summary_path = pathlib.Path(sys.argv[3])
legacy_report_path = pathlib.Path(sys.argv[4])
capacity_target_scenario = sys.argv[5].strip().lower()

scenarios = []
for line in records_path.read_text(encoding="utf-8").splitlines():
    parts = line.split("\t")
    if len(parts) != 9:
        continue
    scenario, sessions, frames_per_session, min_success_ratio, max_p95_latency_ms, min_throughput_fps, exit_code, report_path_raw, log_path_raw = parts
    report_path = pathlib.Path(report_path_raw)
    log_path = pathlib.Path(log_path_raw)
    report_exists = report_path.exists()
    report = None
    if report_exists:
        report = json.loads(report_path.read_text(encoding="utf-8"))
    slo_checks = report.get("sloChecks", {}) if isinstance(report, dict) else {}
    success_ratio_pass = bool(slo_checks.get("successRatioPass")) if report else False
    p95_latency_pass = bool(slo_checks.get("p95LatencyPass")) if report else False
    throughput_fps = report.get("throughputFramesPerSecond") if isinstance(report, dict) else None
    min_throughput_value = float(min_throughput_fps)
    if min_throughput_value <= 0:
        throughput_pass = True
    else:
        throughput_pass = isinstance(throughput_fps, (int, float)) and throughput_fps >= min_throughput_value
    gradle_pass = exit_code == "0"
    scenario_pass = gradle_pass and success_ratio_pass and p95_latency_pass and throughput_pass
    scenarios.append(
        {
            "scenario": scenario,
            "config": {
                "sessions": int(float(sessions)),
                "framesPerSession": int(float(frames_per_session)),
                "minSuccessRatio": float(min_success_ratio),
                "maxP95LatencyMs": float(max_p95_latency_ms),
                "minThroughputFps": min_throughput_value,
            },
            "artifacts": {
                "reportPath": str(report_path),
                "logPath": str(log_path),
            },
            "result": {
                "gradleExitCode": int(exit_code),
                "gradlePass": gradle_pass,
                "reportPresent": report_exists,
                "successRatioPass": success_ratio_pass,
                "p95LatencyPass": p95_latency_pass,
                "throughputPass": throughput_pass,
                "pass": scenario_pass,
            },
            "metrics": report or {},
        }
    )

overall_pass = all(item["result"]["pass"] for item in scenarios) if scenarios else False
passed_count = sum(1 for item in scenarios if item["result"]["pass"])
failed_count = len(scenarios) - passed_count

highest_passing = None
for item in scenarios:
    if item["result"]["pass"]:
        highest_passing = item

if not capacity_target_scenario and scenarios:
    capacity_target_scenario = scenarios[-1]["scenario"]
target_item = next((item for item in scenarios if item["scenario"] == capacity_target_scenario), None)
capacity_target_pass = bool(target_item and target_item["result"]["pass"])

capacity_ceiling = None
if highest_passing is not None:
    ceiling_throughput = highest_passing["metrics"].get("throughputFramesPerSecond")
    if isinstance(ceiling_throughput, (int, float)):
        ceiling_throughput = float(ceiling_throughput)
    else:
        ceiling_throughput = None
    capacity_ceiling = {
        "scenario": highest_passing["scenario"],
        "sessions": highest_passing["config"]["sessions"],
        "framesPerSession": highest_passing["config"]["framesPerSession"],
        "throughputFramesPerSecond": ceiling_throughput,
        "p95LatencyMs": highest_passing["metrics"].get("frameLatencyMsP95"),
        "successRatio": highest_passing["metrics"].get("successRatio"),
    }

aggregate_report = {
    "generatedAt": datetime.now(timezone.utc).isoformat(),
    "scenarioCount": len(scenarios),
    "passedCount": passed_count,
    "failedCount": failed_count,
    "overallPass": overall_pass,
    "capacityEvidence": {
        "targetScenario": capacity_target_scenario,
        "targetScenarioPass": capacity_target_pass,
        "highestPassingScenario": capacity_ceiling,
    },
    "scenarios": scenarios,
}
aggregate_report_path.write_text(
    json.dumps(aggregate_report, ensure_ascii=False, indent=2),
    encoding="utf-8",
)

legacy_source = None
for item in scenarios:
    if item["scenario"] == "baseline" and item["result"]["reportPresent"]:
        legacy_source = pathlib.Path(item["artifacts"]["reportPath"])
        break
if legacy_source is None:
    for item in scenarios:
        if item["result"]["reportPresent"]:
            legacy_source = pathlib.Path(item["artifacts"]["reportPath"])
            break
if legacy_source is not None and legacy_source.resolve() != legacy_report_path.resolve():
    shutil.copyfile(legacy_source, legacy_report_path)

def metric(item, key, default="n/a"):
    value = item["metrics"].get(key)
    if value is None:
        return default
    return value

lines = [
    "# Gateway Loadtest Summary",
    "",
    f"- generatedAt: {aggregate_report['generatedAt']}",
    f"- scenarioCount: {aggregate_report['scenarioCount']}",
    f"- passedCount: {aggregate_report['passedCount']}",
    f"- failedCount: {aggregate_report['failedCount']}",
    f"- overallPass: {str(aggregate_report['overallPass']).lower()}",
    f"- capacityTargetScenario: {aggregate_report['capacityEvidence']['targetScenario'] or 'n/a'}",
    f"- capacityTargetPass: {str(aggregate_report['capacityEvidence']['targetScenarioPass']).lower()}",
    f"- highestPassingScenario: {(aggregate_report['capacityEvidence']['highestPassingScenario'] or {}).get('scenario', 'n/a')}",
    f"- aggregateReportPath: {aggregate_report_path}",
]
if legacy_source is not None:
    lines.append(f"- legacyReportPath: {legacy_report_path}")
lines.extend(
    [
        "",
        "| scenario | sessions | framesPerSession | successRatio | p95(ms) | throughput(fps) | minThroughput(fps) | throughputGate | gatePass | gradleExit |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- | ---: |",
    ]
)
for item in scenarios:
    metrics = item["metrics"]
    success_ratio = metrics.get("successRatio")
    success_ratio_cell = f"{success_ratio:.6f}" if isinstance(success_ratio, (int, float)) else "n/a"
    throughput_value = metrics.get("throughputFramesPerSecond")
    throughput_cell = f"{throughput_value:.6f}" if isinstance(throughput_value, (int, float)) and not math.isnan(throughput_value) else "n/a"
    lines.append(
        "| {scenario} | {sessions} | {frames} | {success_ratio} | {p95} | {throughput} | {min_throughput} | {throughput_gate} | {gate_pass} | {exit_code} |".format(
            scenario=item["scenario"],
            sessions=item["config"]["sessions"],
            frames=item["config"]["framesPerSession"],
            success_ratio=success_ratio_cell,
            p95=metric(item, "frameLatencyMsP95"),
            throughput=throughput_cell,
            min_throughput=item["config"]["minThroughputFps"],
            throughput_gate="PASS" if item["result"]["throughputPass"] else "FAIL",
            gate_pass="PASS" if item["result"]["pass"] else "FAIL",
            exit_code=item["result"]["gradleExitCode"],
        )
    )

summary = "\n".join(lines) + "\n"
summary_path.write_text(summary, encoding="utf-8")
print(summary)
sys.exit(0 if overall_pass else 1)
PY
status="$?"
set -e

echo "Loadtest aggregate report: $aggregate_report_path"
echo "Loadtest summary: $summary_path"
echo "Legacy loadtest report: $legacy_report_path"
exit "$status"
