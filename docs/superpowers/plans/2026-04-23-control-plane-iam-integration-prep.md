# Plan: Control-Plane Real IAM Integration Prep Layer

## Goal

Build a practical “prep layer” so real IAM integration can start immediately once provider endpoints and credentials are available.

Deliverables:

1. Parameter template for real IAM integration.
2. Precheck script for config completeness and endpoint reachability.
3. Runbook checklist for provider handoff and preprod drill execution.

## Tasks

### Task 1: IAM parameter template

- [x] Add versioned template file for `control.auth.external` and drill tokens.
- [x] Document which values are required from IAM/RBAC team.
- [x] Keep defaults aligned with current `control-plane` auth config.

### Task 2: IAM precheck tool

- [x] Add `tools/control-plane-iam-precheck.sh`.
- [x] Validate required envs (`issuer/audience/jwks/claims/permissions`).
- [x] Validate mode constraints (`external-iam` / `hybrid`).
- [x] Add optional network checks for JWKS and OIDC discovery.
- [x] Emit machine-readable JSON + markdown summary artifacts.

### Task 3: Runbook and automation docs

- [x] Add runbook for real IAM handoff checklist and cutover flow.
- [x] Add automation doc entry for precheck command and artifacts.
- [x] Update implementation status to reflect “prep layer ready, real env pending”.

## Verification

- [x] `./gradlew :services:control-plane:test --no-daemon`
- [x] `tools/control-plane-iam-precheck.sh --help`
- [x] `tools/verify.sh`
