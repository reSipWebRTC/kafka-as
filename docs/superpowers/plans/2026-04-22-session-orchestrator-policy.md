# Session Orchestrator Tenant Policy Plan

## Goal

Integrate tenant policy lookup into `session-orchestrator` `session.start` flow by calling `control-plane` and enforcing policy constraints before session creation.

## Task 1: Add Control-Plane Policy Client

- [x] Add typed HTTP client to call `GET /api/v1/tenants/{tenantId}/policy`.
- [x] Add configurable `control-plane` base URL and request timeout.
- [x] Map policy-not-found and transport failures to explicit orchestrator control errors.

## Task 2: Enforce Policy During Session Start

- [x] Apply `enabled` gate before creating session.
- [x] Validate requested `sourceLang/targetLang` against tenant policy.
- [x] Enforce tenant-level `maxConcurrentSessions` limit for active sessions.

## Task 3: Tests and Documentation

- [x] Add unit tests for policy pass/fail paths in `SessionLifecycleService`.
- [x] Update README and configuration docs for the new dependency on `control-plane`.

## Task 4: Verify

- [x] Run `./gradlew :services:session-orchestrator:test --no-daemon`.
- [x] Run `tools/verify.sh`.
