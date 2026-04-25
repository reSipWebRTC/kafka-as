# Plan: Command Worker Smart-Home Runtime

## Goal

Implement PR3 delivery:

- add `command-worker` service
- consume `asr.final` (SMART_HOME only) and `command.confirm.request`
- call smartHomeNlu `/api/v1/command` and `/api/v1/confirm`
- publish `command.result`
- integrate retry/DLQ/idempotency/compensation

## Scope

- new service module `services/command-worker`
- Kafka consumer/publisher chain and smartHome HTTP client
- tenant policy resolver with `sessionMode` routing gate
- tests for message mapping, error path, and idempotency
- docs/status synchronization

## Tasks

### 1) Service skeleton

- Files:
  - `settings.gradle.kts`
  - `services/command-worker/**`
- Checklist:
  - [x] register Gradle module
  - [x] add Spring Boot app/config/README

### 2) Runtime flow

- Files:
  - `services/command-worker/src/main/java/com/kafkaasr/command/{events,kafka,pipeline,policy}/**`
- Checklist:
  - [x] consume `asr.final` and gate by `sessionMode=SMART_HOME`
  - [x] consume `command.confirm.request`
  - [x] call smartHomeNlu command/confirm APIs
  - [x] publish `command.result`
  - [x] apply tenant-aware retry/backoff/DLQ/idempotency/compensation

### 3) Validation and docs

- Files:
  - `services/command-worker/src/test/**`
  - `docs/contracts.md`
  - `docs/event-model.md`
  - `docs/services.md`
  - `docs/implementation-status.md`
  - `docs/README.md`
- Checklist:
  - [x] add unit tests for core mapping and failure paths
  - [x] run `:services:command-worker:test`
  - [x] run `tools/verify.sh`
  - [x] sync docs with implemented state
