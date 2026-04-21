#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
marketplace_name="kafka-asr-local"
plugin_source="${SUPERPOWERS_PLUGIN_SOURCE:-$HOME/.codex/.tmp/plugins/plugins/superpowers}"
plugin_target="$repo_root/plugins/superpowers"
manifest_path="$plugin_source/.codex-plugin/plugin.json"

if ! command -v codex >/dev/null 2>&1; then
  echo "codex command not found." >&2
  exit 1
fi

if [[ ! -f "$manifest_path" ]]; then
  echo "Superpowers plugin manifest not found: $manifest_path" >&2
  echo "Set SUPERPOWERS_PLUGIN_SOURCE if your local plugin lives somewhere else." >&2
  exit 1
fi

mkdir -p "$repo_root/plugins"
ln -sfn "$plugin_source" "$plugin_target"

# Keep activation idempotent: remove the old repo-local marketplace if present, then re-add it.
codex plugin marketplace remove "$marketplace_name" >/dev/null 2>&1 || true
codex plugin marketplace add "$repo_root"

printf 'Linked plugin: %s -> %s\n' "$plugin_target" "$plugin_source"
printf 'Registered marketplace: %s\n' "$marketplace_name"
printf 'Next: start a new Codex session in %s so AGENTS.md and Superpowers both apply from turn 1.\n' "$repo_root"
