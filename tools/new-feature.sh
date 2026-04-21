#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: tools/new-feature.sh <branch-name> [base-branch]

Examples:
  tools/new-feature.sh feature/session-orchestrator
  tools/new-feature.sh docs/contracts-hardening main
USAGE
}

if [[ ${1:-} == "-h" || ${1:-} == "--help" ]]; then
  usage
  exit 0
fi

if [[ $# -lt 1 || $# -gt 2 ]]; then
  usage >&2
  exit 1
fi

branch_name="$1"
base_branch="${2:-main}"
repo_root="$(git rev-parse --show-toplevel)"
worktree_name="${branch_name//\//-}"
worktree_path="$repo_root/.worktrees/$worktree_name"

if [[ "$branch_name" == "main" ]]; then
  echo "Refusing to create a feature worktree for main." >&2
  exit 1
fi

if [[ -e "$worktree_path" ]]; then
  echo "Worktree path already exists: $worktree_path" >&2
  exit 1
fi

if ! git -C "$repo_root" rev-parse --verify "$base_branch^{commit}" >/dev/null 2>&1; then
  echo "Base branch or ref does not exist: $base_branch" >&2
  exit 1
fi

mkdir -p "$repo_root/.worktrees"

if git -C "$repo_root" show-ref --verify --quiet "refs/heads/$branch_name"; then
  git -C "$repo_root" worktree add "$worktree_path" "$branch_name"
else
  git -C "$repo_root" worktree add "$worktree_path" -b "$branch_name" "$base_branch"
fi

mkdir -p "$worktree_path/docs/superpowers/plans"

printf 'Created worktree: %s\n' "$worktree_path"
printf 'Branch: %s\n' "$branch_name"
printf 'Base: %s\n' "$base_branch"
printf 'Next: cd %s\n' "$worktree_path"
