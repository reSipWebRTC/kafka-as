# Plan: Control-Plane Authentication and Authorization

## Goal

Upgrade control-plane auth from token-only authentication to configurable authentication + authorization:

- keep existing bearer token validation
- add permission checks by operation (`GET` read vs `PUT` write)
- add tenant-level access scope constraints

## Scope

- `services/control-plane` auth configuration model
- `services/control-plane` auth web filter decision logic and error mapping
- unit/integration tests for allow/deny matrix
- docs update for contracts and implementation status

## Task 1: Auth Model and Filter Upgrade

### Target files

- `services/control-plane/src/main/java/com/kafkaasr/control/auth/ControlPlaneAuthProperties.java`
- `services/control-plane/src/main/java/com/kafkaasr/control/auth/ControlPlaneAuthWebFilter.java`
- `services/control-plane/src/main/resources/application.yml`

### Checklist

- [x] Add optional credential rules (`token`, `read`, `write`, `tenantPatterns`)
- [x] Keep `control.auth.tokens` backward compatible (full access)
- [x] Distinguish unauthenticated (`401 AUTH_INVALID_TOKEN`) vs unauthorized (`403 AUTH_FORBIDDEN`)
- [x] Enforce tenant scope and method-level permission for `/api/v1/tenants/{tenantId}/policy`

## Task 2: Tests

### Target files

- `services/control-plane/src/test/java/com/kafkaasr/control/api/TenantPolicyControllerTests.java`
- `services/control-plane/src/test/java/com/kafkaasr/control/api/TenantPolicyControllerAuthorizationTests.java` (new)

### Checklist

- [x] Preserve existing token-only behavior
- [x] Add read-only token denied on write test
- [x] Add tenant-scope denied test
- [x] Add tenant-scope allowed test

## Task 3: Docs and Verification

### Target files

- `docs/contracts.md`
- `docs/services.md`
- `docs/implementation-status.md`

### Checklist

- [x] Update auth behavior notes and error code table
- [x] Document authorization capability and scope limits
- [x] Run `tools/verify.sh`
