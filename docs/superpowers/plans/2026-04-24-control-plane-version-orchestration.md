# Plan: Control-Plane Version Orchestration and Cross-Region Distribution

## Goal

Add production-oriented policy orchestration capabilities in `control-plane`:

- rollback to a specified historical policy version (not only previous version)
- attach and publish cross-region distribution intent with policy changes

## Scope

- contract-first changes for control-plane API and policy events
- control-plane persistence and orchestration logic updates
- event publishing and metrics for distribution intent
- tests, docs sync, and repository verification

## Task 1: Freeze Contracts (API + Event)

### Target files

- `docs/contracts.md`
- `api/json-schema/tenant.policy.changed.v1.json`
- `api/protobuf/realtime_speech.proto`
- `docs/services.md`
- `docs/implementation-status.md`

### Checklist

- [x] Freeze rollback orchestration API semantics:
  - `POST /api/v1/tenants/{tenantId}/policy:rollback`
  - optional request body supports `targetVersion` and `distributionRegions`
  - backward compatible behavior when body is absent (rollback to previous version)
- [x] Freeze error model for version orchestration:
  - `TENANT_POLICY_VERSION_NOT_FOUND`
  - `TENANT_POLICY_ROLLBACK_VERSION_INVALID`
- [x] Extend `tenant.policy.changed` payload contract with orchestration metadata:
  - `sourcePolicyVersion`
  - `targetPolicyVersion`
  - `distributionRegions`
- [x] Keep runtime consumer compatibility (new fields optional)

## Task 2: Control-Plane Orchestration Implementation

### Target files

- `services/control-plane/src/main/java/com/kafkaasr/control/api/TenantPolicyController.java`
- `services/control-plane/src/main/java/com/kafkaasr/control/api/TenantPolicyRollbackRequest.java` (new)
- `services/control-plane/src/main/java/com/kafkaasr/control/service/TenantPolicyService.java`
- `services/control-plane/src/main/java/com/kafkaasr/control/service/ControlPlaneException.java`
- `services/control-plane/src/main/java/com/kafkaasr/control/policy/TenantPolicyRepository.java`
- `services/control-plane/src/main/java/com/kafkaasr/control/policy/RedisTenantPolicyRepository.java`

### Checklist

- [x] Add rollback request model with validation (`targetVersion`, `distributionRegions`)
- [x] Add service flow for rollback-to-version:
  - load current policy
  - locate target historical version
  - create new effective version (`current+1`) from target snapshot
- [x] Keep existing no-body rollback behavior as previous-version rollback
- [x] Add new domain errors and HTTP mapping for invalid/missing target version
- [x] Publish `tenant.policy.changed` with orchestration metadata

## Task 3: Cross-Region Distribution Intent Publishing

### Target files

- `services/control-plane/src/main/java/com/kafkaasr/control/events/TenantPolicyChangedPayload.java`
- `services/control-plane/src/main/java/com/kafkaasr/control/events/ControlKafkaProperties.java`
- `services/control-plane/src/main/resources/application.yml`
- `services/control-plane/src/main/java/com/kafkaasr/control/service/TenantPolicyService.java`

### Checklist

- [x] Persist and normalize requested distribution regions (dedupe + stable order)
- [x] Add publish-time metadata for distribution intent in `tenant.policy.changed`
- [x] Add metrics dimensions for orchestration and distribution:
  - `operation=ROLLED_BACK_TO_VERSION`
  - `distributionRegionsCount`

## Task 4: Tests and Documentation Closure

### Target files

- `services/control-plane/src/test/java/com/kafkaasr/control/service/TenantPolicyServiceTests.java`
- `services/control-plane/src/test/java/com/kafkaasr/control/api/TenantPolicyControllerTests.java`
- `services/control-plane/src/test/java/com/kafkaasr/control/api/TenantPolicyControllerAuthorizationTests.java`
- `services/control-plane/src/test/java/com/kafkaasr/control/policy/RedisTenantPolicyRepositoryTests.java`
- `docs/roadmap.md`
- `services/control-plane/README.md`

### Checklist

- [x] Add service tests for specified-version rollback success/failure matrix
- [x] Add controller tests for optional rollback request body
- [x] Add authz tests confirming rollback-to-version remains write-protected
- [x] Add repository tests for historical version lookup behavior
- [x] Sync roadmap/status docs for “version orchestration + cross-region distribution intent”

## Verification

- [x] `./gradlew :services:control-plane:test --no-daemon`
- [x] `tools/verify.sh`
