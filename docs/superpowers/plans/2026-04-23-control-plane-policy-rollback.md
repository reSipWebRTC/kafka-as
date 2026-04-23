# Plan: Control-Plane Policy Rollback

## Goal

Add a rollback capability for tenant policy so operators can roll back to the previous effective strategy without editing every field manually.

## Scope

- Add rollback HTTP API in `control-plane`
- Persist policy history snapshots for rollback
- Publish `tenant.policy.changed` with rollback operation
- Add tests and docs sync

## Task 1: Rollback API and Service Flow

### Target files

- `services/control-plane/src/main/java/com/kafkaasr/control/api/TenantPolicyController.java`
- `services/control-plane/src/main/java/com/kafkaasr/control/service/TenantPolicyService.java`
- `services/control-plane/src/main/java/com/kafkaasr/control/service/ControlPlaneException.java`

### Checklist

- [x] Add `POST /api/v1/tenants/{tenantId}/policy:rollback`
- [x] Add service rollback flow with version increment and event publish
- [x] Add domain error for rollback-not-available scenario
- [x] Keep auth behavior unchanged (rollback is write operation)

## Task 2: Repository History Support

### Target files

- `services/control-plane/src/main/java/com/kafkaasr/control/policy/TenantPolicyRepository.java`
- `services/control-plane/src/main/java/com/kafkaasr/control/policy/RedisTenantPolicyRepository.java`
- `services/control-plane/src/main/java/com/kafkaasr/control/policy/TenantPolicyStoreProperties.java`
- `services/control-plane/src/main/resources/application.yml`

### Checklist

- [x] Add history snapshot operations to repository interface
- [x] Persist latest snapshot into history before update
- [x] Support reading/removing previous snapshot for rollback
- [x] Add history retention configuration for Redis list

## Task 3: Tests, Contracts, and Docs

### Target files

- `services/control-plane/src/test/java/com/kafkaasr/control/service/TenantPolicyServiceTests.java`
- `services/control-plane/src/test/java/com/kafkaasr/control/api/TenantPolicyControllerTests.java`
- `services/control-plane/src/test/java/com/kafkaasr/control/policy/RedisTenantPolicyRepositoryTests.java`
- `api/json-schema/tenant.policy.changed.v1.json`
- `docs/contracts.md`
- `docs/services.md`
- `docs/implementation-status.md`
- `docs/roadmap.md`
- `services/control-plane/README.md`

### Checklist

- [x] Add rollback success/failure tests in service layer
- [x] Add rollback endpoint web test
- [x] Add repository history tests
- [x] Extend event schema operation enum with `ROLLED_BACK`
- [x] Update docs for rollback API and capability status

## Verification

- [x] `./gradlew :services:control-plane:test --no-daemon`
- [x] `tools/verify.sh`
