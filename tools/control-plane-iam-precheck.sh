#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

usage() {
  cat <<'USAGE'
Usage:
  tools/control-plane-iam-precheck.sh [--env-file <path>] [--skip-network] [--allow-placeholder-values]
                                      [--report-dir <path>] [--print-env]

Purpose:
  Precheck real IAM/RBAC integration readiness for control-plane:
  - required parameter completeness
  - auth mode constraints
  - optional JWKS/OIDC reachability checks
  - machine-readable evidence artifacts

Examples:
  tools/control-plane-iam-precheck.sh --env-file .secrets/control-plane-iam.env
  tools/control-plane-iam-precheck.sh --env-file deploy/env/control-plane-iam.env.template --skip-network --allow-placeholder-values
USAGE
}

env_file=""
skip_network=0
allow_placeholder_values=0
print_env=0
report_dir="${CONTROL_PLANE_IAM_PRECHECK_REPORT_DIR:-$repo_root/build/reports/control-plane-iam-precheck}"
report_path="${CONTROL_PLANE_IAM_PRECHECK_REPORT_PATH:-$report_dir/control-plane-iam-precheck.json}"
summary_path="${CONTROL_PLANE_IAM_PRECHECK_SUMMARY_PATH:-$report_dir/control-plane-iam-precheck-summary.md}"

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
    --skip-network)
      skip_network=1
      shift
      ;;
    --allow-placeholder-values)
      allow_placeholder_values=1
      shift
      ;;
    --report-dir)
      if [[ $# -lt 2 ]]; then
        echo "--report-dir requires a value" >&2
        exit 1
      fi
      report_dir="$2"
      report_path="$report_dir/control-plane-iam-precheck.json"
      summary_path="$report_dir/control-plane-iam-precheck-summary.md"
      shift 2
      ;;
    --print-env)
      print_env=1
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

if [[ -n "$env_file" ]]; then
  if [[ ! -f "$env_file" ]]; then
    echo "Env file not found: $env_file" >&2
    exit 1
  fi
  set -a
  # shellcheck disable=SC1090
  source "$env_file"
  set +a
fi

mkdir -p "$report_dir"
mkdir -p "$(dirname "$report_path")"
mkdir -p "$(dirname "$summary_path")"

records_file="$(mktemp)"
jwks_response_file="$(mktemp)"
oidc_response_file="$(mktemp)"
trap 'rm -f "$records_file" "$jwks_response_file" "$oidc_response_file"' EXIT

utc_now() {
  date -u +"%Y-%m-%dT%H:%M:%SZ"
}

add_check() {
  local check_id="$1"
  local status="$2"
  local detail="$3"
  printf '%s\t%s\t%s\n' "$check_id" "$status" "$detail" >> "$records_file"
}

is_blank() {
  local value="${1:-}"
  [[ -z "${value// }" ]]
}

looks_placeholder() {
  local value="${1:-}"
  local normalized
  normalized="$(echo "$value" | tr '[:upper:]' '[:lower:]')"
  [[ "$normalized" == *"example.com"* ]] \
    || [[ "$normalized" == *"changeme"* ]] \
    || [[ "$normalized" == *"replace_me"* ]] \
    || [[ "$normalized" == *"<"* ]] \
    || [[ "$normalized" == *">"* ]]
}

check_required() {
  local var_name="$1"
  local value="${!var_name:-}"
  if is_blank "$value"; then
    add_check "required:$var_name" "FAIL" "value is empty"
    return
  fi
  if looks_placeholder "$value"; then
    if [[ "$allow_placeholder_values" == "1" ]]; then
      add_check "required:$var_name" "WARN" "placeholder-like value detected"
    else
      add_check "required:$var_name" "FAIL" "placeholder-like value detected"
    fi
    return
  fi
  add_check "required:$var_name" "PASS" "present"
}

validate_url() {
  local check_id="$1"
  local value="$2"
  if is_blank "$value"; then
    add_check "$check_id" "FAIL" "empty URL"
    return
  fi
  set +e
  python3 - <<'PY' "$value"
import sys
from urllib.parse import urlparse

url = sys.argv[1]
parsed = urlparse(url)
if parsed.scheme not in ("http", "https") or not parsed.netloc:
    raise SystemExit(1)
raise SystemExit(0)
PY
  local status="$?"
  set -e
  if [[ "$status" -eq 0 ]]; then
    add_check "$check_id" "PASS" "valid URL format"
  else
    add_check "$check_id" "FAIL" "invalid URL format"
  fi
}

required_vars=(
  CONTROL_AUTH_ENABLED
  CONTROL_AUTH_MODE
  CONTROL_AUTH_EXTERNAL_ISSUER
  CONTROL_AUTH_EXTERNAL_AUDIENCE
  CONTROL_AUTH_EXTERNAL_JWKS_URI
  CONTROL_AUTH_EXTERNAL_PERMISSION_CLAIM
  CONTROL_AUTH_EXTERNAL_TENANT_CLAIM
  CONTROL_AUTH_EXTERNAL_READ_PERMISSION
  CONTROL_AUTH_EXTERNAL_WRITE_PERMISSION
  CONTROL_AUTH_DRILL_BASE_URL
  CONTROL_AUTH_DRILL_TENANT_ID
  CONTROL_AUTH_DRILL_READ_TOKEN
  CONTROL_AUTH_DRILL_WRITE_TOKEN
)

for var_name in "${required_vars[@]}"; do
  check_required "$var_name"
done

auth_enabled="${CONTROL_AUTH_ENABLED:-}"
if [[ "$auth_enabled" == "true" ]]; then
  add_check "mode:auth-enabled" "PASS" "CONTROL_AUTH_ENABLED=true"
else
  add_check "mode:auth-enabled" "FAIL" "CONTROL_AUTH_ENABLED must be true"
fi

auth_mode="${CONTROL_AUTH_MODE:-}"
case "$auth_mode" in
  external-iam|hybrid)
    add_check "mode:value" "PASS" "CONTROL_AUTH_MODE=$auth_mode"
    ;;
  *)
    add_check "mode:value" "FAIL" "CONTROL_AUTH_MODE must be external-iam or hybrid"
    ;;
esac

if [[ "$auth_mode" == "hybrid" ]]; then
  if is_blank "${CONTROL_AUTH_TOKENS:-}"; then
    add_check "mode:hybrid-fallback" "FAIL" "CONTROL_AUTH_TOKENS required in hybrid mode"
  else
    add_check "mode:hybrid-fallback" "PASS" "hybrid fallback token configured"
  fi
fi

if [[ "${CONTROL_AUTH_DRILL_ENABLE_CROSS_TENANT_CHECK:-0}" == "1" ]]; then
  if is_blank "${CONTROL_AUTH_DRILL_CROSS_TENANT_ID:-}"; then
    add_check "drill:cross-tenant-id" "FAIL" "CONTROL_AUTH_DRILL_CROSS_TENANT_ID required when cross tenant check enabled"
  else
    add_check "drill:cross-tenant-id" "PASS" "cross tenant id configured"
  fi
fi

validate_url "url:issuer" "${CONTROL_AUTH_EXTERNAL_ISSUER:-}"
validate_url "url:jwks" "${CONTROL_AUTH_EXTERNAL_JWKS_URI:-}"
validate_url "url:drill-base" "${CONTROL_AUTH_DRILL_BASE_URL:-}"

if [[ "$skip_network" == "1" ]]; then
  add_check "network:jwks" "SKIP" "network checks skipped by --skip-network"
  add_check "network:oidc-discovery" "SKIP" "network checks skipped by --skip-network"
else
  set +e
  curl -fsS --max-time 10 "${CONTROL_AUTH_EXTERNAL_JWKS_URI:-}" > "$jwks_response_file"
  jwks_fetch_status="$?"
  set -e
  if [[ "$jwks_fetch_status" -ne 0 ]]; then
    add_check "network:jwks" "FAIL" "failed to fetch JWKS URI"
  else
    set +e
    python3 - <<'PY' "$jwks_response_file"
import json
import pathlib
import sys

content = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
keys = content.get("keys", [])
if not isinstance(keys, list) or len(keys) == 0:
    raise SystemExit(1)
raise SystemExit(0)
PY
    jwks_json_status="$?"
    set -e
    if [[ "$jwks_json_status" -eq 0 ]]; then
      add_check "network:jwks" "PASS" "JWKS reachable and keys[] non-empty"
    else
      add_check "network:jwks" "FAIL" "JWKS response invalid or keys[] empty"
    fi
  fi

  issuer="${CONTROL_AUTH_EXTERNAL_ISSUER:-}"
  issuer="${issuer%/}"
  oidc_url="${issuer}/.well-known/openid-configuration"
  set +e
  curl -fsS --max-time 10 "$oidc_url" > "$oidc_response_file"
  oidc_fetch_status="$?"
  set -e
  if [[ "$oidc_fetch_status" -ne 0 ]]; then
    add_check "network:oidc-discovery" "WARN" "failed to fetch OIDC discovery document"
  else
    set +e
    python3 - <<'PY' "$oidc_response_file" "${CONTROL_AUTH_EXTERNAL_JWKS_URI:-}" "${CONTROL_AUTH_EXTERNAL_ISSUER:-}"
import json
import pathlib
import sys

doc = json.loads(pathlib.Path(sys.argv[1]).read_text(encoding="utf-8"))
expected_jwks = sys.argv[2]
expected_issuer = sys.argv[3]

jwks_uri = doc.get("jwks_uri")
issuer = doc.get("issuer")

if jwks_uri != expected_jwks:
    raise SystemExit(2)
if issuer != expected_issuer:
    raise SystemExit(3)
raise SystemExit(0)
PY
    oidc_validation_status="$?"
    set -e
    case "$oidc_validation_status" in
      0)
        add_check "network:oidc-discovery" "PASS" "OIDC discovery matches issuer and jwks_uri"
        ;;
      2)
        add_check "network:oidc-discovery" "WARN" "OIDC jwks_uri differs from configured CONTROL_AUTH_EXTERNAL_JWKS_URI"
        ;;
      3)
        add_check "network:oidc-discovery" "WARN" "OIDC issuer differs from configured CONTROL_AUTH_EXTERNAL_ISSUER"
        ;;
      *)
        add_check "network:oidc-discovery" "WARN" "OIDC discovery fetched but validation failed"
        ;;
    esac
  fi
fi

if [[ "$print_env" == "1" ]]; then
  {
    echo "CONTROL_AUTH_ENABLED=${CONTROL_AUTH_ENABLED:-}"
    echo "CONTROL_AUTH_MODE=${CONTROL_AUTH_MODE:-}"
    echo "CONTROL_AUTH_EXTERNAL_ISSUER=${CONTROL_AUTH_EXTERNAL_ISSUER:-}"
    echo "CONTROL_AUTH_EXTERNAL_AUDIENCE=${CONTROL_AUTH_EXTERNAL_AUDIENCE:-}"
    echo "CONTROL_AUTH_EXTERNAL_JWKS_URI=${CONTROL_AUTH_EXTERNAL_JWKS_URI:-}"
    echo "CONTROL_AUTH_EXTERNAL_PERMISSION_CLAIM=${CONTROL_AUTH_EXTERNAL_PERMISSION_CLAIM:-}"
    echo "CONTROL_AUTH_EXTERNAL_TENANT_CLAIM=${CONTROL_AUTH_EXTERNAL_TENANT_CLAIM:-}"
    echo "CONTROL_AUTH_EXTERNAL_READ_PERMISSION=${CONTROL_AUTH_EXTERNAL_READ_PERMISSION:-}"
    echo "CONTROL_AUTH_EXTERNAL_WRITE_PERMISSION=${CONTROL_AUTH_EXTERNAL_WRITE_PERMISSION:-}"
    echo "CONTROL_AUTH_DRILL_BASE_URL=${CONTROL_AUTH_DRILL_BASE_URL:-}"
    echo "CONTROL_AUTH_DRILL_TENANT_ID=${CONTROL_AUTH_DRILL_TENANT_ID:-}"
  } > "$report_dir/resolved-env.txt"
fi

set +e
python3 - <<'PY' \
  "$records_file" \
  "$report_path" \
  "$summary_path" \
  "$env_file" \
  "$skip_network" \
  "$allow_placeholder_values"
import json
import pathlib
from datetime import datetime, timezone
import sys

records_path = pathlib.Path(sys.argv[1])
report_path = pathlib.Path(sys.argv[2])
summary_path = pathlib.Path(sys.argv[3])
env_file = sys.argv[4]
skip_network = sys.argv[5] == "1"
allow_placeholder_values = sys.argv[6] == "1"

checks = []
for line in records_path.read_text(encoding="utf-8").splitlines():
    check_id, status, detail = line.split("\t")
    checks.append(
        {
            "checkId": check_id,
            "status": status,
            "detail": detail,
        }
    )

pass_count = sum(1 for item in checks if item["status"] == "PASS")
warn_count = sum(1 for item in checks if item["status"] == "WARN")
fail_count = sum(1 for item in checks if item["status"] == "FAIL")
skip_count = sum(1 for item in checks if item["status"] == "SKIP")
overall_pass = fail_count == 0

report = {
    "generatedAt": datetime.now(timezone.utc).isoformat(),
    "mode": "real-iam-precheck",
    "config": {
        "envFile": env_file,
        "skipNetwork": skip_network,
        "allowPlaceholderValues": allow_placeholder_values,
    },
    "summary": {
        "total": len(checks),
        "pass": pass_count,
        "warn": warn_count,
        "fail": fail_count,
        "skip": skip_count,
        "overallPass": overall_pass,
    },
    "checks": checks,
}
report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

lines = [
    "# Control-Plane IAM Precheck Summary",
    "",
    f"- generatedAt: {report['generatedAt']}",
    f"- mode: {report['mode']}",
    f"- overallPass: {str(overall_pass).lower()}",
    f"- pass/warn/fail/skip: {pass_count}/{warn_count}/{fail_count}/{skip_count}",
    f"- envFile: {env_file or '(not provided)'}",
    f"- skipNetwork: {str(skip_network).lower()}",
    f"- allowPlaceholderValues: {str(allow_placeholder_values).lower()}",
    f"- reportPath: {report_path}",
    "",
    "| checkId | status | detail |",
    "| --- | --- | --- |",
]
for item in checks:
    lines.append(
        "| {check_id} | {status} | {detail} |".format(
            check_id=item["checkId"],
            status=item["status"],
            detail=item["detail"].replace("|", "\\|"),
        )
    )

summary = "\n".join(lines) + "\n"
summary_path.write_text(summary, encoding="utf-8")
print(summary)
sys.exit(0 if overall_pass else 1)
PY
status="$?"
set -e

echo "IAM precheck report: $report_path"
echo "IAM precheck summary: $summary_path"
exit "$status"
