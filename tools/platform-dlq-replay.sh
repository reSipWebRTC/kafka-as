#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  tools/platform-dlq-replay.sh

Environment Variables:
  DLQ_REPLAY_BOOTSTRAP_SERVERS   Kafka bootstrap servers (default: localhost:9092)
  DLQ_REPLAY_INPUT_TOPIC         Source governance topic (default: platform.dlq)
  DLQ_REPLAY_CONSUME_OFFSET      beginning|end|<offset> (default: beginning)
  DLQ_REPLAY_MAX_EVENTS          Max platform.dlq events to scan (default: 200)
  DLQ_REPLAY_OUTPUT_MODE         source-topic|fixed-topic (default: source-topic)
  DLQ_REPLAY_FIXED_TOPIC         Target topic when OUTPUT_MODE=fixed-topic
  DLQ_REPLAY_KEY_FIELD           Field in raw payload for Kafka key (default: sessionId)
  DLQ_REPLAY_TENANT_FILTER       Comma-separated tenantId filter
  DLQ_REPLAY_SOURCE_TOPIC_FILTER Comma-separated sourceTopic filter
  DLQ_REPLAY_REASON_FILTER       Comma-separated reason filter
  DLQ_REPLAY_APPLY               0=dry-run, 1=publish replay (default: 0)
  DLQ_REPLAY_RATE_LIMIT_PER_SEC  Replay publish rate limit (default: 0, unlimited)
  DLQ_REPLAY_KCAT_BIN            kcat binary name/path (default: kcat)
  DLQ_REPLAY_EVENT_FILE          Optional JSONL input file (simulated mode)
  DLQ_REPLAY_REPORT_DIR          Report dir (default: build/reports/platform-dlq-replay)
  DLQ_REPLAY_REPORT_PATH         JSON report path
  DLQ_REPLAY_SUMMARY_PATH        Markdown summary path
  DLQ_REPLAY_SELECTED_PATH       Selected replay records JSONL path
  DLQ_REPLAY_FAILED_PATH         Failed/invalid records JSONL path
USAGE
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

bootstrap_servers="${DLQ_REPLAY_BOOTSTRAP_SERVERS:-localhost:9092}"
input_topic="${DLQ_REPLAY_INPUT_TOPIC:-platform.dlq}"
consume_offset="${DLQ_REPLAY_CONSUME_OFFSET:-beginning}"
max_events="${DLQ_REPLAY_MAX_EVENTS:-200}"
output_mode="${DLQ_REPLAY_OUTPUT_MODE:-source-topic}"
fixed_topic="${DLQ_REPLAY_FIXED_TOPIC:-}"
key_field="${DLQ_REPLAY_KEY_FIELD:-sessionId}"
tenant_filter_raw="${DLQ_REPLAY_TENANT_FILTER:-}"
source_topic_filter_raw="${DLQ_REPLAY_SOURCE_TOPIC_FILTER:-}"
reason_filter_raw="${DLQ_REPLAY_REASON_FILTER:-}"
apply_replay="${DLQ_REPLAY_APPLY:-0}"
rate_limit_per_sec="${DLQ_REPLAY_RATE_LIMIT_PER_SEC:-0}"
kcat_bin="${DLQ_REPLAY_KCAT_BIN:-kcat}"
event_file="${DLQ_REPLAY_EVENT_FILE:-}"

report_dir="${DLQ_REPLAY_REPORT_DIR:-$repo_root/build/reports/platform-dlq-replay}"
report_path="${DLQ_REPLAY_REPORT_PATH:-$report_dir/platform-dlq-replay.json}"
summary_path="${DLQ_REPLAY_SUMMARY_PATH:-$report_dir/platform-dlq-replay-summary.md}"
selected_path="${DLQ_REPLAY_SELECTED_PATH:-$report_dir/platform-dlq-replay-selected.jsonl}"
failed_path="${DLQ_REPLAY_FAILED_PATH:-$report_dir/platform-dlq-replay-failed.jsonl}"

if [[ ! "$max_events" =~ ^[0-9]+$ ]] || [[ "$max_events" -le 0 ]]; then
  echo "DLQ_REPLAY_MAX_EVENTS must be a positive integer." >&2
  exit 1
fi
if [[ "$output_mode" != "source-topic" && "$output_mode" != "fixed-topic" ]]; then
  echo "DLQ_REPLAY_OUTPUT_MODE must be source-topic or fixed-topic." >&2
  exit 1
fi
if [[ "$output_mode" == "fixed-topic" && -z "$fixed_topic" ]]; then
  echo "DLQ_REPLAY_FIXED_TOPIC is required when DLQ_REPLAY_OUTPUT_MODE=fixed-topic." >&2
  exit 1
fi
if [[ "$apply_replay" != "0" && "$apply_replay" != "1" ]]; then
  echo "DLQ_REPLAY_APPLY must be 0 or 1." >&2
  exit 1
fi
if [[ ! "$rate_limit_per_sec" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
  echo "DLQ_REPLAY_RATE_LIMIT_PER_SEC must be a non-negative number." >&2
  exit 1
fi

mkdir -p "$report_dir"
mkdir -p "$(dirname "$report_path")"
mkdir -p "$(dirname "$summary_path")"
mkdir -p "$(dirname "$selected_path")"
mkdir -p "$(dirname "$failed_path")"

raw_events_file="$(mktemp)"
tmp_summary_file="$(mktemp)"
trap 'rm -f "$raw_events_file" "$tmp_summary_file"' EXIT

mode="kafka"
if [[ -n "$event_file" ]]; then
  mode="simulated"
  if [[ ! -f "$event_file" ]]; then
    echo "DLQ_REPLAY_EVENT_FILE does not exist: $event_file" >&2
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

if [[ "$apply_replay" == "1" ]] && ! command -v "$kcat_bin" >/dev/null 2>&1; then
  echo "DLQ_REPLAY_APPLY=1 requires kcat binary: $kcat_bin" >&2
  exit 1
fi

set +e
python3 - <<'PY' \
  "$raw_events_file" \
  "$selected_path" \
  "$failed_path" \
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
  "$apply_replay" \
  "$rate_limit_per_sec" \
  "$kcat_bin" \
  "$event_file"
import hashlib
import json
import pathlib
import subprocess
import sys
import time
from datetime import datetime, timezone

(
    raw_events_path_raw,
    selected_path_raw,
    failed_path_raw,
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
    apply_replay_raw,
    rate_limit_raw,
    kcat_bin,
    event_file,
) = sys.argv[1:]

raw_events_path = pathlib.Path(raw_events_path_raw)
selected_path = pathlib.Path(selected_path_raw)
failed_path = pathlib.Path(failed_path_raw)
report_path = pathlib.Path(report_path_raw)
summary_path = pathlib.Path(summary_path_raw)

max_events = int(max_events_raw)
apply_replay = apply_replay_raw == "1"
rate_limit_per_sec = float(rate_limit_raw)

tenant_filter = {entry.strip() for entry in tenant_filter_raw.split(",") if entry.strip()}
source_filter = {entry.strip() for entry in source_filter_raw.split(",") if entry.strip()}
reason_filter = {entry.strip() for entry in reason_filter_raw.split(",") if entry.strip()}

records = []
failures = []
filtered_out = 0
parse_error_count = 0
invalid_event_count = 0

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

    if event.get("eventType") != "platform.dlq":
        invalid_event_count += 1
        failures.append(
            {
                "stage": "validate-platform-dlq",
                "error": "UNEXPECTED_EVENT_TYPE",
                "eventId": event.get("eventId", ""),
                "eventType": event.get("eventType", ""),
            }
        )
        continue

    payload = event.get("payload")
    if not isinstance(payload, dict):
        invalid_event_count += 1
        failures.append(
            {
                "stage": "validate-platform-dlq",
                "error": "MISSING_PAYLOAD",
                "eventId": event.get("eventId", ""),
            }
        )
        continue

    tenant_id = str(event.get("tenantId", "") or "")
    source_topic = str(payload.get("sourceTopic", "") or "")
    reason = str(payload.get("reason", "") or "")
    raw_payload = payload.get("rawPayload", "")

    if not source_topic or not isinstance(raw_payload, str) or not raw_payload:
        invalid_event_count += 1
        failures.append(
            {
                "stage": "validate-platform-dlq",
                "error": "MISSING_SOURCE_TOPIC_OR_RAW_PAYLOAD",
                "eventId": event.get("eventId", ""),
                "tenantId": tenant_id,
                "sourceTopic": source_topic,
            }
        )
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

    try:
        raw_payload_json = json.loads(raw_payload)
    except json.JSONDecodeError as exc:
        invalid_event_count += 1
        failures.append(
            {
                "stage": "parse-raw-payload",
                "error": "INVALID_RAW_PAYLOAD_JSON",
                "eventId": event.get("eventId", ""),
                "tenantId": tenant_id,
                "sourceTopic": source_topic,
                "errorDetail": str(exc),
            }
        )
        continue

    if not isinstance(raw_payload_json, dict):
        invalid_event_count += 1
        failures.append(
            {
                "stage": "parse-raw-payload",
                "error": "RAW_PAYLOAD_NOT_OBJECT",
                "eventId": event.get("eventId", ""),
                "tenantId": tenant_id,
                "sourceTopic": source_topic,
            }
        )
        continue

    target_topic = fixed_topic if output_mode == "fixed-topic" else source_topic
    key_candidate = (
        raw_payload_json.get(key_field)
        or raw_payload_json.get("sessionId")
        or raw_payload_json.get("tenantId")
        or raw_payload_json.get("idempotencyKey")
        or ""
    )
    record = {
        "eventId": str(event.get("eventId", "") or ""),
        "traceId": str(event.get("traceId", "") or ""),
        "tenantId": tenant_id,
        "sourceTopic": source_topic,
        "dlqTopic": str(payload.get("dlqTopic", "") or ""),
        "reason": reason,
        "targetTopic": target_topic,
        "key": str(key_candidate),
        "rawPayload": raw_payload,
        "rawPayloadSha256": hashlib.sha256(raw_payload.encode("utf-8")).hexdigest(),
    }
    records.append(record)

selected_path.write_text(
    "\n".join(json.dumps(item, ensure_ascii=False) for item in records) + ("\n" if records else ""),
    encoding="utf-8",
)

replay_attempted = 0
replay_success = 0
replay_failure = 0
replay_failure_records = []
replay_started_at = None
replay_ended_at = None

if apply_replay:
    replay_started_at = datetime.now(timezone.utc).isoformat()
    sleep_seconds = 1.0 / rate_limit_per_sec if rate_limit_per_sec > 0 else 0.0
    for item in records:
        replay_attempted += 1
        target_topic = item["targetTopic"]
        message = f"{item['key']}\x1f{item['rawPayload']}\n"
        cmd = [
            kcat_bin,
            "-b",
            bootstrap_servers,
            "-t",
            target_topic,
            "-P",
            "-K",
            "\x1f",
            "-q",
        ]
        proc = subprocess.run(cmd, input=message, text=True, capture_output=True)
        if proc.returncode == 0:
            replay_success += 1
        else:
            replay_failure += 1
            replay_item = {
                "stage": "replay-publish",
                "error": "KCAT_PUBLISH_FAILED",
                "eventId": item.get("eventId", ""),
                "tenantId": item.get("tenantId", ""),
                "sourceTopic": item.get("sourceTopic", ""),
                "targetTopic": target_topic,
                "stderr": (proc.stderr or "").strip(),
                "stdout": (proc.stdout or "").strip(),
                "exitCode": proc.returncode,
            }
            replay_failure_records.append(replay_item)
        if sleep_seconds > 0:
            time.sleep(sleep_seconds)
    replay_ended_at = datetime.now(timezone.utc).isoformat()

all_failures = failures + replay_failure_records
failed_path.write_text(
    "\n".join(json.dumps(item, ensure_ascii=False) for item in all_failures) + ("\n" if all_failures else ""),
    encoding="utf-8",
)

overall_pass = parse_error_count == 0 and invalid_event_count == 0
if apply_replay:
    overall_pass = overall_pass and replay_failure == 0

report = {
    "generatedAt": datetime.now(timezone.utc).isoformat(),
    "mode": mode,
    "applyReplay": apply_replay,
    "source": {
        "bootstrapServers": bootstrap_servers if mode == "kafka" else "",
        "topic": input_topic if mode == "kafka" else "",
        "consumeOffset": consume_offset if mode == "kafka" else "",
        "inputFile": event_file if mode == "simulated" else "",
        "maxEvents": max_events,
    },
    "filters": {
        "tenantIds": sorted(tenant_filter),
        "sourceTopics": sorted(source_filter),
        "reasons": sorted(reason_filter),
    },
    "output": {
        "mode": output_mode,
        "fixedTopic": fixed_topic if output_mode == "fixed-topic" else "",
        "keyField": key_field,
        "rateLimitPerSec": rate_limit_per_sec,
    },
    "counts": {
        "consumed": parse_error_count + invalid_event_count + filtered_out + len(records),
        "parseError": parse_error_count,
        "invalid": invalid_event_count,
        "filteredOut": filtered_out,
        "selected": len(records),
        "replayAttempted": replay_attempted,
        "replaySuccess": replay_success,
        "replayFailure": replay_failure,
    },
    "artifacts": {
        "selectedPath": str(selected_path),
        "failedPath": str(failed_path),
    },
    "replayWindow": {
        "startedAt": replay_started_at or "",
        "endedAt": replay_ended_at or "",
    },
    "overallPass": overall_pass,
}
report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

lines = [
    "# Platform DLQ Replay Summary",
    "",
    f"- generatedAt: {report['generatedAt']}",
    f"- mode: {mode}",
    f"- applyReplay: {str(apply_replay).lower()}",
    f"- consumed: {report['counts']['consumed']}",
    f"- parseError: {report['counts']['parseError']}",
    f"- invalid: {report['counts']['invalid']}",
    f"- filteredOut: {report['counts']['filteredOut']}",
    f"- selected: {report['counts']['selected']}",
    f"- replayAttempted: {report['counts']['replayAttempted']}",
    f"- replaySuccess: {report['counts']['replaySuccess']}",
    f"- replayFailure: {report['counts']['replayFailure']}",
    f"- overallPass: {str(report['overallPass']).lower()}",
    f"- reportPath: {report_path}",
    f"- selectedPath: {selected_path}",
    f"- failedPath: {failed_path}",
    "",
]
summary_path.write_text("\n".join(lines), encoding="utf-8")
print("\n".join(lines))

sys.exit(0 if overall_pass else 1)
PY
status="$?"
set -e

echo "Platform DLQ replay report: $report_path"
echo "Platform DLQ replay summary: $summary_path"
exit "$status"
