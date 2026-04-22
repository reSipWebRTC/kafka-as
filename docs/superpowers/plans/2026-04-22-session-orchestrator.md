# Session Orchestrator Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wire `speech-gateway` WebSocket control messages to `session-orchestrator` start/stop HTTP APIs while keeping the `audio.frame -> Kafka` high-frequency path unchanged.

**Architecture:** Add a low-frequency control client in `speech-gateway` (WebClient + config + error mapping), then route inbound WS messages by `type`: `audio.frame` keeps existing publisher logic, `session.start`/`session.stop` call orchestrator. Preserve existing gateway `session.error` wire format for client-visible failures.

**Tech Stack:** Spring Boot 3 (WebFlux), Reactor, Spring Kafka, JUnit 5, Mockito.

---

### Task 1: Add Session-Orchestrator Client and Config

**Files:**
- Create: `services/speech-gateway/src/main/java/com/kafkaasr/gateway/session/*`
- Modify: `services/speech-gateway/src/main/resources/application.yml`
- Test: `services/speech-gateway/src/test/java/com/kafkaasr/gateway/session/HttpSessionControlClientTests.java`

- [ ] Add gateway configuration properties for orchestrator base URL, timeout, and enabled toggle.
- [ ] Implement `SessionControlClient` interface + HTTP/WebClient implementation for:
  - `POST /api/v1/sessions:start`
  - `POST /api/v1/sessions/{sessionId}:stop`
- [ ] Add request/response DTOs and map orchestrator non-2xx responses into gateway-safe exceptions with code/sessionId/message.
- [ ] Add unit tests covering success and error mapping.

### Task 2: Route WebSocket Message Types Without Touching Audio Path

**Files:**
- Modify: `services/speech-gateway/src/main/java/com/kafkaasr/gateway/ws/AudioWebSocketHandler.java`
- Create: `services/speech-gateway/src/main/java/com/kafkaasr/gateway/ws/protocol/GatewayMessageRouter.java`
- Create: `services/speech-gateway/src/main/java/com/kafkaasr/gateway/ws/protocol/SessionStartMessageDecoder.java`
- Create: `services/speech-gateway/src/main/java/com/kafkaasr/gateway/ws/protocol/SessionStopMessageDecoder.java`
- Create: `services/speech-gateway/src/main/java/com/kafkaasr/gateway/ws/protocol/SessionStartMessage.java`
- Create: `services/speech-gateway/src/main/java/com/kafkaasr/gateway/ws/protocol/SessionStopMessage.java`
- Test: `services/speech-gateway/src/test/java/com/kafkaasr/gateway/ws/AudioWebSocketHandlerTests.java`

- [ ] Introduce message-type routing for `audio.frame`, `session.start`, and `session.stop`.
- [ ] Keep `audio.frame` decode + publish flow identical in behavior and output.
- [ ] Wire `session.start` and `session.stop` to `SessionControlClient`.
- [ ] Ensure gateway emits `session.error` with mapped code/message/sessionId for validation and orchestrator failures.

### Task 3: Verify Behavior and Guard Regressions

**Files:**
- Modify/Create tests in:
  - `services/speech-gateway/src/test/java/com/kafkaasr/gateway/ws/protocol/AudioFrameMessageDecoderTests.java`
  - `services/speech-gateway/src/test/java/com/kafkaasr/gateway/ws/AudioWebSocketHandlerTests.java`
  - `services/speech-gateway/src/test/java/com/kafkaasr/gateway/session/HttpSessionControlClientTests.java`

- [ ] Add/adjust tests proving `audio.frame` behavior remains unchanged.
- [ ] Add tests for `session.start`/`session.stop` happy path and mapped error path.
- [ ] Run full repository verification with `tools/verify.sh`.
