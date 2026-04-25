#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  tools/platform-dlq-auto-recovery.sh

Environment Variables:
  DLQ_AUTO_RECOVERY_BOOTSTRAP_SERVERS     Kafka bootstrap servers (default: localhost:9092)
  DLQ_AUTO_RECOVERY_INPUT_TOPIC           Source governance topic (default: platform.dlq)
  DLQ_AUTO_RECOVERY_CONSUME_OFFSET        beginning|end|<offset> (default: beginning)
  DLQ_AUTO_RECOVERY_MAX_EVENTS            Max platform.dlq events to scan (default: 200)
  DLQ_AUTO_RECOVERY_OUTPUT_MODE           source-topic|fixed-topic (default: source-topic)
  DLQ_AUTO_RECOVERY_FIXED_TOPIC           Target topic when OUTPUT_MODE=fixed-topic
  DLQ_AUTO_RECOVERY_KEY_FIELD             Field in raw payload for Kafka key (default: sessionId)
  DLQ_AUTO_RECOVERY_TENANT_FILTER         Comma-separated tenantId filter
  DLQ_AUTO_RECOVERY_SOURCE_TOPIC_FILTER   Comma-separated sourceTopic filter
  DLQ_AUTO_RECOVERY_REASON_FILTER         Comma-separated reason filter
  DLQ_AUTO_RECOVERY_SERVICE_FILTER        Comma-separated service filter
  DLQ_AUTO_RECOVERY_APPLY                 0=dry-run, 1=perform replay (default: 0)
  DLQ_AUTO_RECOVERY_RATE_LIMIT_PER_SEC    Replay publish rate limit (default: 0, unlimited)
  DLQ_AUTO_RECOVERY_KCAT_BIN              kcat binary name/path (default: kcat)
  DLQ_AUTO_RECOVERY_EVENT_FILE            Optional platform.dlq JSONL file (simulated mode)

  DLQ_AUTO_RECOVERY_STATE_PATH            Recovery ledger JSONL path
                                          (default: build/state/platform-dlq-auto-recovery-success.jsonl)
  DLQ_AUTO_RECOVERY_REPORT_DIR            Report dir (default: build/reports/platform-dlq-auto-recovery)
  DLQ_AUTO_RECOVERY_REPORT_PATH           JSON report path
  DLQ_AUTO_RECOVERY_SUMMARY_PATH          Markdown summary path
  DLQ_AUTO_RECOVERY_CANDIDATES_PATH       Candidate platform.dlq events JSONL path
  DLQ_AUTO_RECOVERY_SELECTED_PATH         Selected candidate records JSONL path
  DLQ_AUTO_RECOVERY_FAILED_PATH           Failed/invalid records JSONL path
  DLQ_AUTO_RECOVERY_PREFILTER_REPORT_PATH Prefilter JSON report path
  DLQ_AUTO_RECOVERY_REPLAY_REPORT_DIR     Sub-report dir for platform-dlq-replay
USAGE
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

bootstrap_servers="${DLQ_AUTO_RECOVERY_BOOTSTRAP_SERVERS:-localhost:9092}"
input_topic="${DLQ_AUTO_RECOVERY_INPUT_TOPIC:-platform.dlq}"
consume_offset="${DLQ_AUTO_RECOVERY_CONSUME_OFFSET:-beginning}"
max_events="${DLQ_AUTO_RECOVERY_MAX_EVENTS:-200}"
output_mode="${DLQ_AUTO_RECOVERY_OUTPUT_MODE:-source-topic}"
fixed_topic="${DLQ_AUTO_RECOVERY_FIXED_TOPIC:-}"
key_field="${DLQ_AUTO_RECOVERY_KEY_FIELD:-sessionId}"
tenant_filter_raw="${DLQ_AUTO_RECOVERY_TENANT_FILTER:-}"
source_topic_filter_raw="${DLQ_AUTO_RECOVERY_SOURCE_TOPIC_FILTER:-}"
reason_filter_raw="${DLQ_AUTO_RECOVERY_REASON_FILTER:-}"
service_filter_raw="${DLQ_AUTO_RECOVERY_SERVICE_FILTER:-}"
apply_recovery="${DLQ_AUTO_RECOVERY_APPLY:-0}"
rate_limit_per_sec="${DLQ_AUTO_RECOVERY_RATE_LIMIT_PER_SEC:-0}"
kcat_bin="${DLQ_AUTO_RECOVERY_KCAT_BIN:-kcat}"
event_file="${DLQ_AUTO_RECOVERY_EVENT_FILE:-}"

state_path="${DLQ_AUTO_RECOVERY_STATE_PATH:-$repo_root/build/state/platform-dlq-auto-recovery-success.jsonl}"
report_dir="${DLQ_AUTO_RECOVERY_REPORT_DIR:-$repo_root/build/reports/platform-dlq-auto-recovery}"
report_path="${DLQ_AUTO_RECOVERY_REPORT_PATH:-$report_dir/platform-dlq-auto-recovery.json}"
summary_path="${DLQ_AUTO_RECOVERY_SUMMARY_PATH:-$report_dir/platform-dlq-auto-recovery-summary.md}"
candidates_path="${DLQ_AUTO_RECOVERY_CANDIDATES_PATH:-$report_dir/platform-dlq-auto-recovery-candidates.jsonl}"
selected_path="${DLQ_AUTO_RECOVERY_SELECTED_PATH:-$report_dir/platform-dlq-auto-recovery-selected.jsonl}"
failed_path="${DLQ_AUTO_RECOVERY_FAILED_PATH:-$report_dir/platform-dlq-auto-recovery-failed.jsonl}"
prefilter_report_path="${DLQ_AUTO_RECOVERY_PREFILTER_REPORT_PATH:-$report_dir/platform-dlq-auto-recovery-prefilter.json}"
replay_report_dir="${DLQ_AUTO_RECOVERY_REPLAY_REPORT_DIR:-$report_dir/replay}"
replay_report_path="${replay_report_dir}/platform-dlq-replay.json"
replay_summary_path="${replay_report_dir}/platform-dlq-replay-summary.md"
replay_selected_path="${replay_report_dir}/platform-dlq-replay-selected.jsonl"
replay_failed_path="${replay_report_dir}/platform-dlq-replay-failed.jsonl"

if [[ ! "$max_events" =~ ^[0-9]+$ ]] || [[ "$max_events" -le 0 ]]; then
  echo "DLQ_AUTO_RECOVERY_MAX_EVENTS must be a positive integer." >&2
  exit 1
fi
if [[ "$output_mode" != "source-topic" && "$output_mode" != "fixed-topic" ]]; then
  echo "DLQ_AUTO_RECOVERY_OUTPUT_MODE must be source-topic or fixed-topic." >&2
  exit 1
fi
if [[ "$output_mode" == "fixed-topic" && -z "$fixed_topic" ]]; then
  echo "DLQ_AUTO_RECOVERY_FIXED_TOPIC is required when DLQ_AUTO_RECOVERY_OUTPUT_MODE=fixed-topic." >&2
  exit 1
fi
if [[ "$apply_recovery" != "0" && "$apply_recovery" != "1" ]]; then
  echo "DLQ_AUTO_RECOVERY_APPLY must be 0 or 1." >&2
  exit 1
fi
if [[ ! "$rate_limit_per_sec" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
  echo "DLQ_AUTO_RECOVERY_RATE_LIMIT_PER_SEC must be a non-negative number." >&2
  exit 1
fi

mkdir -p "$(dirname "$state_path")"
mkdir -p "$report_dir"
mkdir -p "$replay_report_dir"
mkdir -p "$(dirname "$report_path")"
mkdir -p "$(dirname "$summary_path")"
mkdir -p "$(dirname "$candidates_path")"
mkdir -p "$(dirname "$selected_path")"
mkdir -p "$(dirname "$failed_path")"
touch "$state_path"

raw_events_file="$(mktemp)"
tmp_summary_file="$(mktemp)"
trap 'rm -f "$raw_events_file" "$tmp_summary_file"' EXIT

mode="kafka"
if [[ -n "$event_file" ]]; then
  mode="simulated"
  if [[ ! -f "$event_file" ]]; then
    echo "DLQ_AUTO_RECOVERY_EVENT_FILE does not exist: $event_file" >&2
    exit 1
  fi
  cp "$event_file" "$raw_events_file"
else
  if ! command -v "$kcat_bin" >/dev/null 2>&1; then
    echo "kcat not found: $kcat_bin" >&2
    exit 1
  fi
  "$kcat_bin" \
    -b "$bootstrap_servers" \
    -t "$input_topic" \
    -C \
    -o "$consume_offset" \
    -c "$max_events" \
    -e \
    -q > "$raw_events_file"
fi

python3 - <<'PY' \
  "$raw_events_file" \
  "$state_path" \
  "$candidates_path" \
  "$selected_path" \
  "$failed_path" \
  "$prefilter_report_path" \
  "$max_events" \
  "$mode" \
  "$tenant_filter_raw" \
  "$source_topic_filter_raw" \
  "$reason_filter_raw" \
  "$service_filter_raw"
import hashlib
import json
import pathlib
import sys
from datetime import datetime, timezone

(
    raw_events_path_raw,
    state_path_raw,
    candidates_path_raw,
    selected_path_raw,
    failed_path_raw,
    prefilter_report_path_raw,
    max_events_raw,
    mode,
    tenant_filter_raw,
    source_filter_raw,
    reason_filter_raw,
    service_filter_raw,
) = sys.argv[1:]

raw_events_path = pathlib.Path(raw_events_path_raw)
state_path = pathlib.Path(state_path_raw)
candidates_path = pathlib.Path(candidates_path_raw)
selected_path = pathlib.Path(selected_path_raw)
failed_path = pathlib.Path(failed_path_raw)
prefilter_report_path = pathlib.Path(prefilter_report_path_raw)

max_events = int(max_events_raw)
tenant_filter = {entry.strip() for entry in tenant_filter_raw.split(",") if entry.strip()}
source_filter = {entry.strip() for entry in source_filter_raw.split(",") if entry.strip()}
reason_filter = {entry.strip() for entry in reason_filter_raw.split(",") if entry.strip()}
service_filter = {entry.strip() for entry in service_filter_raw.split(",") if entry.strip()}

state_event_ids = set()
for line in state_path.read_text(encoding="utf-8").splitlines():
    raw_line = line.strip()
    if not raw_line:
        continue
    try:
        item = json.loads(raw_line)
    except json.JSONDecodeError:
        continue
    if isinstance(item, dict):
        event_id = str(item.get("eventId", "") or "").strip()
        if event_id:
            state_event_ids.add(event_id)

candidate_events = []
selected_records = []
failures = []
parse_error_count = 0
invalid_event_count = 0
filtered_out = 0
already_recovered = 0

for index, line in enumerate(raw_events_path.read_text(encoding="utf-8").splitlines()):
    if index >= max_events:
        break
    raw_line = line.strip()
    if not raw_line:
        continue

    try:
        event = json.loads(raw_line)
    except json.JSONDecodeError as exc:
        parse_error_count += 1
        failures.append(
            {
                "stage": "parse-platform-dlq",
                "error": "INVALID_PLATFORM_DLQ_JSON",
                "errorDetail": str(exc),
                "rawLine": raw_line,
            }
        )
        continue

    event_type = str(event.get("eventType", "") or "")
    event_id = str(event.get("eventId", "") or "").strip()
    tenant_id = str(event.get("tenantId", "") or "")
    payload = event.get("payload")

    if event_type != "platform.dlq":
        invalid_event_count += 1
        failures.append(
            {
                "stage": "validate-platform-dlq",
                "error": "UNEXPECTED_EVENT_TYPE",
                "eventId": event_id,
                "eventType": event_type,
            }
        )
        continue
    if not event_id:
        invalid_event_count += 1
        failures.append(
            {
                "stage": "validate-platform-dlq",
                "error": "MISSING_EVENT_ID",
                "tenantId": tenant_id,
            }
        )
        continue
    if not isinstance(payload, dict):
        invalid_event_count += 1
        failures.append(
            {
                "stage": "validate-platform-dlq",
                "error": "MISSING_PAYLOAD",
                "eventId": event_id,
                "tenantId": tenant_id,
            }
        )
        continue

    source_topic = str(payload.get("sourceTopic", "") or "")
    reason = str(payload.get("reason", "") or "")
    service = str(payload.get("service", "") or "")
    raw_payload = payload.get("rawPayload", "")

    if not source_topic or not isinstance(raw_payload, str) or not raw_payload:
        invalid_event_count += 1
        failures.append(
            {
                "stage": "validate-platform-dlq",
                "error": "MISSING_SOURCE_TOPIC_OR_RAW_PAYLOAD",
                "eventId": event_id,
                "tenantId": tenant_id,
                "sourceTopic": source_topic,
            }
        )
        continue

    if event_id in state_event_ids:
        already_recovered += 1
        continue

    if tenant_filter and tenant_id not in tenant_filter:
        filtered_out += 1
        continue
    if source_filter and source_topic not in source_filter:
        filtered_out += 1
        continue
    if reason_filter and reason not in reason_filter:
        filtered_out += 1
        continue
    if service_filter and service not in service_filter:
        filtered_out += 1
        continue

    candidate_events.append(event)
    selected_records.append(
        {
            "eventId": event_id,
            "traceId": str(event.get("traceId", "") or ""),
            "tenantId": tenant_id,
            "service": service,
            "sourceTopic": source_topic,
            "dlqTopic": str(payload.get("dlqTopic", "") or ""),
            "reason": reason,
            "rawPayloadSha256": hashlib.sha256(raw_payload.encode("utf-8")).hexdigest(),
        }
    )

candidates_path.write_text(
    "\n".join(json.dumps(item, ensure_ascii=False) for item in candidate_events) + ("\n" if candidate_events else ""),
    encoding="utf-8",
)
selected_path.write_text(
    "\n".join(json.dumps(item, ensure_ascii=False) for item in selected_records) + ("\n" if selected_records else ""),
    encoding="utf-8",
)
failed_path.write_text(
    "\n".join(json.dumps(item, ensure_ascii=False) for item in failures) + ("\n" if failures else ""),
    encoding="utf-8",
)

counts = {
    "consumed": parse_error_count + invalid_event_count + filtered_out + already_recovered + len(candidate_events),
    "parseError": parse_error_count,
    "invalid": invalid_event_count,
    "filteredOut": filtered_out,
    "alreadyRecovered": already_recovered,
    "candidates": len(candidate_events),
}

prefilter_report = {
    "generatedAt": datetime.now(timezone.utc).isoformat(),
    "mode": mode,
    "filters": {
        "tenantIds": sorted(tenant_filter),
        "sourceTopics": sorted(source_filter),
        "reasons": sorted(reason_filter),
        "services": sorted(service_filter),
    },
    "state": {
        "path": str(state_path),
        "entries": len(state_event_ids),
    },
    "counts": counts,
    "overallPass": parse_error_count == 0 and invalid_event_count == 0,
}
prefilter_report_path.write_text(json.dumps(prefilter_report, ensure_ascii=False, indent=2), encoding="utf-8")
PY

candidate_count="$(python3 - <<'PY' "$prefilter_report_path"
import json
import pathlib
import sys
report = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
print(int(report.get("counts", {}).get("candidates", 0)))
PY
)"

replay_executed=0
replay_exit=0
if [[ "$candidate_count" -gt 0 ]]; then
  replay_executed=1
  set +e
  DLQ_REPLAY_BOOTSTRAP_SERVERS="$bootstrap_servers" \
  DLQ_REPLAY_INPUT_TOPIC="$input_topic" \
  DLQ_REPLAY_CONSUME_OFFSET="$consume_offset" \
  DLQ_REPLAY_MAX_EVENTS="$max_events" \
  DLQ_REPLAY_OUTPUT_MODE="$output_mode" \
  DLQ_REPLAY_FIXED_TOPIC="$fixed_topic" \
  DLQ_REPLAY_KEY_FIELD="$key_field" \
  DLQ_REPLAY_TENANT_FILTER="$tenant_filter_raw" \
  DLQ_REPLAY_SOURCE_TOPIC_FILTER="$source_topic_filter_raw" \
  DLQ_REPLAY_REASON_FILTER="$reason_filter_raw" \
  DLQ_REPLAY_APPLY="$apply_recovery" \
  DLQ_REPLAY_RATE_LIMIT_PER_SEC="$rate_limit_per_sec" \
  DLQ_REPLAY_KCAT_BIN="$kcat_bin" \
  DLQ_REPLAY_EVENT_FILE="$candidates_path" \
  DLQ_REPLAY_REPORT_DIR="$replay_report_dir" \
  DLQ_REPLAY_REPORT_PATH="$replay_report_path" \
  DLQ_REPLAY_SUMMARY_PATH="$replay_summary_path" \
  DLQ_REPLAY_SELECTED_PATH="$replay_selected_path" \
  DLQ_REPLAY_FAILED_PATH="$replay_failed_path" \
  tools/platform-dlq-replay.sh
  replay_exit="$?"
  set -e
fi

set +e
python3 - <<'PY' \
  "$prefilter_report_path" \
  "$candidates_path" \
  "$selected_path" \
  "$failed_path" \
  "$state_path" \
  "$replay_report_path" \
  "$replay_summary_path" \
  "$replay_selected_path" \
  "$replay_failed_path" \
  "$report_path" \
  "$summary_path" \
  "$bootstrap_servers" \
  "$input_topic" \
  "$consume_offset" \
  "$max_events" \
  "$mode" \
  "$output_mode" \
  "$fixed_topic" \
  "$key_field" \
  "$tenant_filter_raw" \
  "$source_topic_filter_raw" \
  "$reason_filter_raw" \
  "$service_filter_raw" \
  "$apply_recovery" \
  "$rate_limit_per_sec" \
  "$replay_executed" \
  "$replay_exit"
import json
import pathlib
import sys
from datetime import datetime, timezone

(
    prefilter_report_path_raw,
    candidates_path_raw,
    selected_path_raw,
    failed_path_raw,
    state_path_raw,
    replay_report_path_raw,
    replay_summary_path_raw,
    replay_selected_path_raw,
    replay_failed_path_raw,
    report_path_raw,
    summary_path_raw,
    bootstrap_servers,
    input_topic,
    consume_offset,
    max_events_raw,
    mode,
    output_mode,
    fixed_topic,
    key_field,
    tenant_filter_raw,
    source_filter_raw,
    reason_filter_raw,
    service_filter_raw,
    apply_recovery_raw,
    rate_limit_raw,
    replay_executed_raw,
    replay_exit_raw,
) = sys.argv[1:]

prefilter_report_path = pathlib.Path(prefilter_report_path_raw)
candidates_path = pathlib.Path(candidates_path_raw)
selected_path = pathlib.Path(selected_path_raw)
failed_path = pathlib.Path(failed_path_raw)
state_path = pathlib.Path(state_path_raw)
replay_report_path = pathlib.Path(replay_report_path_raw)
replay_summary_path = pathlib.Path(replay_summary_path_raw)
replay_selected_path = pathlib.Path(replay_selected_path_raw)
replay_failed_path = pathlib.Path(replay_failed_path_raw)
report_path = pathlib.Path(report_path_raw)
summary_path = pathlib.Path(summary_path_raw)

max_events = int(max_events_raw)
apply_recovery = apply_recovery_raw == "1"
replay_executed = replay_executed_raw == "1"
replay_exit = int(replay_exit_raw)
rate_limit_per_sec = float(rate_limit_raw)

prefilter_report = json.loads(prefilter_report_path.read_text(encoding="utf-8"))

def read_jsonl(path: pathlib.Path):
    rows = []
    if not path.exists():
        return rows
    for line in path.read_text(encoding="utf-8").splitlines():
        raw = line.strip()
        if not raw:
            continue
        try:
            rows.append(json.loads(raw))
        except json.JSONDecodeError:
            rows.append({"stage": "parse-jsonl", "error": "INVALID_JSONL", "rawLine": raw})
    return rows

prefilter_failures = read_jsonl(failed_path)
selected_records = read_jsonl(selected_path)
selected_by_event_id = {
    str(item.get("eventId", "") or ""): item
    for item in selected_records
    if str(item.get("eventId", "") or "")
}

replay_report = {}
if replay_report_path.exists():
    replay_report = json.loads(replay_report_path.read_text(encoding="utf-8"))

replay_selected = read_jsonl(replay_selected_path)
replay_failed = read_jsonl(replay_failed_path)
replay_failed_event_ids = {
    str(item.get("eventId", "") or "")
    for item in replay_failed
    if str(item.get("eventId", "") or "")
}
replay_selected_event_ids = {
    str(item.get("eventId", "") or "")
    for item in replay_selected
    if str(item.get("eventId", "") or "")
}
replay_success_event_ids = sorted(replay_selected_event_ids - replay_failed_event_ids)

state_records = read_jsonl(state_path)
state_event_ids = {
    str(item.get("eventId", "") or "")
    for item in state_records
    if isinstance(item, dict) and str(item.get("eventId", "") or "")
}
state_before = len(state_event_ids)
state_added = 0

if apply_recovery and replay_executed and replay_success_event_ids:
    append_lines = []
    now_iso = datetime.now(timezone.utc).isoformat()
    for event_id in replay_success_event_ids:
        if event_id in state_event_ids:
            continue
        selected_item = selected_by_event_id.get(event_id, {})
        append_lines.append(
            json.dumps(
                {
                    "eventId": event_id,
                    "tenantId": str(selected_item.get("tenantId", "") or ""),
                    "service": str(selected_item.get("service", "") or ""),
                    "sourceTopic": str(selected_item.get("sourceTopic", "") or ""),
                    "reason": str(selected_item.get("reason", "") or ""),
                    "recoveredAt": now_iso,
                },
                ensure_ascii=False,
            )
        )
        state_event_ids.add(event_id)
        state_added += 1
    if append_lines:
        with state_path.open("a", encoding="utf-8") as handle:
            handle.write("\n".join(append_lines) + "\n")

all_failures = list(prefilter_failures)
all_failures.extend(replay_failed)
failed_path.write_text(
    "\n".join(json.dumps(item, ensure_ascii=False) for item in all_failures) + ("\n" if all_failures else ""),
    encoding="utf-8",
)

replay_counts = replay_report.get("counts", {})
replay_selected_count = int(replay_counts.get("selected", 0)) if replay_report else 0
replay_attempted = int(replay_counts.get("replayAttempted", 0)) if replay_report else 0
replay_success = int(replay_counts.get("replaySuccess", 0)) if replay_report else 0
replay_failure = int(replay_counts.get("replayFailure", 0)) if replay_report else 0

overall_pass = bool(prefilter_report.get("overallPass", False))
if replay_executed:
    overall_pass = overall_pass and replay_exit == 0 and bool(replay_report.get("overallPass", False))
if apply_recovery and replay_executed:
    overall_pass = overall_pass and replay_failure == 0

tenant_filter = sorted({entry.strip() for entry in tenant_filter_raw.split(",") if entry.strip()})
source_filter = sorted({entry.strip() for entry in source_filter_raw.split(",") if entry.strip()})
reason_filter = sorted({entry.strip() for entry in reason_filter_raw.split(",") if entry.strip()})
service_filter = sorted({entry.strip() for entry in service_filter_raw.split(",") if entry.strip()})

report = {
    "generatedAt": datetime.now(timezone.utc).isoformat(),
    "mode": mode,
    "applyRecovery": apply_recovery,
    "source": {
        "bootstrapServers": bootstrap_servers if mode == "kafka" else "",
        "topic": input_topic if mode == "kafka" else "",
        "consumeOffset": consume_offset if mode == "kafka" else "",
        "maxEvents": max_events,
    },
    "filters": {
        "tenantIds": tenant_filter,
        "sourceTopics": source_filter,
        "reasons": reason_filter,
        "services": service_filter,
    },
    "output": {
        "mode": output_mode,
        "fixedTopic": fixed_topic if output_mode == "fixed-topic" else "",
        "keyField": key_field,
        "rateLimitPerSec": rate_limit_per_sec,
    },
    "counts": {
        "consumed": int(prefilter_report["counts"]["consumed"]),
        "parseError": int(prefilter_report["counts"]["parseError"]),
        "invalid": int(prefilter_report["counts"]["invalid"]),
        "filteredOut": int(prefilter_report["counts"]["filteredOut"]),
        "alreadyRecovered": int(prefilter_report["counts"]["alreadyRecovered"]),
        "candidates": int(prefilter_report["counts"]["candidates"]),
        "replaySelected": replay_selected_count,
        "replayAttempted": replay_attempted,
        "replaySuccess": replay_success,
        "replayFailure": replay_failure,
        "stateAdded": state_added,
    },
    "state": {
        "path": str(state_path),
        "entriesBefore": state_before,
        "entriesAfter": len(state_event_ids),
    },
    "artifacts": {
        "prefilterReportPath": str(prefilter_report_path),
        "candidatesPath": str(candidates_path),
        "selectedPath": str(selected_path),
        "failedPath": str(failed_path),
        "replayReportPath": str(replay_report_path) if replay_executed else "",
        "replaySummaryPath": str(replay_summary_path) if replay_executed else "",
    },
    "replay": {
        "executed": replay_executed,
        "exitCode": replay_exit if replay_executed else 0,
        "overallPass": bool(replay_report.get("overallPass", True)) if replay_executed else True,
    },
    "overallPass": overall_pass,
}
report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

lines = [
    "# Platform DLQ Auto-Recovery Summary",
    "",
    f"- generatedAt: {report['generatedAt']}",
    f"- mode: {mode}",
    f"- applyRecovery: {str(apply_recovery).lower()}",
    f"- consumed: {report['counts']['consumed']}",
    f"- parseError: {report['counts']['parseError']}",
    f"- invalid: {report['counts']['invalid']}",
    f"- filteredOut: {report['counts']['filteredOut']}",
    f"- alreadyRecovered: {report['counts']['alreadyRecovered']}",
    f"- candidates: {report['counts']['candidates']}",
    f"- replayExecuted: {str(report['replay']['executed']).lower()}",
    f"- replayAttempted: {report['counts']['replayAttempted']}",
    f"- replaySuccess: {report['counts']['replaySuccess']}",
    f"- replayFailure: {report['counts']['replayFailure']}",
    f"- stateAdded: {report['counts']['stateAdded']}",
    f"- stateEntries: {report['state']['entriesBefore']} -> {report['state']['entriesAfter']}",
    f"- overallPass: {str(report['overallPass']).lower()}",
    f"- reportPath: {report_path}",
    f"- selectedPath: {selected_path}",
    f"- failedPath: {failed_path}",
    f"- statePath: {state_path}",
]
summary_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
print("\n".join(lines))

sys.exit(0 if overall_pass else 1)
PY
status="$?"
set -e

echo "Platform DLQ auto-recovery report: $report_path"
echo "Platform DLQ auto-recovery summary: $summary_path"
exit "$status"
