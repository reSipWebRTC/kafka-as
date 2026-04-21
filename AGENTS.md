# AGENTS

This repository expects Codex to use the locally installed `superpowers` plugin as the default workflow layer.

## Required Workflow

1. At the start of any non-trivial coding conversation, invoke the `using-superpowers` skill.
2. If the task is ambiguous or changes architecture/contracts, use `brainstorming` first.
3. For feature work, use `using-git-worktrees` and prefer the repository entrypoint:
   - `tools/new-feature.sh <branch-name>`
4. After the design is approved, write the implementation plan with `writing-plans`.
   - Save plans under `docs/superpowers/plans/YYYY-MM-DD-<feature-name>.md`
5. Execute with `subagent-driven-development` by default.
   - Use `executing-plans` only for narrow, low-risk changes.
6. Before completion, run:
   - `tools/verify.sh`
7. Before merge, use `requesting-code-review`.
8. Finish with:
   - `tools/finish-feature.sh <branch-name>`

## Repository-Specific Rules

- `docs/` and `api/` are the technical source of truth.
- If a change affects external behavior, update `docs/contracts.md` and `api/` before or together with code.
- High-frequency audio frames must not be routed through `session-orchestrator`.
- Keep one feature per worktree.
- Prefer `.worktrees/` for project-local worktrees.

## Activation

If the repository-local marketplace entry has not been linked yet, run:

```bash
tools/activate-superpowers.sh
```

That script links the already installed home-local `superpowers` plugin into this repository and registers the repository as a Codex plugin marketplace.
