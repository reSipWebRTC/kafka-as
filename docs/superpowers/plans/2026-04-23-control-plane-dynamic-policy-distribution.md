# Plan: Control-Plane Dynamic Policy Distribution

## Goal

Build the first dynamic policy distribution loop so policy updates are pushed by `control-plane` and consumed by runtime services without waiting for local cache TTL expiry.

## Scope

- Add policy-change event contract (`tenant.policy.changed`)
- Publish event from `control-plane` on tenant policy upsert
- Consume event in runtime services to invalidate/refresh tenant policy caches
- Add tests and docs sync

## Task 1: Contract and Event Model

### Target files

- `docs/contracts.md`
- `docs/services.md`
- `api/json-schema/tenant.policy.changed.v1.json` (new)
- `api/protobuf/realtime_speech.proto`

### Checklist

- [x] Define `tenant.policy.changed` topic/event semantics and payload fields
- [x] Add JSON schema with v1 envelope + payload validation
- [x] Extend protobuf with `TenantPolicyChangedPayload/Event`
- [x] Keep backward compatibility for existing services

## Task 2: Control-Plane Publish Path

### Target files

- `services/control-plane/src/main/java/com/kafkaasr/control/events/*` (new)
- `services/control-plane/src/main/java/com/kafkaasr/control/service/TenantPolicyService.java`
- `services/control-plane/src/main/resources/application.yml`
- `services/control-plane/src/test/java/**`

### Checklist

- [x] Add event publisher abstraction (`TenantPolicyChangedPublisher`) with Kafka + noop implementations
- [x] Add config (`control.kafka.policy-changed-topic` and enable toggle)
- [x] Publish event after successful create/update with tenantId, version, updatedAtMs
- [x] Keep HTTP upsert response semantics unchanged
- [x] Add unit/integration tests for publish-on-upsert behavior

## Task 3: Runtime Consumer Refresh

### Target files

- `services/session-orchestrator/src/main/java/com/kafkaasr/orchestrator/policy/*`
- `services/asr-worker/src/main/java/com/kafkaasr/asr/policy/*`
- `services/translation-worker/src/main/java/com/kafkaasr/translation/policy/*`
- `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/policy/*`
- corresponding `src/test/java/**`

### Checklist

- [x] Add a lightweight consumer in each service for `tenant.policy.changed`
- [x] Add explicit cache invalidation/refresh method in each policy resolver/client
- [x] Ensure updates become effective immediately for subsequent requests/messages
- [x] Add tests covering cache refresh on policy-change event
- [x] Add metrics counters for consume success/failure (`*.policy.distribution.*`)

## Verification

- [x] `./gradlew :services:control-plane:test --no-daemon`
- [x] `./gradlew :services:session-orchestrator:test --no-daemon`
- [x] `./gradlew :services:asr-worker:test --no-daemon`
- [x] `./gradlew :services:translation-worker:test --no-daemon`
- [x] `./gradlew :services:tts-orchestrator:test --no-daemon`
- [x] `tools/verify.sh`
