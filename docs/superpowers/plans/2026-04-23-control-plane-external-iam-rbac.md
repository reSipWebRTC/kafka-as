# Plan: Control-Plane External IAM/RBAC Integration (Static Credentials First)

## Goal

Integrate external IAM/RBAC for `control-plane` while keeping current static token/credentials as default and fallback.

## Stage Plan

### Stage 1 (this change): Auth backend abstraction

- [x] Introduce `AuthBackend` contract and unified auth decision model
- [x] Move current static token/credentials logic into `StaticAuthBackend`
- [x] Keep `ControlPlaneAuthWebFilter` as orchestration + error mapping layer
- [x] Keep API behavior unchanged (`401 AUTH_INVALID_TOKEN`, `403 AUTH_FORBIDDEN`)

### Stage 2 (next): External IAM backend

- [ ] Add `ExternalIamAuthBackend` (JWT/JWKS first)
- [ ] Add config for issuer/audience/jwks and claim mapping
- [ ] Implement permission + tenant scope mapping from claims

### Stage 3 (next): Mode switch and rollout

- [ ] Add `control.auth.mode` (`static` default, `external-iam`, `hybrid`)
- [ ] Add backend-level metrics and deny reason counters
- [ ] Add rollout runbook and preprod drill evidence

## Verification

- [x] `./gradlew :services:control-plane:test --no-daemon`
- [x] `tools/verify.sh`
