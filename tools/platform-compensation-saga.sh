#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  tools/platform-compensation-saga.sh

Environment Variables:
  COMPENSATION_SAGA_BOOTSTRAP_SERVERS       Kafka bootstrap servers (default: localhost:9092)
  COMPENSATION_SAGA_INPUT_TOPIC             Source governance topic (default: platform.dlq)
  COMPENSATION_SAGA_CONSUME_OFFSET          beginning|end|<offset> (default: beginning)
  COMPENSATION_SAGA_MAX_EVENTS              Max platform.dlq events to scan (default: 200)
  COMPENSATION_SAGA_OUTPUT_MODE             source-topic|fixed-topic (default: source-topic)
  COMPENSATION_SAGA_FIXED_TOPIC             Target topic when OUTPUT_MODE=fixed-topic
  COMPENSATION_SAGA_KEY_FIELD               Field in raw payload for Kafka key (default: sessionId)
  COMPENSATION_SAGA_TENANT_FILTER           Comma-separated tenantId filter
  COMPENSATION_SAGA_SOURCE_TOPIC_FILTER     Comma-separated sourceTopic filter
  COMPENSATION_SAGA_REASON_FILTER           Comma-separated reason filter
  COMPENSATION_SAGA_SERVICE_FILTER          Comma-separated service filter
  COMPENSATION_SAGA_ACTION_FILTER           Comma-separated action filter (replay|session-close|manual)
  COMPENSATION_SAGA_APPLY                   0=dry-run, 1=execute (default: 0)
  COMPENSATION_SAGA_RATE_LIMIT_PER_SEC      Replay publish rate limit (default: 0, unlimited)
  COMPENSATION_SAGA_KCAT_BIN                kcat binary name/path (default: kcat)
  COMPENSATION_SAGA_EVENT_FILE              Optional platform.dlq JSONL file (simulated mode)

  COMPENSATION_SAGA_SESSION_API_BASE_URL    Session-orchestrator base URL (default: http://localhost:8081)
  COMPENSATION_SAGA_SESSION_API_TIMEOUT_SEC Session stop timeout seconds (default: 3)

  COMPENSATION_SAGA_AUDIT_TOPIC             Audit topic (default: platform.audit)
  COMPENSATION_SAGA_PUBLISH_AUDIT           0=skip publish, 1=publish when APPLY=1 (default: 1)

  COMPENSATION_SAGA_STATE_PATH              Success ledger JSONL path
                                            (default: build/state/platform-compensation-saga-success.jsonl)
  COMPENSATION_SAGA_REPORT_DIR              Report dir (default: build/reports/platform-compensation-saga)
  COMPENSATION_SAGA_REPORT_PATH             JSON report path
  COMPENSATION_SAGA_SUMMARY_PATH            Markdown summary path
  COMPENSATION_SAGA_CANDIDATES_PATH         Candidate platform.dlq events JSONL path
  COMPENSATION_SAGA_SELECTED_PATH           Selected action records JSONL path
  COMPENSATION_SAGA_FAILED_PATH             Failed/invalid records JSONL path
  COMPENSATION_SAGA_REPLAY_EVENTS_PATH      Replay action event JSONL path
  COMPENSATION_SAGA_CLOSE_ACTIONS_PATH      Session-close action records JSONL path
  COMPENSATION_SAGA_MANUAL_ACTIONS_PATH     Manual action records JSONL path
  COMPENSATION_SAGA_AUDIT_EVENTS_PATH       Generated platform.audit events JSONL path
  COMPENSATION_SAGA_PREFILTER_REPORT_PATH   Prefilter JSON report path
  COMPENSATION_SAGA_REPLAY_REPORT_DIR       Sub-report dir for platform-dlq-replay
  COMPENSATION_SAGA_CLOSE_REPORT_PATH       Session-close execution report JSON path
  COMPENSATION_SAGA_CLOSE_FAILED_PATH       Session-close failure records JSONL path
USAGE
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

bootstrap_servers="${COMPENSATION_SAGA_BOOTSTRAP_SERVERS:-localhost:9092}"
input_topic="${COMPENSATION_SAGA_INPUT_TOPIC:-platform.dlq}"
consume_offset="${COMPENSATION_SAGA_CONSUME_OFFSET:-beginning}"
max_events="${COMPENSATION_SAGA_MAX_EVENTS:-200}"
output_mode="${COMPENSATION_SAGA_OUTPUT_MODE:-source-topic}"
fixed_topic="${COMPENSATION_SAGA_FIXED_TOPIC:-}"
key_field="${COMPENSATION_SAGA_KEY_FIELD:-sessionId}"
tenant_filter_raw="${COMPENSATION_SAGA_TENANT_FILTER:-}"
source_topic_filter_raw="${COMPENSATION_SAGA_SOURCE_TOPIC_FILTER:-}"
reason_filter_raw="${COMPENSATION_SAGA_REASON_FILTER:-}"
service_filter_raw="${COMPENSATION_SAGA_SERVICE_FILTER:-}"
action_filter_raw="${COMPENSATION_SAGA_ACTION_FILTER:-}"
apply_saga="${COMPENSATION_SAGA_APPLY:-0}"
rate_limit_per_sec="${COMPENSATION_SAGA_RATE_LIMIT_PER_SEC:-0}"
kcat_bin="${COMPENSATION_SAGA_KCAT_BIN:-kcat}"
event_file="${COMPENSATION_SAGA_EVENT_FILE:-}"

session_api_base_url="${COMPENSATION_SAGA_SESSION_API_BASE_URL:-http://localhost:8081}"
session_api_timeout_sec="${COMPENSATION_SAGA_SESSION_API_TIMEOUT_SEC:-3}"

audit_topic="${COMPENSATION_SAGA_AUDIT_TOPIC:-platform.audit}"
publish_audit="${COMPENSATION_SAGA_PUBLISH_AUDIT:-1}"

state_path="${COMPENSATION_SAGA_STATE_PATH:-$repo_root/build/state/platform-compensation-saga-success.jsonl}"
report_dir="${COMPENSATION_SAGA_REPORT_DIR:-$repo_root/build/reports/platform-compensation-saga}"
report_path="${COMPENSATION_SAGA_REPORT_PATH:-$report_dir/platform-compensation-saga.json}"
summary_path="${COMPENSATION_SAGA_SUMMARY_PATH:-$report_dir/platform-compensation-saga-summary.md}"
candidates_path="${COMPENSATION_SAGA_CANDIDATES_PATH:-$report_dir/platform-compensation-saga-candidates.jsonl}"
selected_path="${COMPENSATION_SAGA_SELECTED_PATH:-$report_dir/platform-compensation-saga-selected.jsonl}"
failed_path="${COMPENSATION_SAGA_FAILED_PATH:-$report_dir/platform-compensation-saga-failed.jsonl}"
replay_events_path="${COMPENSATION_SAGA_REPLAY_EVENTS_PATH:-$report_dir/platform-compensation-saga-replay-events.jsonl}"
close_actions_path="${COMPENSATION_SAGA_CLOSE_ACTIONS_PATH:-$report_dir/platform-compensation-saga-session-close.jsonl}"
manual_actions_path="${COMPENSATION_SAGA_MANUAL_ACTIONS_PATH:-$report_dir/platform-compensation-saga-manual.jsonl}"
audit_events_path="${COMPENSATION_SAGA_AUDIT_EVENTS_PATH:-$report_dir/platform-compensation-saga-audit-events.jsonl}"
prefilter_report_path="${COMPENSATION_SAGA_PREFILTER_REPORT_PATH:-$report_dir/platform-compensation-saga-prefilter.json}"
replay_report_dir="${COMPENSATION_SAGA_REPLAY_REPORT_DIR:-$report_dir/replay}"
close_report_path="${COMPENSATION_SAGA_CLOSE_REPORT_PATH:-$report_dir/platform-compensation-saga-session-close-report.json}"
close_failed_path="${COMPENSATION_SAGA_CLOSE_FAILED_PATH:-$report_dir/platform-compensation-saga-session-close-failed.jsonl}"

replay_report_path="${replay_report_dir}/platform-dlq-replay.json"
replay_summary_path="${replay_report_dir}/platform-dlq-replay-summary.md"
replay_selected_path="${replay_report_dir}/platform-dlq-replay-selected.jsonl"
replay_failed_path="${replay_report_dir}/platform-dlq-replay-failed.jsonl"

if [[ ! "$max_events" =~ ^[0-9]+$ ]] || [[ "$max_events" -le 0 ]]; then
  echo "COMPENSATION_SAGA_MAX_EVENTS must be a positive integer." >&2
  exit 1
fi
if [[ "$output_mode" != "source-topic" && "$output_mode" != "fixed-topic" ]]; then
  echo "COMPENSATION_SAGA_OUTPUT_MODE must be source-topic or fixed-topic." >&2
  exit 1
fi
if [[ "$output_mode" == "fixed-topic" && -z "$fixed_topic" ]]; then
  echo "COMPENSATION_SAGA_FIXED_TOPIC is required when COMPENSATION_SAGA_OUTPUT_MODE=fixed-topic." >&2
  exit 1
fi
if [[ "$apply_saga" != "0" && "$apply_saga" != "1" ]]; then
  echo "COMPENSATION_SAGA_APPLY must be 0 or 1." >&2
  exit 1
fi
if [[ "$publish_audit" != "0" && "$publish_audit" != "1" ]]; then
  echo "COMPENSATION_SAGA_PUBLISH_AUDIT must be 0 or 1." >&2
  exit 1
fi
if [[ ! "$rate_limit_per_sec" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
  echo "COMPENSATION_SAGA_RATE_LIMIT_PER_SEC must be a non-negative number." >&2
  exit 1
fi
if [[ ! "$session_api_timeout_sec" =~ ^[0-9]+([.][0-9]+)?$ ]]; then
  echo "COMPENSATION_SAGA_SESSION_API_TIMEOUT_SEC must be a non-negative number." >&2
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
mkdir -p "$(dirname "$replay_events_path")"
mkdir -p "$(dirname "$close_actions_path")"
mkdir -p "$(dirname "$manual_actions_path")"
mkdir -p "$(dirname "$audit_events_path")"
mkdir -p "$(dirname "$prefilter_report_path")"
mkdir -p "$(dirname "$close_report_path")"
mkdir -p "$(dirname "$close_failed_path")"
touch "$state_path"

raw_events_file="$(mktemp)"
trap 'rm -f "$raw_events_file"' EXIT

mode="kafka"
if [[ -n "$event_file" ]]; then
  mode="simulated"
  if [[ ! -f "$event_file" ]]; then
    echo "COMPENSATION_SAGA_EVENT_FILE does not exist: $event_file" >&2
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
  "$replay_events_path" \
  "$close_actions_path" \
  "$manual_actions_path" \
  "$prefilter_report_path" \
  "$max_events" \
  "$mode" \
  "$output_mode" \
  "$fixed_topic" \
  "$key_field" \
  "$tenant_filter_raw" \
  "$source_topic_filter_raw" \
  "$reason_filter_raw" \
  "$service_filter_raw" \
  "$action_filter_raw"
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
    replay_events_path_raw,
    close_actions_path_raw,
    manual_actions_path_raw,
    prefilter_report_path_raw,
    max_events_raw,
    mode,
    output_mode,
    fixed_topic,
    key_field,
    tenant_filter_raw,
    source_filter_raw,
    reason_filter_raw,
    service_filter_raw,
    action_filter_raw,
) = sys.argv[1:]

raw_events_path = pathlib.Path(raw_events_path_raw)
state_path = pathlib.Path(state_path_raw)
candidates_path = pathlib.Path(candidates_path_raw)
selected_path = pathlib.Path(selected_path_raw)
failed_path = pathlib.Path(failed_path_raw)
replay_events_path = pathlib.Path(replay_events_path_raw)
close_actions_path = pathlib.Path(close_actions_path_raw)
manual_actions_path = pathlib.Path(manual_actions_path_raw)
prefilter_report_path = pathlib.Path(prefilter_report_path_raw)

max_events = int(max_events_raw)
tenant_filter = {entry.strip() for entry in tenant_filter_raw.split(",") if entry.strip()}
source_filter = {entry.strip() for entry in source_filter_raw.split(",") if entry.strip()}
reason_filter = {entry.strip() for entry in reason_filter_raw.split(",") if entry.strip()}
service_filter = {entry.strip() for entry in service_filter_raw.split(",") if entry.strip()}
action_filter = {entry.strip() for entry in action_filter_raw.split(",") if entry.strip()}

manual_reasons = {"NON_RETRYABLE", "DESERIALIZATION_FAILED", "UNKNOWN_EVENT_VERSION"}
replay_reasons = {"MAX_RETRIES_EXCEEDED", "PROCESSING_ERROR"}
close_source_topics = {
    "asr.partial",
    "translation.result",
    "tts.chunk",
    "tts.ready",
    "command.result",
}

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

def normalize_source_topic_for_reason(value: str) -> str:
    return "".join(ch if ch.isalnum() else "_" for ch in value).strip("_") or "unknown"

def classify_action(reason: str, source_topic: str, session_id: str):
    if reason in manual_reasons:
        return "manual", "MANUAL_INTERVENTION_REQUIRED"
    if reason in replay_reasons and source_topic in close_source_topics:
        if session_id:
            return "session-close", "SESSION_CLOSE_REQUIRED"
        return "manual", "SESSION_ID_REQUIRED_FOR_CLOSE"
    if reason in replay_reasons:
        return "replay", "REPLAY_REQUIRED"
    return "manual", "UNCLASSIFIED_REASON"

candidate_events = []
selected_actions = []
replay_events = []
close_actions = []
manual_actions = []
failures = []

parse_error_count = 0
invalid_event_count = 0
filtered_out = 0
already_processed = 0

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
    trace_id = str(event.get("traceId", "") or "")
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
            }
        )
        continue

    source_topic = str(payload.get("sourceTopic", "") or "")
    reason = str(payload.get("reason", "") or "")
    service = str(payload.get("service", "") or "")
    dlq_topic = str(payload.get("dlqTopic", "") or "")
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
        already_processed += 1
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

    try:
        raw_payload_json = json.loads(raw_payload)
    except json.JSONDecodeError as exc:
        invalid_event_count += 1
        failures.append(
            {
                "stage": "parse-raw-payload",
                "error": "INVALID_RAW_PAYLOAD_JSON",
                "eventId": event_id,
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
                "eventId": event_id,
                "tenantId": tenant_id,
                "sourceTopic": source_topic,
            }
        )
        continue

    session_id = str(
        raw_payload_json.get("sessionId")
        or event.get("sessionId")
        or ""
    )
    action_type, action_reason_code = classify_action(reason, source_topic, session_id)
    if action_filter and action_type not in action_filter:
        filtered_out += 1
        continue

    target_topic = fixed_topic if output_mode == "fixed-topic" else source_topic
    key_candidate = (
        raw_payload_json.get(key_field)
        or raw_payload_json.get("sessionId")
        or raw_payload_json.get("tenantId")
        or raw_payload_json.get("idempotencyKey")
        or ""
    )

    candidate_events.append(event)
    action = {
        "eventId": event_id,
        "traceId": trace_id,
        "tenantId": tenant_id,
        "sessionId": session_id,
        "service": service,
        "sourceTopic": source_topic,
        "dlqTopic": dlq_topic,
        "reason": reason,
        "actionType": action_type,
        "actionReasonCode": action_reason_code,
        "targetTopic": target_topic if action_type == "replay" else "",
        "key": str(key_candidate),
        "closeReason": "compensation.saga.%s.%s"
        % (normalize_source_topic_for_reason(source_topic), reason.lower() if reason else "unknown"),
        "rawPayload": raw_payload,
        "rawPayloadSha256": hashlib.sha256(raw_payload.encode("utf-8")).hexdigest(),
    }
    selected_actions.append(action)
    if action_type == "replay":
        replay_events.append(event)
    elif action_type == "session-close":
        close_actions.append(action)
    else:
        manual_actions.append(action)

def write_jsonl(path: pathlib.Path, rows):
    path.write_text(
        "\n".join(json.dumps(item, ensure_ascii=False) for item in rows) + ("\n" if rows else ""),
        encoding="utf-8",
    )

write_jsonl(candidates_path, candidate_events)
write_jsonl(selected_path, selected_actions)
write_jsonl(failed_path, failures)
write_jsonl(replay_events_path, replay_events)
write_jsonl(close_actions_path, close_actions)
write_jsonl(manual_actions_path, manual_actions)

counts = {
    "consumed": parse_error_count + invalid_event_count + filtered_out + already_processed + len(selected_actions),
    "parseError": parse_error_count,
    "invalid": invalid_event_count,
    "filteredOut": filtered_out,
    "alreadyProcessed": already_processed,
    "candidates": len(selected_actions),
    "replayCandidates": len(replay_events),
    "closeCandidates": len(close_actions),
    "manualCandidates": len(manual_actions),
}
prefilter_report = {
    "generatedAt": datetime.now(timezone.utc).isoformat(),
    "mode": mode,
    "filters": {
        "tenantIds": sorted(tenant_filter),
        "sourceTopics": sorted(source_filter),
        "reasons": sorted(reason_filter),
        "services": sorted(service_filter),
        "actions": sorted(action_filter),
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

replay_candidates="$(python3 - <<'PY' "$prefilter_report_path"
import json
import pathlib
import sys
report = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
print(int(report.get("counts", {}).get("replayCandidates", 0)))
PY
)"
close_candidates="$(python3 - <<'PY' "$prefilter_report_path"
import json
import pathlib
import sys
report = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
print(int(report.get("counts", {}).get("closeCandidates", 0)))
PY
)"

replay_executed=0
replay_exit=0
if [[ "$replay_candidates" -gt 0 ]]; then
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
  DLQ_REPLAY_APPLY="$apply_saga" \
  DLQ_REPLAY_RATE_LIMIT_PER_SEC="$rate_limit_per_sec" \
  DLQ_REPLAY_KCAT_BIN="$kcat_bin" \
  DLQ_REPLAY_EVENT_FILE="$replay_events_path" \
  DLQ_REPLAY_REPORT_DIR="$replay_report_dir" \
  DLQ_REPLAY_REPORT_PATH="$replay_report_path" \
  DLQ_REPLAY_SUMMARY_PATH="$replay_summary_path" \
  DLQ_REPLAY_SELECTED_PATH="$replay_selected_path" \
  DLQ_REPLAY_FAILED_PATH="$replay_failed_path" \
  tools/platform-dlq-replay.sh
  replay_exit="$?"
  set -e
fi

python3 - <<'PY' \
  "$close_actions_path" \
  "$close_report_path" \
  "$close_failed_path" \
  "$apply_saga" \
  "$session_api_base_url" \
  "$session_api_timeout_sec"
import json
import pathlib
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

(
    close_actions_path_raw,
    close_report_path_raw,
    close_failed_path_raw,
    apply_saga_raw,
    session_api_base_url,
    timeout_raw,
) = sys.argv[1:]

close_actions_path = pathlib.Path(close_actions_path_raw)
close_report_path = pathlib.Path(close_report_path_raw)
close_failed_path = pathlib.Path(close_failed_path_raw)

apply_saga = apply_saga_raw == "1"
timeout = float(timeout_raw)

actions = []
for line in close_actions_path.read_text(encoding="utf-8").splitlines():
    raw = line.strip()
    if not raw:
        continue
    try:
        item = json.loads(raw)
    except json.JSONDecodeError:
        continue
    if isinstance(item, dict):
        actions.append(item)

failed = []
results = []
attempted = 0
success = 0
failure = 0

def execute_close(session_id: str, trace_id: str, close_reason: str):
    endpoint = session_api_base_url.rstrip("/") + "/api/v1/sessions/" + urllib.parse.quote(session_id, safe="") + ":stop"
    request_payload = {
        "traceId": trace_id or ("saga-close-" + datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")),
        "reason": close_reason,
    }
    data = json.dumps(request_payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        endpoint,
        method="POST",
        data=data,
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        body = response.read().decode("utf-8")
        payload = {}
        if body.strip():
            try:
                payload = json.loads(body)
            except json.JSONDecodeError:
                payload = {}
        return response.status, payload

for action in actions:
    event_id = str(action.get("eventId", "") or "")
    session_id = str(action.get("sessionId", "") or "")
    close_reason = str(action.get("closeReason", "") or "compensation.saga.close")
    trace_id = str(action.get("traceId", "") or "")

    if not session_id:
        failure += 1
        failed.append(
            {
                "stage": "session-close",
                "error": "MISSING_SESSION_ID",
                "eventId": event_id,
            }
        )
        results.append({"eventId": event_id, "outcome": "FAILED", "code": "MISSING_SESSION_ID"})
        continue

    if not apply_saga:
        results.append({"eventId": event_id, "outcome": "SKIPPED", "code": "DRY_RUN"})
        continue

    attempted += 1
    try:
        status_code, payload = execute_close(session_id, trace_id, close_reason)
        stopped = bool(payload.get("stopped", False))
        success += 1
        results.append(
            {
                "eventId": event_id,
                "outcome": "SUCCESS",
                "code": "OK",
                "statusCode": status_code,
                "stopped": stopped,
            }
        )
    except urllib.error.HTTPError as exc:
        failure += 1
        body = ""
        try:
            body = exc.read().decode("utf-8")
        except Exception:
            body = ""
        failed_item = {
            "stage": "session-close",
            "error": "HTTP_ERROR",
            "eventId": event_id,
            "statusCode": exc.code,
            "body": body.strip(),
        }
        failed.append(failed_item)
        results.append({"eventId": event_id, "outcome": "FAILED", "code": "HTTP_ERROR", "statusCode": exc.code})
    except Exception as exc:
        failure += 1
        failed_item = {
            "stage": "session-close",
            "error": "REQUEST_FAILED",
            "eventId": event_id,
            "errorDetail": str(exc),
        }
        failed.append(failed_item)
        results.append({"eventId": event_id, "outcome": "FAILED", "code": "REQUEST_FAILED"})

close_failed_path.write_text(
    "\n".join(json.dumps(item, ensure_ascii=False) for item in failed) + ("\n" if failed else ""),
    encoding="utf-8",
)
close_report = {
    "generatedAt": datetime.now(timezone.utc).isoformat(),
    "apply": apply_saga,
    "counts": {
        "candidates": len(actions),
        "attempted": attempted,
        "success": success,
        "failure": failure,
    },
    "results": results,
    "overallPass": failure == 0,
}
close_report_path.write_text(json.dumps(close_report, ensure_ascii=False, indent=2), encoding="utf-8")
PY

set +e
python3 - <<'PY' \
  "$prefilter_report_path" \
  "$selected_path" \
  "$failed_path" \
  "$state_path" \
  "$replay_report_path" \
  "$replay_selected_path" \
  "$replay_failed_path" \
  "$close_report_path" \
  "$close_failed_path" \
  "$audit_events_path" \
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
  "$action_filter_raw" \
  "$apply_saga" \
  "$rate_limit_per_sec" \
  "$replay_executed" \
  "$replay_exit" \
  "$publish_audit" \
  "$audit_topic" \
  "$kcat_bin"
import json
import pathlib
import subprocess
import sys
import uuid
from datetime import datetime, timezone

(
    prefilter_report_path_raw,
    selected_path_raw,
    failed_path_raw,
    state_path_raw,
    replay_report_path_raw,
    replay_selected_path_raw,
    replay_failed_path_raw,
    close_report_path_raw,
    close_failed_path_raw,
    audit_events_path_raw,
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
    action_filter_raw,
    apply_saga_raw,
    rate_limit_raw,
    replay_executed_raw,
    replay_exit_raw,
    publish_audit_raw,
    audit_topic,
    kcat_bin,
) = sys.argv[1:]

prefilter_report_path = pathlib.Path(prefilter_report_path_raw)
selected_path = pathlib.Path(selected_path_raw)
failed_path = pathlib.Path(failed_path_raw)
state_path = pathlib.Path(state_path_raw)
replay_report_path = pathlib.Path(replay_report_path_raw)
replay_selected_path = pathlib.Path(replay_selected_path_raw)
replay_failed_path = pathlib.Path(replay_failed_path_raw)
close_report_path = pathlib.Path(close_report_path_raw)
close_failed_path = pathlib.Path(close_failed_path_raw)
audit_events_path = pathlib.Path(audit_events_path_raw)
report_path = pathlib.Path(report_path_raw)
summary_path = pathlib.Path(summary_path_raw)

max_events = int(max_events_raw)
apply_saga = apply_saga_raw == "1"
rate_limit_per_sec = float(rate_limit_raw)
replay_executed = replay_executed_raw == "1"
replay_exit = int(replay_exit_raw)
publish_audit = publish_audit_raw == "1"

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

selected_actions = read_jsonl(selected_path)
prefilter_failures = read_jsonl(failed_path)
replay_selected = read_jsonl(replay_selected_path)
replay_failed = read_jsonl(replay_failed_path)
close_failed = read_jsonl(close_failed_path)

replay_report = {}
if replay_report_path.exists():
    replay_report = json.loads(replay_report_path.read_text(encoding="utf-8"))
close_report = {}
if close_report_path.exists():
    close_report = json.loads(close_report_path.read_text(encoding="utf-8"))

close_result_by_event_id = {}
for item in close_report.get("results", []):
    event_id = str(item.get("eventId", "") or "")
    if event_id:
        close_result_by_event_id[event_id] = item

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

def normalize_action_outcome(action):
    event_id = str(action.get("eventId", "") or "")
    action_type = str(action.get("actionType", "") or "")
    if action_type == "replay":
        if not apply_saga:
            return "SKIPPED", "DRY_RUN"
        if event_id in replay_failed_event_ids:
            return "FAILED", "REPLAY_FAILED"
        if event_id in replay_selected_event_ids:
            return "SUCCESS", "OK"
        return "SKIPPED", "NOT_SELECTED"
    if action_type == "session-close":
        result = close_result_by_event_id.get(event_id)
        if not result:
            return ("SKIPPED", "DRY_RUN") if not apply_saga else ("FAILED", "MISSING_RESULT")
        return str(result.get("outcome", "FAILED") or "FAILED"), str(result.get("code", "UNKNOWN") or "UNKNOWN")
    return "SKIPPED", "MANUAL_INTERVENTION_REQUIRED"

def build_audit_event(action, outcome, code):
    now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
    event_id = str(action.get("eventId", "") or "")
    tenant_id = str(action.get("tenantId", "") or "") or "tenant-unknown"
    session_id = str(action.get("sessionId", "") or "") or ("governance::" + tenant_id)
    trace_id = str(action.get("traceId", "") or "") or str(uuid.uuid4())
    action_type = str(action.get("actionType", "") or "manual")
    action_name = {
        "replay": "DLQ_REPLAY_COMPENSATION",
        "session-close": "SESSION_CLOSE_COMPENSATION",
        "manual": "MANUAL_INTERVENTION_ESCALATION",
    }.get(action_type, "MANUAL_INTERVENTION_ESCALATION")
    reason_code = {
        "replay": "DLQ_REPLAY",
        "session-close": "SESSION_CLOSE",
        "manual": "MANUAL_INTERVENTION_REQUIRED",
    }.get(action_type, "MANUAL_INTERVENTION_REQUIRED")

    details = {
        "eventId": event_id,
        "sourceTopic": str(action.get("sourceTopic", "") or ""),
        "dlqTopic": str(action.get("dlqTopic", "") or ""),
        "dlqReason": str(action.get("reason", "") or ""),
        "compensationAction": action_type,
        "actionReasonCode": str(action.get("actionReasonCode", "") or ""),
    }
    if action_type == "replay":
        details["targetTopic"] = str(action.get("targetTopic", "") or "")
    if action_type == "session-close":
        details["closeReason"] = str(action.get("closeReason", "") or "")

    payload = {
        "service": "platform-compensation-saga",
        "action": action_name,
        "outcome": "SUCCESS" if outcome == "SUCCESS" else ("FAILED" if outcome == "FAILED" else "SKIPPED"),
        "resourceType": "session",
        "resourceId": session_id,
        "sourceTopic": str(action.get("sourceTopic", "") or ""),
        "targetTopic": str(action.get("targetTopic", "") or ""),
        "reasonCode": reason_code,
        "occurredAtMs": now_ms,
        "details": details,
    }
    if outcome == "FAILED":
        payload["failureType"] = code
        payload["failureMessage"] = "compensation action failed: " + code

    return {
        "eventId": str(uuid.uuid4()),
        "eventType": "platform.audit",
        "eventVersion": "v1",
        "traceId": trace_id,
        "sessionId": session_id,
        "tenantId": tenant_id,
        "producer": "platform-compensation-saga",
        "seq": 0,
        "ts": now_ms,
        "idempotencyKey": event_id + ":saga:" + action_type,
        "payload": payload,
    }

audit_records = []
action_outcomes = []
for action in selected_actions:
    outcome, code = normalize_action_outcome(action)
    action_outcomes.append(
        {
            "eventId": str(action.get("eventId", "") or ""),
            "actionType": str(action.get("actionType", "") or ""),
            "outcome": outcome,
            "code": code,
        }
    )
    audit_records.append(build_audit_event(action, outcome, code))

audit_events_path.write_text(
    "\n".join(json.dumps(item, ensure_ascii=False) for item in audit_records) + ("\n" if audit_records else ""),
    encoding="utf-8",
)

audit_publish_attempted = 0
audit_publish_success = 0
audit_publish_failure = 0
audit_publish_failures = []

if apply_saga and publish_audit and audit_records:
    for event in audit_records:
        audit_publish_attempted += 1
        tenant_id = str(event.get("tenantId", "") or "")
        payload_text = json.dumps(event, ensure_ascii=False)
        message = tenant_id + "\x1f" + payload_text + "\n"
        cmd = [
            kcat_bin,
            "-b",
            bootstrap_servers,
            "-t",
            audit_topic,
            "-P",
            "-K",
            "\x1f",
            "-q",
        ]
        try:
            proc = subprocess.run(cmd, input=message, text=True, capture_output=True)
            if proc.returncode == 0:
                audit_publish_success += 1
            else:
                audit_publish_failure += 1
                audit_publish_failures.append(
                    {
                        "stage": "publish-audit",
                        "error": "KCAT_PUBLISH_FAILED",
                        "eventId": str(event.get("eventId", "") or ""),
                        "stderr": (proc.stderr or "").strip(),
                        "stdout": (proc.stdout or "").strip(),
                        "exitCode": proc.returncode,
                    }
                )
        except Exception as exc:
            audit_publish_failure += 1
            audit_publish_failures.append(
                {
                    "stage": "publish-audit",
                    "error": "KCAT_EXECUTION_FAILED",
                    "eventId": str(event.get("eventId", "") or ""),
                    "errorDetail": str(exc),
                }
            )

all_failures = list(prefilter_failures)
all_failures.extend(replay_failed)
all_failures.extend(close_failed)
all_failures.extend(audit_publish_failures)
failed_path.write_text(
    "\n".join(json.dumps(item, ensure_ascii=False) for item in all_failures) + ("\n" if all_failures else ""),
    encoding="utf-8",
)

replay_counts = replay_report.get("counts", {})
close_counts = close_report.get("counts", {})

overall_pass = bool(prefilter_report.get("overallPass", False))
if replay_executed:
    overall_pass = overall_pass and replay_exit == 0 and bool(replay_report.get("overallPass", False))
if apply_saga:
    overall_pass = overall_pass and int(close_counts.get("failure", 0) or 0) == 0
    if publish_audit:
        overall_pass = overall_pass and audit_publish_failure == 0

state_records = read_jsonl(state_path)
state_event_ids = {
    str(item.get("eventId", "") or "")
    for item in state_records
    if isinstance(item, dict) and str(item.get("eventId", "") or "")
}
state_before = len(state_event_ids)
state_added = 0
if apply_saga:
    append_lines = []
    now_iso = datetime.now(timezone.utc).isoformat()
    for outcome in action_outcomes:
        event_id = str(outcome.get("eventId", "") or "")
        action_type = str(outcome.get("actionType", "") or "")
        status = str(outcome.get("outcome", "") or "")
        if not event_id or event_id in state_event_ids:
            continue
        if status != "SUCCESS":
            continue
        append_lines.append(
            json.dumps(
                {
                    "eventId": event_id,
                    "actionType": action_type,
                    "processedAt": now_iso,
                },
                ensure_ascii=False,
            )
        )
        state_event_ids.add(event_id)
        state_added += 1
    if append_lines:
        with state_path.open("a", encoding="utf-8") as handle:
            handle.write("\n".join(append_lines) + "\n")

tenant_filter = sorted({entry.strip() for entry in tenant_filter_raw.split(",") if entry.strip()})
source_filter = sorted({entry.strip() for entry in source_filter_raw.split(",") if entry.strip()})
reason_filter = sorted({entry.strip() for entry in reason_filter_raw.split(",") if entry.strip()})
service_filter = sorted({entry.strip() for entry in service_filter_raw.split(",") if entry.strip()})
action_filter = sorted({entry.strip() for entry in action_filter_raw.split(",") if entry.strip()})

report = {
    "generatedAt": datetime.now(timezone.utc).isoformat(),
    "mode": mode,
    "applySaga": apply_saga,
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
        "actions": action_filter,
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
        "alreadyProcessed": int(prefilter_report["counts"]["alreadyProcessed"]),
        "candidates": int(prefilter_report["counts"]["candidates"]),
        "replayCandidates": int(prefilter_report["counts"]["replayCandidates"]),
        "closeCandidates": int(prefilter_report["counts"]["closeCandidates"]),
        "manualCandidates": int(prefilter_report["counts"]["manualCandidates"]),
        "replayAttempted": int(replay_counts.get("replayAttempted", 0) or 0),
        "replaySuccess": int(replay_counts.get("replaySuccess", 0) or 0),
        "replayFailure": int(replay_counts.get("replayFailure", 0) or 0),
        "closeAttempted": int(close_counts.get("attempted", 0) or 0),
        "closeSuccess": int(close_counts.get("success", 0) or 0),
        "closeFailure": int(close_counts.get("failure", 0) or 0),
        "auditGenerated": len(audit_records),
        "auditPublishAttempted": audit_publish_attempted,
        "auditPublishSuccess": audit_publish_success,
        "auditPublishFailure": audit_publish_failure,
        "stateAdded": state_added,
    },
    "state": {
        "path": str(state_path),
        "entriesBefore": state_before,
        "entriesAfter": len(state_event_ids),
    },
    "artifacts": {
        "prefilterReportPath": str(prefilter_report_path),
        "selectedPath": str(selected_path),
        "failedPath": str(failed_path),
        "replayReportPath": str(replay_report_path) if replay_executed else "",
        "closeReportPath": str(close_report_path),
        "auditEventsPath": str(audit_events_path),
    },
    "replay": {
        "executed": replay_executed,
        "exitCode": replay_exit if replay_executed else 0,
        "overallPass": bool(replay_report.get("overallPass", True)) if replay_executed else True,
    },
    "sessionClose": {
        "overallPass": bool(close_report.get("overallPass", True)),
    },
    "auditPublish": {
        "enabled": apply_saga and publish_audit,
        "topic": audit_topic,
    },
    "overallPass": overall_pass,
}
report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

lines = [
    "# Platform Compensation Saga Summary",
    "",
    "- generatedAt: %s" % report["generatedAt"],
    "- mode: %s" % mode,
    "- applySaga: %s" % str(apply_saga).lower(),
    "- consumed: %d" % report["counts"]["consumed"],
    "- parseError: %d" % report["counts"]["parseError"],
    "- invalid: %d" % report["counts"]["invalid"],
    "- filteredOut: %d" % report["counts"]["filteredOut"],
    "- alreadyProcessed: %d" % report["counts"]["alreadyProcessed"],
    "- candidates: %d" % report["counts"]["candidates"],
    "- replayCandidates: %d" % report["counts"]["replayCandidates"],
    "- closeCandidates: %d" % report["counts"]["closeCandidates"],
    "- manualCandidates: %d" % report["counts"]["manualCandidates"],
    "- replayFailure: %d" % report["counts"]["replayFailure"],
    "- closeFailure: %d" % report["counts"]["closeFailure"],
    "- auditPublishFailure: %d" % report["counts"]["auditPublishFailure"],
    "- overallPass: %s" % str(report["overallPass"]).lower(),
    "- reportPath: %s" % report_path,
    "- selectedPath: %s" % selected_path,
    "- failedPath: %s" % failed_path,
    "- auditEventsPath: %s" % audit_events_path,
]
summary_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
print("\n".join(lines))

sys.exit(0 if overall_pass else 1)
PY
status="$?"
set -e

echo "Platform compensation saga report: $report_path"
echo "Platform compensation saga summary: $summary_path"
exit "$status"
