# Plan: Control-Plane Policy Distribution Execution Chain

Date: 2026-04-25  
Branch: `feature/control-plane-policy-distribution-execution`

## Goal

Upgrade policy distribution from "publish intent + cache invalidation" to "runtime execution feedback + control-plane aggregation".

## Scope

- Contract-first add execution feedback event.
- Runtime consumers publish distribution execution result.
- Control-plane consumes execution result and exposes queryable status.
- Keep existing `tenant.policy.changed` behavior backward compatible.

## Task 1: Contract Freeze (Event + API)

- [x] Add event contract `tenant.policy.distribution.result`:
  - JSON schema in `api/json-schema/`
  - protobuf in `api/protobuf/realtime_speech.proto`
  - docs sync (`docs/contracts.md`, `docs/event-model.md`)
- [x] Define payload fields (v1):
  - `tenantId`, `policyVersion`, `operation`
  - `service`, `region`, `status` (`APPLIED` / `FAILED` / `IGNORED`)
  - `reasonCode`, `reasonMessage`, `appliedAtMs`
  - `sourceEventId` (link to `tenant.policy.changed.eventId`)

## Task 2: Runtime Execution Feedback Publishing

- [x] In policy-changed consumers (`session-orchestrator` / `asr-worker` / `translation-worker` / `tts-orchestrator` / `command-worker`):
  - publish `tenant.policy.distribution.result` on success/failure/ignored
  - keep current invalidate/refresh logic unchanged
- [x] Add per-service metrics:
  - `*.policy.distribution.execute.total{result,code}`

## Task 3: Control-Plane Aggregation & Query

- [x] Add control-plane consumer for `tenant.policy.distribution.result`.
- [x] Persist latest execution status by `(tenantId, policyVersion, service, region)`.
- [x] Add query API:
  - `GET /api/v1/tenants/{tenantId}/policy:distribution-status?policyVersion=<n>`
- [x] Keep auth behavior consistent with existing policy read scope.

## Task 4: Tests & Closure

- [x] Add schema/proto compatibility tests and consumer parsing tests.
- [x] Add integration tests for end-to-end status aggregation.
- [x] Run:
  - `./gradlew :services:control-plane:test --no-daemon`
  - `tools/verify.sh`
