# Dev Workflow

## 1. Goal

This repository uses a spec-first workflow and is prepared for isolated feature work with `git worktree`.

The intended sequence is:

1. Confirm or update the spec in `docs/`.
2. Freeze protocol and event changes in `docs/contracts.md` and `api/`.
3. Create a dedicated worktree for one feature branch.
4. Implement in isolation.
5. Verify, review, and merge back to `main`.

## 2. Source of Truth

Use these files as the technical source of truth:

- `docs/architecture.md`
- `docs/contracts.md`
- `docs/event-model.md`
- `docs/services.md`
- `docs/observability.md`
- `docs/roadmap.md`

If `openspec` is introduced later, use it to shape product requirements or feature proposals, but keep technical contracts frozen in `docs/contracts.md` and `api/`. Do not maintain two parallel technical specs.

## 3. Branch Naming

Recommended branch names:

- `feature/gateway`
- `feature/asr-pipeline`
- `feature/translation-worker`
- `feature/tts-orchestrator`
- `feature/control-plane`
- `docs/contracts-hardening`
- `chore/local-dev`

Keep one worktree per branch.

## 4. Worktree Layout

Project-local worktrees live under:

- `.worktrees/`

Examples:

- `.worktrees/feature-gateway`
- `.worktrees/feature-asr-pipeline`
- `.worktrees/docs-contracts-hardening`

## 5. Standard Flow

### 5.1 Create a branch worktree

From the repository root:

```bash
git worktree add .worktrees/feature-gateway -b feature/gateway
```

Automation entrypoint:

```bash
tools/new-feature.sh feature/gateway
```

### 5.2 Work inside the isolated tree

```bash
cd .worktrees/feature-gateway
git status
```

### 5.3 Keep spec and implementation aligned

If a feature changes protocol, schema, or event behavior, update these before or together with code:

- `docs/contracts.md`
- `api/protobuf/realtime_speech.proto`
- `api/json-schema/*.json`

### 5.4 Verify before merge

At minimum:

- Re-read the relevant spec section
- Run the project-appropriate validation commands
- Review changed contracts and docs
- Check `git diff --stat`

Default verification entrypoint:

```bash
tools/verify.sh
```

### 5.5 Merge back

After review:

```bash
git checkout main
git merge --no-ff feature/gateway
git worktree remove .worktrees/feature-gateway
git branch -d feature/gateway
```

Automation entrypoint:

```bash
tools/finish-feature.sh feature/gateway
```

## 6. Automation Scripts

Repository-local automation lives under `tools/`:

- `tools/new-feature.sh`
  Creates a branch-backed worktree under `.worktrees/`.
- `tools/verify.sh`
  Runs contract checks and `./gradlew test`.
- `tools/finish-feature.sh`
  Verifies, merges into `main`, and optionally cleans up the branch worktree.

See also:

- `docs/automation.md`

## 7. Superpowers Usage

Use `superpowers` as the execution workflow layer:

1. Design/spec confirmation
2. Worktree creation
3. Plan writing
4. Task execution
5. Code review
6. Branch finish/cleanup

The repository-side convention is simple:

- `docs/` defines architecture and contracts
- `api/` defines machine-checkable protocol
- `.worktrees/` defines isolated implementation workspaces

## 8. What Not to Do

- Do not implement large features directly on `main`.
- Do not change event formats in code without updating `docs/contracts.md` and `api/`.
- Do not run multiple unrelated features in the same worktree.
- Do not introduce a second technical source of truth beside `docs/` and `api/`.

## 9. First Recommended Feature Sequence

Recommended implementation order:

1. `feature/gateway`
2. `feature/session-orchestrator`
3. `feature/asr-pipeline`
4. `feature/translation-worker`
5. `feature/observability-baseline`
6. `feature/tts-orchestrator`
