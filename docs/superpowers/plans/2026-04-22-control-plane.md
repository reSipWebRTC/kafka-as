# Control Plane Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the first runnable `control-plane` service that manages tenant policy via HTTP APIs with clear validation, versioned upsert semantics, and baseline observability.

**Architecture:** Introduce a new `services/control-plane` Spring Boot module with WebFlux APIs (`PUT/GET tenant policy`), one domain service, and one repository abstraction. In this phase, use in-memory storage behind an interface so future Redis persistence can replace it without API/controller changes.

**Tech Stack:** Spring Boot 3, WebFlux, Validation, Micrometer, JUnit 5, Reactor Test.

**Source of truth:**
- `docs/architecture.md`
- `docs/services.md`
- `docs/roadmap.md`

---

### Task 1: Scaffold `control-plane` Module

**Files:**
- Modify: `settings.gradle.kts`
- Create: `services/control-plane/build.gradle.kts`
- Create: `services/control-plane/README.md`
- Create: `services/control-plane/src/main/java/com/kafkaasr/control/ControlPlaneApplication.java`
- Create: `services/control-plane/src/main/resources/application.yml`
- Create: `services/control-plane/src/test/java/com/kafkaasr/control/ControlPlaneApplicationTests.java`

- [x] Register `:services:control-plane` in Gradle settings.
- [x] Add baseline dependencies (`actuator`, `webflux`, `validation`, observability runtimes, tests).
- [x] Add baseline app bootstrap and management/tracing config.
- [x] Document scope and non-goals in module README.

### Task 2: Implement Tenant Policy Domain and Service

**Files:**
- Create: `services/control-plane/src/main/java/com/kafkaasr/control/policy/TenantPolicyState.java`
- Create: `services/control-plane/src/main/java/com/kafkaasr/control/policy/TenantPolicyRepository.java`
- Create: `services/control-plane/src/main/java/com/kafkaasr/control/policy/InMemoryTenantPolicyRepository.java`
- Create: `services/control-plane/src/main/java/com/kafkaasr/control/service/TenantPolicyService.java`
- Create: `services/control-plane/src/main/java/com/kafkaasr/control/service/ControlPlaneException.java`

- [x] Add tenant policy state model with version/updated timestamp.
- [x] Add repository abstraction and in-memory implementation.
- [x] Implement upsert/get service methods with deterministic create/update behavior.
- [x] Add baseline service metrics for upsert/get success and failures.

### Task 3: Expose HTTP API with Validation and Error Mapping

**Files:**
- Create: `services/control-plane/src/main/java/com/kafkaasr/control/api/TenantPolicyController.java`
- Create: `services/control-plane/src/main/java/com/kafkaasr/control/api/TenantPolicyUpsertRequest.java`
- Create: `services/control-plane/src/main/java/com/kafkaasr/control/api/TenantPolicyResponse.java`
- Create: `services/control-plane/src/main/java/com/kafkaasr/control/api/ControlPlaneErrorResponse.java`
- Create: `services/control-plane/src/main/java/com/kafkaasr/control/api/ControlPlaneExceptionHandler.java`

- [x] Add `PUT /api/v1/tenants/{tenantId}/policy` for create/update policy.
- [x] Add `GET /api/v1/tenants/{tenantId}/policy` for query policy.
- [x] Map validation and domain errors to stable error codes and status.
- [x] Keep controller thin and delegate behavior to service.

### Task 4: Add Tests and Run Full Verification

**Files:**
- Create: `services/control-plane/src/test/java/com/kafkaasr/control/service/TenantPolicyServiceTests.java`
- Create: `services/control-plane/src/test/java/com/kafkaasr/control/api/TenantPolicyControllerTests.java`

- [x] Test service create/update/get behavior and error path.
- [x] Test HTTP API success, validation failure, and not-found mapping.
- [x] Run:
  - `./gradlew :services:control-plane:test --no-daemon`
  - `tools/verify.sh`

## Non-goals in This Feature

- No authn/authz implementation yet.
- No persistent storage backend (Redis/DB) yet.
- No dynamic policy push to other services yet.

## Exit Criteria

- New `control-plane` module builds and tests pass.
- Tenant policy API supports deterministic upsert/get behavior.
- Repository verification passes with `tools/verify.sh`.
