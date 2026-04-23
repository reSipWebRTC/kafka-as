# Plan: Control-Plane IAM Mock Drill (JWKS/JWT, Matrix, Failure, Evidence)

## Goal

Without a real IAM/RBAC environment, build a local/test simulated closure for `control-plane` external IAM auth:

1. Local JWKS + JWT integration across `external-iam` and `hybrid`.
2. Claim mapping / authorization matrix coverage.
3. Failure drill coverage (JWKS unavailable, timeout, static fallback) with metric assertions.
4. One-command drill script that emits machine-readable evidence marked as simulated/mock.

## Scope

- `services/control-plane` auth decoder/backend tests.
- Auth external config (timeout tuning for deterministic failure drill).
- `tools/` drill automation script and report generation.
- `docs/` runbook + implementation status updates.

## Tasks

### Task 1: Deterministic JWKS decoder timeout controls

- [x] Extend `control.auth.external` properties with connection/read timeout settings.
- [x] Wire timeout settings into `JwksExternalIamTokenDecoder`.
- [x] Keep defaults backward compatible.

### Task 2: Local JWKS + JWT integration tests

- [x] Add test helper for local JWKS server and signed JWT generation.
- [x] Add end-to-end auth chain tests for `external-iam` and `hybrid`.
- [x] Verify allow/deny outcomes for valid/invalid tokens.

### Task 3: Claim matrix + failure drill tests

- [x] Add claim matrix tests for read/write, tenant scope, and deny reasons.
- [ ] Add failure tests for:
  - [x] JWKS unavailable (connection failure)
  - [x] JWKS timeout
  - [x] `hybrid` fallback behavior and `controlplane.auth.hybrid.fallback.total`

### Task 4: Simulated/mock drill script + docs

- [x] Add `tools/mock-iam-rbac-drill.sh`.
- [x] Emit JSON + markdown summary under `build/reports/mock-iam-drill/`.
- [x] Mark report mode explicitly as `simulated/mock`.
- [x] Update runbook/status docs with execution and artifact paths.

## Verification

- [x] `./gradlew :services:control-plane:test --no-daemon`
- [x] `tools/mock-iam-rbac-drill.sh`
- [x] `tools/verify.sh`
