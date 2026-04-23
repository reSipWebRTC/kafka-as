#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

usage() {
  cat <<'USAGE'
Usage:
  tools/render-monitoring-config.sh [--env-file <path>]

Purpose:
  Render monitoring configs from repository templates:
  - deploy/monitoring/prometheus/alerts/kafka-asr-alerts.template.yml
  - deploy/monitoring/alertmanager/alertmanager.template.yml

Default env file:
  deploy/monitoring/alert-ops.env
USAGE
}

env_file="${ALERT_OPS_ENV_FILE:-$repo_root/deploy/monitoring/alert-ops.env}"
alerts_template="$repo_root/deploy/monitoring/prometheus/alerts/kafka-asr-alerts.template.yml"
alerts_output="$repo_root/deploy/monitoring/prometheus/alerts/kafka-asr-alerts.yml"
alertmanager_template="$repo_root/deploy/monitoring/alertmanager/alertmanager.template.yml"
alertmanager_output="$repo_root/deploy/monitoring/alertmanager/alertmanager.yml"

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
  echo "Missing env file: $env_file" >&2
  exit 1
fi

if [[ ! -f "$alerts_template" ]]; then
  echo "Missing alerts template: $alerts_template" >&2
  exit 1
fi
if [[ ! -f "$alertmanager_template" ]]; then
  echo "Missing alertmanager template: $alertmanager_template" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$env_file"
set +a

python3 - <<'PY' \
  "$env_file" \
  "$alerts_template" \
  "$alerts_output" \
  "$alertmanager_template" \
  "$alertmanager_output"
import os
import pathlib
import re
import sys
import yaml

(
    env_file_raw,
    alerts_template_raw,
    alerts_output_raw,
    alertmanager_template_raw,
    alertmanager_output_raw,
) = sys.argv[1:]

env_file = pathlib.Path(env_file_raw)
alerts_template = pathlib.Path(alerts_template_raw)
alerts_output = pathlib.Path(alerts_output_raw)
alertmanager_template = pathlib.Path(alertmanager_template_raw)
alertmanager_output = pathlib.Path(alertmanager_output_raw)

token_pattern = re.compile(r"\{\{([A-Z0-9_]+)\}\}")

def render(template_path: pathlib.Path, output_path: pathlib.Path) -> None:
    raw = template_path.read_text(encoding="utf-8")
    tokens = sorted(set(token_pattern.findall(raw)))
    missing = [token for token in tokens if not os.environ.get(token)]
    if missing:
        raise SystemExit(
            f"Template {template_path} missing values in env ({env_file}): {', '.join(missing)}"
        )

    rendered = raw
    for token in tokens:
        rendered = rendered.replace(f"{{{{{token}}}}}", os.environ[token])

    unresolved = token_pattern.findall(rendered)
    if unresolved:
        raise SystemExit(
            f"Template {template_path} still has unresolved placeholders: {', '.join(sorted(set(unresolved)))}"
        )

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(rendered, encoding="utf-8")

    try:
        yaml.safe_load(rendered)
    except yaml.YAMLError as exc:
        raise SystemExit(f"Rendered YAML is invalid: {output_path}: {exc}") from exc


render(alerts_template, alerts_output)
render(alertmanager_template, alertmanager_output)

print(f"Rendered alerts config: {alerts_output}")
print(f"Rendered alertmanager config: {alertmanager_output}")
PY
