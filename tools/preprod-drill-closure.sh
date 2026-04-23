#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

target="${PREPROD_TARGET:-preprod}"
report_dir="${PREPROD_REPORT_DIR:-$repo_root/build/reports/preprod-drill}"
report_path="${PREPROD_REPORT_PATH:-$report_dir/preprod-drill-closure.json}"
summary_path="${PREPROD_SUMMARY_PATH:-$report_dir/preprod-drill-closure-summary.md}"

loadtest_command="${PREPROD_LOADTEST_COMMAND:-tools/loadtest-alert-closure.sh}"
fault_drill_command="${PREPROD_FAULT_DRILL_COMMAND:-tools/fault-drill-closure.sh}"
auth_drill_command="${PREPROD_AUTH_DRILL_COMMAND:-}"
auth_drill_required="${PREPROD_AUTH_DRILL_REQUIRED:-0}"
loadtest_report_path="${PREPROD_LOADTEST_REPORT_PATH:-$repo_root/build/reports/loadtest/gateway-pipeline-loadtest-aggregate.json}"
fault_drill_report_path="${PREPROD_FAULT_DRILL_REPORT_PATH:-$repo_root/build/reports/fault-drill/fault-drill-closure.json}"
require_loadtest_evidence="${PREPROD_REQUIRE_LOADTEST_EVIDENCE:-1}"
require_fault_evidence="${PREPROD_REQUIRE_FAULT_EVIDENCE:-1}"
alertmanager_url="${PREPROD_ALERTMANAGER_URL:-http://localhost:9093}"
watch_alerts_raw="${PREPROD_WATCH_ALERTS:-GatewayWsErrorRateHigh,DownlinkErrorRateHigh,PipelineErrorRateHigh,AsrPipelineP95LatencyHigh,TranslationPipelineP95LatencyHigh,TtsPipelineP95LatencyHigh,KafkaConsumerLagHigh,ControlPlaneAuthDenyRateHigh,ControlPlaneExternalIamUnavailableSpike,ControlPlaneHybridFallbackSpike}"
recovery_timeout_seconds="${PREPROD_RECOVERY_TIMEOUT_SECONDS:-900}"
recovery_poll_seconds="${PREPROD_RECOVERY_POLL_SECONDS:-30}"
recovery_max_seconds="${PREPROD_RECOVERY_MAX_SECONDS:-$recovery_timeout_seconds}"
dry_run="${PREPROD_DRY_RUN:-0}"
skip_alert_capture="${PREPROD_SKIP_ALERT_CAPTURE:-0}"

if [[ "$auth_drill_required" == "1" && -z "$auth_drill_command" ]]; then
  echo "PREPROD_AUTH_DRILL_REQUIRED=1 but PREPROD_AUTH_DRILL_COMMAND is empty." >&2
  exit 1
fi

mkdir -p "$report_dir"
mkdir -p "$(dirname "$report_path")"
mkdir -p "$(dirname "$summary_path")"

phase_records="$(mktemp)"
snapshot_records="$(mktemp)"
recovery_records="$(mktemp)"
trap 'rm -f "$phase_records" "$snapshot_records" "$recovery_records"' EXIT

utc_now() {
  date -u +"%Y-%m-%dT%H:%M:%SZ"
}

capture_alert_snapshot() {
  local label="$1"
  local safe_label="${label//[^a-zA-Z0-9._-]/-}"
  local status_path="$report_dir/alertmanager-status-${safe_label}.json"
  local alerts_path="$report_dir/alertmanager-alerts-${safe_label}.json"
  local captured_at
  captured_at="$(utc_now)"
  local capture_ok=1

  if [[ "$dry_run" == "1" || "$skip_alert_capture" == "1" ]]; then
    printf '{}\n' > "$status_path"
    printf '[]\n' > "$alerts_path"
  else
    if ! curl -fsS "$alertmanager_url/api/v2/status" > "$status_path"; then
      capture_ok=0
      printf '{}\n' > "$status_path"
    fi
    if ! curl -fsS "$alertmanager_url/api/v2/alerts" > "$alerts_path"; then
      capture_ok=0
      printf '[]\n' > "$alerts_path"
    fi
    if ! python3 - <<'PY' "$status_path" "$alerts_path"
import json
import pathlib
import sys

for raw_path in sys.argv[1:]:
    path = pathlib.Path(raw_path)
    json.loads(path.read_text(encoding="utf-8"))
PY
    then
      capture_ok=0
      printf '{}\n' > "$status_path"
      printf '[]\n' > "$alerts_path"
    fi
  fi

  printf '%s\t%s\t%s\t%s\t%s\n' \
    "$label" \
    "$status_path" \
    "$alerts_path" \
    "$capture_ok" \
    "$captured_at" >> "$snapshot_records"
}

run_phase() {
  local phase="$1"
  local command="$2"
  local log_path="$report_dir/preprod-${phase}.log"
  local started_at
  started_at="$(utc_now)"
  local exit_code=0

  if [[ "$dry_run" == "1" ]]; then
    {
      echo "[DRY_RUN] phase=$phase"
      echo "[DRY_RUN] command=$command"
    } > "$log_path"
  else
    set +e
    bash -lc "$command" 2>&1 | tee "$log_path"
    exit_code="${PIPESTATUS[0]}"
    set -e
  fi

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

count_firing_alerts() {
  local alerts_path="$1"
  python3 - <<'PY' "$alerts_path" "$watch_alerts_raw"
import json
import pathlib
import sys

alerts_path = pathlib.Path(sys.argv[1])
watch = {entry.strip() for entry in sys.argv[2].split(",") if entry.strip()}
alerts = json.loads(alerts_path.read_text(encoding="utf-8"))

count = 0
for item in alerts:
    state = item.get("status", {}).get("state")
    if state not in ("active", "firing"):
        continue
    alertname = item.get("labels", {}).get("alertname", "")
    if watch and alertname not in watch:
        continue
    count += 1

print(count)
PY
}

run_recovery_poll() {
  if [[ "$dry_run" == "1" || "$skip_alert_capture" == "1" ]]; then
    return 0
  fi

  local deadline=$(( $(date +%s) + recovery_timeout_seconds ))
  local iteration=0

  while true; do
    local label="recovery-${iteration}"
    capture_alert_snapshot "$label"
    local alerts_path
    alerts_path="$(tail -n 1 "$snapshot_records" | cut -f3)"
    local firing_count
    firing_count="$(count_firing_alerts "$alerts_path")"
    printf '%s\t%s\t%s\t%s\n' \
      "$iteration" \
      "$(utc_now)" \
      "$firing_count" \
      "$alerts_path" >> "$recovery_records"

    if [[ "$firing_count" -eq 0 ]]; then
      return 0
    fi
    if [[ "$(date +%s)" -ge "$deadline" ]]; then
      return 1
    fi

    sleep "$recovery_poll_seconds"
    iteration=$((iteration + 1))
  done
}

echo "Running preprod drill closure for target=$target"
echo "report_dir=$report_dir"
if [[ "$dry_run" == "1" ]]; then
  echo "PREPROD_DRY_RUN=1 -> command execution and alert queries are simulated."
fi

capture_alert_snapshot "before"

loadtest_exit=0
set +e
run_phase "loadtest" "$loadtest_command"
loadtest_exit="$?"
set -e
capture_alert_snapshot "after-loadtest"

fault_drill_exit=0
set +e
run_phase "fault-drill" "$fault_drill_command"
fault_drill_exit="$?"
set -e
capture_alert_snapshot "after-fault-drill"

auth_drill_executed=0
if [[ -n "$auth_drill_command" ]]; then
  auth_drill_executed=1
  auth_drill_exit=0
  set +e
  run_phase "control-auth" "$auth_drill_command"
  auth_drill_exit="$?"
  set -e
  capture_alert_snapshot "after-control-auth"
fi

recovery_pass=0
recovery_reason="timeout"
set +e
run_recovery_poll
recovery_exit="$?"
set -e
if [[ "$recovery_exit" -eq 0 ]]; then
  recovery_pass=1
  if [[ "$dry_run" == "1" || "$skip_alert_capture" == "1" ]]; then
    recovery_reason="skipped"
  else
    recovery_reason="recovered"
  fi
fi

set +e
python3 - <<'PY' \
  "$target" \
  "$report_path" \
  "$summary_path" \
  "$phase_records" \
  "$snapshot_records" \
  "$recovery_records" \
  "$alertmanager_url" \
  "$watch_alerts_raw" \
  "$recovery_timeout_seconds" \
  "$recovery_poll_seconds" \
  "$recovery_max_seconds" \
  "$dry_run" \
  "$skip_alert_capture" \
  "$loadtest_report_path" \
  "$fault_drill_report_path" \
  "$require_loadtest_evidence" \
  "$require_fault_evidence" \
  "$auth_drill_command" \
  "$auth_drill_required" \
  "$auth_drill_executed" \
  "$recovery_pass" \
  "$recovery_reason"
import json
import pathlib
from datetime import datetime, timezone
import sys

(
    target,
    report_path_raw,
    summary_path_raw,
    phase_records_raw,
    snapshot_records_raw,
    recovery_records_raw,
    alertmanager_url,
    watch_alerts_raw,
    recovery_timeout_seconds,
    recovery_poll_seconds,
    recovery_max_seconds,
    dry_run_raw,
    skip_alert_capture_raw,
    loadtest_report_path_raw,
    fault_drill_report_path_raw,
    require_loadtest_evidence_raw,
    require_fault_evidence_raw,
    auth_drill_command,
    auth_drill_required_raw,
    auth_drill_executed_raw,
    recovery_pass_raw,
    recovery_reason,
) = sys.argv[1:]

report_path = pathlib.Path(report_path_raw)
summary_path = pathlib.Path(summary_path_raw)
phase_records_path = pathlib.Path(phase_records_raw)
snapshot_records_path = pathlib.Path(snapshot_records_raw)
recovery_records_path = pathlib.Path(recovery_records_raw)
loadtest_report_path = pathlib.Path(loadtest_report_path_raw)
fault_drill_report_path = pathlib.Path(fault_drill_report_path_raw)

dry_run = dry_run_raw == "1"
skip_alert_capture = skip_alert_capture_raw == "1"
auth_drill_required = auth_drill_required_raw == "1"
auth_drill_executed = auth_drill_executed_raw == "1"
recovery_pass = recovery_pass_raw == "1"
require_loadtest_evidence = require_loadtest_evidence_raw == "1"
require_fault_evidence = require_fault_evidence_raw == "1"
watch_alerts = [entry.strip() for entry in watch_alerts_raw.split(",") if entry.strip()]

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

snapshots = []
for line in snapshot_records_path.read_text(encoding="utf-8").splitlines():
    label, status_path, alerts_path, capture_ok, captured_at = line.split("\t")
    snapshots.append(
        {
            "label": label,
            "capturedAt": captured_at,
            "statusPath": status_path,
            "alertsPath": alerts_path,
            "captureOk": capture_ok == "1",
        }
    )

recovery_samples = []
for line in recovery_records_path.read_text(encoding="utf-8").splitlines():
    iteration, captured_at, firing_count, alerts_path = line.split("\t")
    recovery_samples.append(
        {
            "iteration": int(iteration),
            "capturedAt": captured_at,
            "firingCount": int(firing_count),
            "alertsPath": alerts_path,
        }
    )

phases_pass = all(item["result"]["pass"] for item in phases) if phases else False
capture_pass = all(item["captureOk"] for item in snapshots) if snapshots else False
if dry_run or skip_alert_capture:
    capture_pass = True

auth_drill_phase = next((item for item in phases if item["phase"] == "control-auth"), None)
auth_drill_pass = auth_drill_phase["result"]["pass"] if auth_drill_phase else not auth_drill_required

if dry_run or skip_alert_capture:
    recovery = {
        "pass": True,
        "reason": "skipped",
        "samples": recovery_samples,
    }
else:
    recovery = {
        "pass": recovery_pass,
        "reason": recovery_reason,
        "samples": recovery_samples,
    }

def parse_iso8601(raw: str):
    normalized = raw.strip()
    if normalized.endswith("Z"):
        normalized = normalized[:-1] + "+00:00"
    return datetime.fromisoformat(normalized)

recovery_duration_seconds = 0
if recovery_samples:
    start_at = parse_iso8601(recovery_samples[0]["capturedAt"])
    end_at = parse_iso8601(recovery_samples[-1]["capturedAt"])
    recovery_duration_seconds = max(0, int((end_at - start_at).total_seconds()))

recovery_slo_pass = recovery["pass"]
if not (dry_run or skip_alert_capture):
    recovery_slo_pass = recovery["pass"] and recovery_duration_seconds <= int(recovery_max_seconds)

loadtest_evidence = {
    "required": require_loadtest_evidence,
    "path": str(loadtest_report_path),
    "present": loadtest_report_path.exists(),
    "parseOk": False,
    "overallPass": False,
    "capacityTargetPass": False,
    "pass": False,
    "reason": "not_checked",
}
if loadtest_evidence["present"]:
    try:
        loadtest_payload = json.loads(loadtest_report_path.read_text(encoding="utf-8"))
        loadtest_evidence["parseOk"] = True
        loadtest_evidence["overallPass"] = bool(loadtest_payload.get("overallPass"))
        capacity_target_pass = loadtest_payload.get("capacityEvidence", {}).get("targetScenarioPass")
        if isinstance(capacity_target_pass, bool):
            loadtest_evidence["capacityTargetPass"] = capacity_target_pass
        else:
            loadtest_evidence["capacityTargetPass"] = loadtest_evidence["overallPass"]
        loadtest_evidence["pass"] = loadtest_evidence["overallPass"] and loadtest_evidence["capacityTargetPass"]
        loadtest_evidence["reason"] = "evaluated"
    except (json.JSONDecodeError, OSError):
        loadtest_evidence["parseOk"] = False

fault_evidence = {
    "required": require_fault_evidence,
    "path": str(fault_drill_report_path),
    "present": fault_drill_report_path.exists(),
    "parseOk": False,
    "overallPass": False,
    "pass": False,
    "reason": "not_checked",
}
if fault_evidence["present"]:
    try:
        fault_payload = json.loads(fault_drill_report_path.read_text(encoding="utf-8"))
        fault_evidence["parseOk"] = True
        fault_evidence["overallPass"] = bool(fault_payload.get("overallPass"))
        fault_evidence["pass"] = fault_evidence["overallPass"]
        fault_evidence["reason"] = "evaluated"
    except (json.JSONDecodeError, OSError):
        fault_evidence["parseOk"] = False

if dry_run:
    loadtest_evidence["pass"] = True
    loadtest_evidence["reason"] = "dry_run_skipped"
    fault_evidence["pass"] = True
    fault_evidence["reason"] = "dry_run_skipped"
    loadtest_requirement_pass = True
    fault_requirement_pass = True
else:
    loadtest_requirement_pass = (not require_loadtest_evidence) or loadtest_evidence["pass"]
    fault_requirement_pass = (not require_fault_evidence) or fault_evidence["pass"]

slo_evidence_pass = loadtest_requirement_pass and fault_requirement_pass and recovery_slo_pass

overall_pass = phases_pass and capture_pass and recovery["pass"] and auth_drill_pass and slo_evidence_pass

report = {
    "generatedAt": datetime.now(timezone.utc).isoformat(),
    "target": target,
    "config": {
        "alertmanagerUrl": alertmanager_url,
        "watchAlerts": watch_alerts,
        "recoveryTimeoutSeconds": int(recovery_timeout_seconds),
        "recoveryPollSeconds": int(recovery_poll_seconds),
        "recoveryMaxSeconds": int(recovery_max_seconds),
        "dryRun": dry_run,
        "skipAlertCapture": skip_alert_capture,
        "loadtestReportPath": str(loadtest_report_path),
        "faultDrillReportPath": str(fault_drill_report_path),
        "requireLoadtestEvidence": require_loadtest_evidence,
        "requireFaultEvidence": require_fault_evidence,
        "authDrill": {
            "command": auth_drill_command,
            "required": auth_drill_required,
            "executed": auth_drill_executed,
        },
    },
    "phases": phases,
    "snapshots": snapshots,
    "recovery": recovery,
    "sloEvidence": {
        "loadtest": loadtest_evidence,
        "faultDrill": fault_evidence,
        "recovery": {
            "durationSeconds": recovery_duration_seconds,
            "maxAllowedSeconds": int(recovery_max_seconds),
            "pass": recovery_slo_pass,
        },
        "pass": slo_evidence_pass,
    },
    "checks": {
        "phasesPass": phases_pass,
        "alertCapturePass": capture_pass,
        "recoveryPass": recovery["pass"],
        "recoverySloPass": recovery_slo_pass,
        "authDrillPass": auth_drill_pass,
        "loadtestEvidencePass": loadtest_requirement_pass,
        "faultEvidencePass": fault_requirement_pass,
        "sloEvidencePass": slo_evidence_pass,
    },
    "overallPass": overall_pass,
}

report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

lines = [
    "# Preprod Drill Closure Summary",
    "",
    f"- generatedAt: {report['generatedAt']}",
    f"- target: {target}",
    f"- overallPass: {str(overall_pass).lower()}",
    f"- phasesPass: {str(phases_pass).lower()}",
    f"- alertCapturePass: {str(capture_pass).lower()}",
    f"- recoveryPass: {str(recovery['pass']).lower()} ({recovery['reason']})",
    f"- recoverySloPass: {str(recovery_slo_pass).lower()} (duration={recovery_duration_seconds}s / max={int(recovery_max_seconds)}s)",
    f"- loadtestEvidencePass: {str(loadtest_requirement_pass).lower()}",
    f"- faultEvidencePass: {str(fault_requirement_pass).lower()}",
    f"- sloEvidencePass: {str(slo_evidence_pass).lower()}",
    f"- authDrillConfigured: {str(bool(auth_drill_command)).lower()}",
    f"- authDrillExecuted: {str(auth_drill_executed).lower()}",
    f"- authDrillPass: {str(auth_drill_pass).lower()}",
    f"- reportPath: {report_path}",
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

lines.extend(
    [
        "",
        "| snapshot | captureOk | alertsPath |",
        "| --- | --- | --- |",
    ]
)
for item in snapshots:
    lines.append(
        "| {label} | {ok} | `{alerts_path}` |".format(
            label=item["label"],
            ok="true" if item["captureOk"] else "false",
            alerts_path=item["alertsPath"],
        )
    )

lines.extend(
    [
        "",
        "| evidence | required | present | parseOk | pass | reason | path |",
        "| --- | --- | --- | --- | --- | --- | --- |",
        "| loadtest | {required} | {present} | {parse_ok} | {status} | {reason} | `{path}` |".format(
            required=str(loadtest_evidence["required"]).lower(),
            present=str(loadtest_evidence["present"]).lower(),
            parse_ok=str(loadtest_evidence["parseOk"]).lower(),
            status=str(loadtest_evidence["pass"]).lower(),
            reason=loadtest_evidence["reason"],
            path=loadtest_evidence["path"],
        ),
        "| fault-drill | {required} | {present} | {parse_ok} | {status} | {reason} | `{path}` |".format(
            required=str(fault_evidence["required"]).lower(),
            present=str(fault_evidence["present"]).lower(),
            parse_ok=str(fault_evidence["parseOk"]).lower(),
            status=str(fault_evidence["pass"]).lower(),
            reason=fault_evidence["reason"],
            path=fault_evidence["path"],
        ),
    ]
)

if recovery_samples:
    lines.extend(
        [
            "",
            "| recoveryIteration | firingCount | alertsPath |",
            "| ---: | ---: | --- |",
        ]
    )
    for sample in recovery_samples:
        lines.append(
            "| {iteration} | {count} | `{alerts_path}` |".format(
                iteration=sample["iteration"],
                count=sample["firingCount"],
                alerts_path=sample["alertsPath"],
            )
        )

summary = "\n".join(lines) + "\n"
summary_path.write_text(summary, encoding="utf-8")
print(summary)
sys.exit(0 if overall_pass else 1)
PY
status="$?"
set -e

echo "Preprod closure report: $report_path"
echo "Preprod closure summary: $summary_path"
exit "$status"
