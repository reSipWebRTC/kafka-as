# Session Orchestrator Plan

## Goal

Implement the minimum production-shaped `session-orchestrator` without touching the high-frequency audio path.

This phase only covers low-frequency control flow:

- session start
- session stop
- session lifecycle state
- `session.control` event publishing
- idempotent control handling

## Constraints

- Do not proxy audio frames.
- Do not add GPU or ASR logic here.
- Keep `docs/` and `api/` as the source of truth.
- Prefer seams that can switch from in-memory to Redis/Kafka-backed implementations later.

## Scope

### In

- new module `services/session-orchestrator`
- Spring Boot service skeleton
- control API for `session.start` and `session.stop`
- session state machine with explicit statuses
- Kafka publisher for `session.control`
- basic tests for state transitions and event mapping

### Out

- Redis persistence
- gateway callback push channel
- ASR/translation result aggregation
- retry scheduler and timeout wheel
- tenant policy engine

## Target Files

- `settings.gradle.kts`
- `services/session-orchestrator/build.gradle.kts`
- `services/session-orchestrator/src/main/java/...`
- `services/session-orchestrator/src/main/resources/application.yml`
- `services/session-orchestrator/src/test/java/...`
- `docs/contracts.md` if control payload needs to be tightened
- `api/protobuf/realtime_speech.proto` if control API messages need additions

## Milestones

1. Add module skeleton and health endpoint.
2. Model session states and transition rules.
3. Add `POST /api/v1/sessions:start` and `POST /api/v1/sessions/{sessionId}:stop`.
4. Publish `session.control` Kafka events keyed by `sessionId`.
5. Add tests and run `./tools/verify.sh`.

## Design Notes

- Start with an in-memory `SessionRegistry` behind an interface.
- Keep event envelope aligned with `docs/event-model.md`.
- Use `sessionId` as Kafka key.
- Generate `eventId`, `traceId`, `idempotencyKey` inside the orchestrator for control events.
- Reserve Redis integration behind a repository seam rather than baking it into controllers.

## Exit Criteria

- `session-orchestrator` starts locally.
- start/stop control endpoints compile and are covered by tests.
- `session.control` events are emitted in the frozen envelope format.
- `./tools/verify.sh` passes in the worktree.
