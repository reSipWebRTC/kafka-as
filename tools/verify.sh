#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

printf '[1/4] Checking diff whitespace...\n'
git diff --check --cached
git diff --check

printf '[2/4] Validating JSON contracts...\n'
python3 - <<'PY'
import json
from pathlib import Path

schema_dir = Path('api/json-schema')
if not schema_dir.exists():
    print('No api/json-schema directory found; skipping JSON validation.')
    raise SystemExit(0)

schema_files = sorted(schema_dir.glob('*.json'))
if not schema_files:
    print('No JSON schema files found; skipping JSON validation.')
    raise SystemExit(0)

for path in schema_files:
    with path.open('r', encoding='utf-8') as handle:
        json.load(handle)
    print(f'OK {path}')
PY

printf '[3/4] Checking required contract files...\n'
if [[ ! -f api/protobuf/realtime_speech.proto ]]; then
  echo 'Missing api/protobuf/realtime_speech.proto' >&2
  exit 1
fi
if [[ ! -x ./gradlew ]]; then
  echo 'Gradle wrapper is missing or not executable.' >&2
  exit 1
fi

printf '[4/4] Running Gradle tests...\n'
./gradlew test --no-daemon
