# Plan: Control-Plane Policy Store DB Backend

Date: 2026-04-25  
Branch: `feature/control-plane-policy-store-db`

## Goal

Introduce a JDBC-backed `TenantPolicyRepository` for `control-plane` while keeping external APIs/contracts unchanged and preserving current Redis default behavior.

## Scope

- Add `control.policy-store.backend` switch (`redis` default, `jdbc` optional).
- Implement JDBC repository for:
  - current policy read/write
  - conditional create
  - history append
  - latest/version history lookup
  - remove latest history
- Add lightweight table initialization for local/test.
- Add repository tests for JDBC backend behavior parity.
- Update internal docs/README to reflect new backend capability.

## Non-Goals

- No API contract changes.
- No IAM behavior changes.
- No cross-region distribution execution changes.

## Execution Steps

- [x] Add JDBC dependency and policy-store config fields.
- [x] Make Redis repository conditional (`backend=redis`).
- [x] Add JDBC repository (`backend=jdbc`) + SQL init.
- [x] Add/adjust tests (JDBC repository + existing tests stay green).
- [x] Run `:services:control-plane:test` and `tools/verify.sh`.
- [x] Update docs status/README and prepare PR notes.
