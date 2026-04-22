#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
compose_file="$repo_root/deploy/monitoring/docker-compose.yml"

if ! command -v docker >/dev/null 2>&1; then
  echo "docker command not found" >&2
  exit 1
fi

if [[ ! -f "$compose_file" ]]; then
  echo "missing compose file: $compose_file" >&2
  exit 1
fi

docker compose -f "$compose_file" up -d

cat <<EOF
Monitoring stack started.
Prometheus: http://localhost:9090
Grafana:    http://localhost:3000 (admin/admin)
EOF
