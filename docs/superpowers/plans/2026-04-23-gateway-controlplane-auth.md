# Plan: Gateway + Control-Plane Auth Baseline

## Goal

Add a first production-safe auth baseline for external entrypoints:

- `speech-gateway` WebSocket `/ws/audio`
- `control-plane` tenant policy APIs `/api/v1/tenants/**`

## Scope

- Static token verification via configuration (enable flag + token list)
- Contract-aligned auth failure semantics (`AUTH_INVALID_TOKEN`)
- Test coverage for allow/deny paths
- Docs/status synchronization

## Tasks

### 1) Gateway WebSocket token auth

- Files:
  - `services/speech-gateway/src/main/java/com/kafkaasr/gateway/auth/*`
  - `services/speech-gateway/src/main/java/com/kafkaasr/gateway/ws/AudioWebSocketHandler.java`
  - `services/speech-gateway/src/main/resources/application.yml`
  - `services/speech-gateway/src/test/java/com/kafkaasr/gateway/ws/AudioWebSocketAuthTests.java`
- Checklist:
  - [x] Add auth properties and token authenticator
  - [x] Enforce auth in WebSocket handler
  - [x] Return `session.error(code=AUTH_INVALID_TOKEN)` and close on failure
  - [x] Add WebSocket auth integration tests

### 2) Control-plane HTTP token auth

- Files:
  - `services/control-plane/src/main/java/com/kafkaasr/control/auth/*`
  - `services/control-plane/src/main/resources/application.yml`
  - `services/control-plane/src/test/java/com/kafkaasr/control/api/TenantPolicyControllerTests.java`
- Checklist:
  - [x] Add auth properties and WebFilter for `/api/v1/tenants/**`
  - [x] Return `401 + AUTH_INVALID_TOKEN` on missing/invalid token
  - [x] Keep non-tenant endpoints unaffected
  - [x] Extend controller tests for authenticated and unauthenticated cases

### 3) Docs sync

- Files:
  - `docs/contracts.md`
  - `docs/services.md`
  - `docs/implementation-status.md`
  - `docs/roadmap.md`
  - `services/speech-gateway/README.md`
  - `services/control-plane/README.md`
- Checklist:
  - [x] Reflect new auth baseline as implemented
  - [x] Keep IAM/RBAC and dynamic credential governance in “not yet implemented”
