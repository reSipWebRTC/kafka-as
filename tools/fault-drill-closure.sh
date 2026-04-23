#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

report_dir="${FAULT_DRILL_REPORT_DIR:-$repo_root/build/reports/fault-drill}"
scenario_input="${FAULT_DRILL_SCENARIOS:-asr-engine-fault-mapping translation-engine-fault-mapping tts-engine-fault-mapping asr-consumer-compensation translation-consumer-retry-dlq tts-consumer-retry-dlq}"
scenario_input="${scenario_input//,/ }"
read -r -a scenarios <<< "$scenario_input"
if [[ "${#scenarios[@]}" -eq 0 ]]; then
  echo "No fault-drill scenarios configured. Set FAULT_DRILL_SCENARIOS." >&2
  exit 1
fi

report_path="${FAULT_DRILL_REPORT_PATH:-$report_dir/fault-drill-closure.json}"
summary_path="${FAULT_DRILL_SUMMARY_PATH:-$report_dir/fault-drill-closure-summary.md}"

mkdir -p "$report_dir"
mkdir -p "$(dirname "$report_path")"
mkdir -p "$(dirname "$summary_path")"

scenario_records_file="$(mktemp)"
trap 'rm -f "$scenario_records_file"' EXIT

echo "Running fault-drill closure scenarios: ${scenarios[*]}"

for raw_scenario in "${scenarios[@]}"; do
  scenario="$(echo "$raw_scenario" | tr '[:upper:]' '[:lower:]')"
  if [[ ! "$scenario" =~ ^[a-z0-9._-]+$ ]]; then
    echo "Invalid scenario name: $scenario" >&2
    exit 1
  fi

  module=""
  tests=()
  case "$scenario" in
    asr-engine-fault-mapping)
      module=":services:asr-worker:test"
      tests=(
        "com.kafkaasr.asr.pipeline.FunasrAsrInferenceEngineTests.inferFailsFastWhenHealthCheckIsDown"
        "com.kafkaasr.asr.pipeline.FunasrAsrInferenceEngineTests.inferMapsTimeoutAsAsrTimeout"
        "com.kafkaasr.asr.pipeline.FunasrAsrInferenceEngineTests.inferRejectsWhenConcurrencyLimitReached"
      )
      ;;
    translation-engine-fault-mapping)
      module=":services:translation-worker:test"
      tests=(
        "com.kafkaasr.translation.pipeline.OpenaiTranslationEngineTests.translateFailsFastWhenHealthCheckIsDown"
        "com.kafkaasr.translation.pipeline.OpenaiTranslationEngineTests.translateMapsTimeoutAsTranslationTimeout"
        "com.kafkaasr.translation.pipeline.OpenaiTranslationEngineTests.translateRejectsWhenConcurrencyLimitReached"
      )
      ;;
    tts-engine-fault-mapping)
      module=":services:tts-orchestrator:test"
      tests=(
        "com.kafkaasr.tts.pipeline.HttpTtsSynthesisEngineTests.synthesizeFailsFastWhenHealthCheckIsDown"
        "com.kafkaasr.tts.pipeline.HttpTtsSynthesisEngineTests.synthesizeMapsTimeoutAsTtsTimeout"
        "com.kafkaasr.tts.pipeline.HttpTtsSynthesisEngineTests.synthesizeRejectsWhenConcurrencyLimitReached"
      )
      ;;
    asr-consumer-compensation)
      module=":services:asr-worker:test"
      tests=(
        "com.kafkaasr.asr.kafka.AudioIngressConsumerTests.emitsCompensationSignalAfterRepeatedFailureThreshold"
      )
      ;;
    translation-consumer-retry-dlq)
      module=":services:translation-worker:test"
      tests=(
        "com.kafkaasr.translation.kafka.AsrFinalConsumerTests.emitsCompensationSignalAfterRepeatedFailureThreshold"
        "com.kafkaasr.translation.kafka.AsrFinalConsumerTests.retriesWhenTranslationEngineExceptionIsRetryable"
        "com.kafkaasr.translation.kafka.AsrFinalConsumerTests.doesNotRetryWhenTranslationEngineExceptionIsNotRetryable"
      )
      ;;
    tts-consumer-retry-dlq)
      module=":services:tts-orchestrator:test"
      tests=(
        "com.kafkaasr.tts.kafka.TranslationResultConsumerTests.emitsCompensationSignalAfterRepeatedFailureThreshold"
        "com.kafkaasr.tts.kafka.TranslationResultConsumerTests.retriesWhenTtsSynthesisExceptionIsRetryable"
        "com.kafkaasr.tts.kafka.TranslationResultConsumerTests.doesNotRetryWhenTtsSynthesisExceptionIsNotRetryable"
      )
      ;;
    *)
      echo "Unsupported fault-drill scenario: $scenario" >&2
      echo "Supported: asr-engine-fault-mapping translation-engine-fault-mapping tts-engine-fault-mapping asr-consumer-compensation translation-consumer-retry-dlq tts-consumer-retry-dlq" >&2
      exit 1
      ;;
  esac

  log_path="$report_dir/fault-drill-${scenario}.log"
  started_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  start_ms="$(date +%s%3N)"

  cmd=(./gradlew "$module" --rerun-tasks)
  for test in "${tests[@]}"; do
    cmd+=(--tests "$test")
  done

  echo ""
  echo "[$scenario] module=$module tests=${#tests[@]}"
  set +e
  "${cmd[@]}" 2>&1 | tee "$log_path"
  exit_code="${PIPESTATUS[0]}"
  set -e

  end_ms="$(date +%s%3N)"
  duration_ms=$((end_ms - start_ms))
  ended_at="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  tests_csv="$(printf '%s;' "${tests[@]}")"
  tests_csv="${tests_csv%;}"

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$scenario" \
    "$module" \
    "$tests_csv" \
    "$exit_code" \
    "$duration_ms" \
    "$started_at" \
    "$ended_at" \
    "$log_path" >> "$scenario_records_file"
done

set +e
python3 - <<'PY' "$scenario_records_file" "$report_path" "$summary_path"
import json
import pathlib
from datetime import datetime, timezone
import sys

records_path = pathlib.Path(sys.argv[1])
report_path = pathlib.Path(sys.argv[2])
summary_path = pathlib.Path(sys.argv[3])

scenarios = []
for line in records_path.read_text(encoding="utf-8").splitlines():
    parts = line.split("\t")
    if len(parts) != 8:
        continue
    scenario, module, tests_csv, exit_code, duration_ms, started_at, ended_at, log_path = parts
    tests = [entry for entry in tests_csv.split(";") if entry]
    scenarios.append(
        {
            "scenario": scenario,
            "module": module,
            "tests": tests,
            "result": {
                "gradleExitCode": int(exit_code),
                "pass": exit_code == "0",
                "durationMs": int(duration_ms),
            },
            "startedAt": started_at,
            "endedAt": ended_at,
            "logPath": log_path,
        }
    )

overall_pass = all(item["result"]["pass"] for item in scenarios) if scenarios else False
passed_count = sum(1 for item in scenarios if item["result"]["pass"])
failed_count = len(scenarios) - passed_count

report = {
    "generatedAt": datetime.now(timezone.utc).isoformat(),
    "scenarioCount": len(scenarios),
    "passedCount": passed_count,
    "failedCount": failed_count,
    "overallPass": overall_pass,
    "scenarios": scenarios,
}
report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

lines = [
    "# Fault Drill Closure Summary",
    "",
    f"- generatedAt: {report['generatedAt']}",
    f"- scenarioCount: {report['scenarioCount']}",
    f"- passedCount: {report['passedCount']}",
    f"- failedCount: {report['failedCount']}",
    f"- overallPass: {str(report['overallPass']).lower()}",
    f"- reportPath: {report_path}",
    "",
    "| scenario | module | tests | duration(ms) | pass | gradleExit |",
    "| --- | --- | ---: | ---: | --- | ---: |",
]
for item in scenarios:
    lines.append(
        "| {scenario} | {module} | {tests} | {duration} | {status} | {exit_code} |".format(
            scenario=item["scenario"],
            module=item["module"],
            tests=len(item["tests"]),
            duration=item["result"]["durationMs"],
            status="PASS" if item["result"]["pass"] else "FAIL",
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

echo "Fault-drill report: $report_path"
echo "Fault-drill summary: $summary_path"
exit "$status"
