#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

sessions="${LOADTEST_SESSIONS:-24}"
frames_per_session="${LOADTEST_FRAMES_PER_SESSION:-180}"
min_success_ratio="${LOADTEST_MIN_SUCCESS_RATIO:-0.999}"
max_p95_latency_ms="${LOADTEST_MAX_P95_LATENCY_MS:-1500}"
report_path="${LOADTEST_REPORT_PATH:-$repo_root/build/reports/loadtest/gateway-pipeline-loadtest.json}"
summary_path="${LOADTEST_SUMMARY_PATH:-$repo_root/build/reports/loadtest/gateway-pipeline-loadtest-summary.md}"

mkdir -p "$(dirname "$report_path")"
mkdir -p "$(dirname "$summary_path")"

echo "Running gateway pipeline loadtest harness..."
echo "sessions=$sessions frames_per_session=$frames_per_session min_success_ratio=$min_success_ratio max_p95_latency_ms=$max_p95_latency_ms"

./gradlew \
  :services:speech-gateway:test \
  --rerun-tasks \
  --tests "com.kafkaasr.gateway.ws.downlink.GatewayPipelineLoadHarnessTests" \
  -Dgateway.loadtest.sessions="$sessions" \
  -Dgateway.loadtest.framesPerSession="$frames_per_session" \
  -Dgateway.loadtest.minSuccessRatio="$min_success_ratio" \
  -Dgateway.loadtest.maxP95LatencyMs="$max_p95_latency_ms" \
  -Dgateway.loadtest.report="$report_path"

python3 - <<'PY' "$report_path" "$summary_path"
import json
import pathlib
import sys

report_path = pathlib.Path(sys.argv[1])
summary_path = pathlib.Path(sys.argv[2])
report = json.loads(report_path.read_text(encoding="utf-8"))

summary = f"""# Gateway Loadtest Summary

- generatedAt: {report["generatedAt"]}
- sessions: {report["sessions"]}
- framesPerSession: {report["framesPerSession"]}
- totalFrames: {report["totalFrames"]}
- successfulFrames: {report["successfulFrames"]}
- failedFrames: {report["failedFrames"]}
- successRatio: {report["successRatio"]:.6f}
- throughputFramesPerSecond: {report["throughputFramesPerSecond"]}
- frameLatencyMsP50: {report["frameLatencyMsP50"]}
- frameLatencyMsP95: {report["frameLatencyMsP95"]}
- frameLatencyMsP99: {report["frameLatencyMsP99"]}
- frameLatencyMsMax: {report["frameLatencyMsMax"]}
- subtitlePartialCount: {report["subtitlePartialCount"]}
- subtitleFinalCount: {report["subtitleFinalCount"]}
- sessionClosedCount: {report["sessionClosedCount"]}
- outboundTotalCount: {report["outboundTotalCount"]}
- minSuccessRatioGate: {report["sloChecks"]["minSuccessRatio"]}
- maxP95LatencyMsGate: {report["sloChecks"]["maxP95LatencyMs"]}
- successRatioPass: {report["sloChecks"]["successRatioPass"]}
- p95LatencyPass: {report["sloChecks"]["p95LatencyPass"]}
"""
summary_path.write_text(summary, encoding="utf-8")
print(summary)
PY

echo "Loadtest report: $report_path"
echo "Loadtest summary: $summary_path"
