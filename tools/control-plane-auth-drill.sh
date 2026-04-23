#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

report_dir="${CONTROL_AUTH_DRILL_REPORT_DIR:-$repo_root/build/reports/preprod-drill}"
report_path="${CONTROL_AUTH_DRILL_REPORT_PATH:-$report_dir/control-plane-auth-drill.json}"
summary_path="${CONTROL_AUTH_DRILL_SUMMARY_PATH:-$report_dir/control-plane-auth-drill-summary.md}"

base_url="${CONTROL_AUTH_DRILL_BASE_URL:-http://localhost:8085}"
tenant_id="${CONTROL_AUTH_DRILL_TENANT_ID:-tenant-a}"
cross_tenant_id="${CONTROL_AUTH_DRILL_CROSS_TENANT_ID:-tenant-b}"
read_token="${CONTROL_AUTH_DRILL_READ_TOKEN:-}"
write_token="${CONTROL_AUTH_DRILL_WRITE_TOKEN:-}"
cross_tenant_token="${CONTROL_AUTH_DRILL_CROSS_TENANT_TOKEN:-}"

expect_missing_token_status="${CONTROL_AUTH_DRILL_EXPECT_MISSING_TOKEN_STATUS:-401}"
expect_write_status="${CONTROL_AUTH_DRILL_EXPECT_WRITE_STATUS:-200}"
expect_read_status="${CONTROL_AUTH_DRILL_EXPECT_READ_STATUS:-200}"
expect_read_put_status="${CONTROL_AUTH_DRILL_EXPECT_READ_PUT_STATUS:-403}"
expect_cross_tenant_status="${CONTROL_AUTH_DRILL_EXPECT_CROSS_TENANT_STATUS:-403}"
enable_cross_tenant_check="${CONTROL_AUTH_DRILL_ENABLE_CROSS_TENANT_CHECK:-0}"

if [[ -z "$read_token" ]]; then
  echo "CONTROL_AUTH_DRILL_READ_TOKEN is required." >&2
  exit 1
fi
if [[ -z "$write_token" ]]; then
  echo "CONTROL_AUTH_DRILL_WRITE_TOKEN is required." >&2
  exit 1
fi

policy_json="${CONTROL_AUTH_DRILL_POLICY_JSON:-}"
if [[ -z "$policy_json" ]]; then
  policy_json='{"sourceLang":"zh-CN","targetLang":"en-US","asrModel":"funasr-streaming","translationModel":"openai:gpt-4o-mini","ttsVoice":"alloy","maxConcurrentSessions":200,"rateLimitPerMinute":6000,"enabled":true}'
fi

mkdir -p "$report_dir"
mkdir -p "$(dirname "$report_path")"
mkdir -p "$(dirname "$summary_path")"

records_file="$(mktemp)"
trap 'rm -f "$records_file"' EXIT

run_case() {
  local name="$1"
  local method="$2"
  local path="$3"
  local token="$4"
  local expected_status="$5"
  local request_body="${6:-}"

  local url="${base_url%/}${path}"
  local body_path="$report_dir/control-plane-auth-${name}.body.json"

  local -a curl_command
  curl_command=(
    curl
    -sS
    -o "$body_path"
    -w "%{http_code}"
    -X "$method"
    "$url"
    -H "accept: application/json"
  )
  if [[ -n "$token" ]]; then
    curl_command+=(-H "Authorization: Bearer $token")
  fi
  if [[ -n "$request_body" ]]; then
    curl_command+=(
      -H "content-type: application/json"
      --data "$request_body"
    )
  fi

  local actual_status="000"
  local curl_exit=0
  set +e
  actual_status="$("${curl_command[@]}")"
  curl_exit="$?"
  set -e

  if [[ "$curl_exit" -ne 0 ]]; then
    actual_status="000"
  fi

  local pass=0
  if [[ "$curl_exit" -eq 0 && "$actual_status" == "$expected_status" ]]; then
    pass=1
  fi

  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$name" \
    "$method" \
    "$url" \
    "$expected_status" \
    "$actual_status" \
    "$curl_exit" \
    "$pass" \
    "$body_path" >> "$records_file"

  echo "[$name] expected=$expected_status actual=$actual_status pass=$pass"
}

echo "Running control-plane auth drill"
echo "base_url=$base_url tenant_id=$tenant_id"

run_case \
  "missing-token-read" \
  "GET" \
  "/api/v1/tenants/${tenant_id}/policy" \
  "" \
  "$expect_missing_token_status"

run_case \
  "write-token-put" \
  "PUT" \
  "/api/v1/tenants/${tenant_id}/policy" \
  "$write_token" \
  "$expect_write_status" \
  "$policy_json"

run_case \
  "read-token-get" \
  "GET" \
  "/api/v1/tenants/${tenant_id}/policy" \
  "$read_token" \
  "$expect_read_status"

run_case \
  "read-token-put" \
  "PUT" \
  "/api/v1/tenants/${tenant_id}/policy" \
  "$read_token" \
  "$expect_read_put_status" \
  "$policy_json"

if [[ "$enable_cross_tenant_check" == "1" ]]; then
  token_for_cross="$cross_tenant_token"
  if [[ -z "$token_for_cross" ]]; then
    token_for_cross="$read_token"
  fi
  run_case \
    "cross-tenant-read" \
    "GET" \
    "/api/v1/tenants/${cross_tenant_id}/policy" \
    "$token_for_cross" \
    "$expect_cross_tenant_status"
fi

set +e
python3 - <<'PY' "$records_file" "$report_path" "$summary_path" "$base_url" "$tenant_id" "$cross_tenant_id" "$enable_cross_tenant_check"
import json
import pathlib
from datetime import datetime, timezone
import sys

(
    records_path_raw,
    report_path_raw,
    summary_path_raw,
    base_url,
    tenant_id,
    cross_tenant_id,
    enable_cross_tenant_check_raw,
) = sys.argv[1:]

records_path = pathlib.Path(records_path_raw)
report_path = pathlib.Path(report_path_raw)
summary_path = pathlib.Path(summary_path_raw)
enable_cross_tenant_check = enable_cross_tenant_check_raw == "1"

checks = []
for line in records_path.read_text(encoding="utf-8").splitlines():
    (
        name,
        method,
        url,
        expected_status,
        actual_status,
        curl_exit_code,
        pass_raw,
        body_path,
    ) = line.split("\t")
    checks.append(
        {
            "name": name,
            "method": method,
            "url": url,
            "expectedStatus": int(expected_status),
            "actualStatus": int(actual_status),
            "curlExitCode": int(curl_exit_code),
            "pass": pass_raw == "1",
            "bodyPath": body_path,
        }
    )

overall_pass = all(item["pass"] for item in checks) if checks else False

report = {
    "generatedAt": datetime.now(timezone.utc).isoformat(),
    "baseUrl": base_url,
    "tenantId": tenant_id,
    "crossTenantId": cross_tenant_id,
    "crossTenantCheckEnabled": enable_cross_tenant_check,
    "checkCount": len(checks),
    "overallPass": overall_pass,
    "checks": checks,
}
report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

lines = [
    "# Control-Plane Auth Drill Summary",
    "",
    f"- generatedAt: {report['generatedAt']}",
    f"- baseUrl: {base_url}",
    f"- tenantId: {tenant_id}",
    f"- crossTenantCheckEnabled: {str(enable_cross_tenant_check).lower()}",
    f"- checkCount: {len(checks)}",
    f"- overallPass: {str(overall_pass).lower()}",
    f"- reportPath: {report_path}",
    "",
    "| case | method | expected | actual | pass | bodyPath |",
    "| --- | --- | ---: | ---: | --- | --- |",
]
for item in checks:
    lines.append(
        "| {name} | {method} | {expected} | {actual} | {status} | `{body_path}` |".format(
            name=item["name"],
            method=item["method"],
            expected=item["expectedStatus"],
            actual=item["actualStatus"],
            status="PASS" if item["pass"] else "FAIL",
            body_path=item["bodyPath"],
        )
    )

summary = "\n".join(lines) + "\n"
summary_path.write_text(summary, encoding="utf-8")
print(summary)
sys.exit(0 if overall_pass else 1)
PY
status="$?"
set -e

echo "Control-plane auth drill report: $report_path"
echo "Control-plane auth drill summary: $summary_path"
exit "$status"
