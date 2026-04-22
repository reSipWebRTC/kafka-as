# Control Plane Redis Store Plan

## Goal

Replace `control-plane` in-memory tenant policy repository with Redis-backed storage while keeping API/controller/service interfaces unchanged.

## Task 1: Add Redis Dependency and Store Config

- [x] Add `spring-boot-starter-data-redis` to `services/control-plane`.
- [x] Add Redis connection config and tenant-policy key/ttl config in `application.yml`.
- [x] Add typed store properties class for key prefix and TTL.

## Task 2: Replace Repository Implementation

- [x] Add `RedisTenantPolicyRepository` implementing `TenantPolicyRepository`.
- [x] Remove runtime usage of `InMemoryTenantPolicyRepository` in production wiring.
- [x] Keep serialization format stable via Jackson JSON.

## Task 3: Update Tests for Redis-backed Wiring

- [x] Add repository unit tests with mocked `StringRedisTemplate`.
- [x] Update controller tests to avoid real Redis dependency by mocking service behavior.
- [x] Keep service tests unchanged for deterministic business semantics.

## Task 4: Verify

- [x] Run `./gradlew :services:control-plane:test --no-daemon`.
- [x] Run `tools/verify.sh`.
