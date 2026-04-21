#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: tools/finish-feature.sh <branch-name> [--skip-verify] [--keep-worktree]

Examples:
  tools/finish-feature.sh feature/gateway
  tools/finish-feature.sh docs/contracts-hardening --keep-worktree
USAGE
}

find_worktree_for_branch() {
  local branch_ref="$1"
  git worktree list --porcelain | awk -v target="$branch_ref" '
    $1 == "worktree" { path = $2 }
    $1 == "branch" && $2 == target { print path; exit }
  '
}

branch_name=""
skip_verify=false
keep_worktree=false

for arg in "$@"; do
  case "$arg" in
    -h|--help)
      usage
      exit 0
      ;;
    --skip-verify)
      skip_verify=true
      ;;
    --keep-worktree)
      keep_worktree=true
      ;;
    *)
      if [[ -z "$branch_name" ]]; then
        branch_name="$arg"
      else
        usage >&2
        exit 1
      fi
      ;;
  esac
done

if [[ -z "$branch_name" ]]; then
  usage >&2
  exit 1
fi

if [[ "$branch_name" == "main" ]]; then
  echo 'Refusing to finish main as a feature branch.' >&2
  exit 1
fi

if ! git show-ref --verify --quiet "refs/heads/$branch_name"; then
  echo "Unknown local branch: $branch_name" >&2
  exit 1
fi

main_worktree="$(find_worktree_for_branch refs/heads/main)"
branch_worktree="$(find_worktree_for_branch refs/heads/$branch_name)"
current_dir="$(pwd -P)"

if [[ -z "$main_worktree" ]]; then
  echo 'Could not find a worktree with main checked out.' >&2
  exit 1
fi

if [[ -n "$(git -C "$main_worktree" status --porcelain)" ]]; then
  echo "Main worktree is dirty: $main_worktree" >&2
  exit 1
fi

if [[ -n "$branch_worktree" ]] && [[ -n "$(git -C "$branch_worktree" status --porcelain)" ]]; then
  echo "Feature worktree is dirty: $branch_worktree" >&2
  exit 1
fi

if [[ -z "$branch_worktree" && "$skip_verify" == false ]]; then
  echo 'Feature branch is not checked out in a worktree; use --skip-verify or attach it first.' >&2
  exit 1
fi

if [[ -n "$branch_worktree" && "$skip_verify" == false ]]; then
  printf 'Running verification in %s\n' "$branch_worktree"
  (
    cd "$branch_worktree"
    ./tools/verify.sh
  )
fi

printf 'Merging %s into main\n' "$branch_name"
git -C "$main_worktree" merge --no-ff "$branch_name" -m "merge: integrate $branch_name"

if [[ "$keep_worktree" == true ]]; then
  printf 'Merged %s. Kept worktree and branch in place.\n' "$branch_name"
  exit 0
fi

if [[ -n "$branch_worktree" && "$current_dir" == "$branch_worktree"* ]]; then
  echo 'Refusing to remove the current working directory. Rerun from another path or use --keep-worktree.' >&2
  exit 1
fi

if [[ -n "$branch_worktree" && "$branch_worktree" != "$main_worktree" ]]; then
  git -C "$main_worktree" worktree remove "$branch_worktree"
fi

git -C "$main_worktree" branch -d "$branch_name"
printf 'Merged and cleaned up %s\n' "$branch_name"
