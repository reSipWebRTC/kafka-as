# Subtitle Downlink Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the first end-to-end WebSocket downlink path in `speech-gateway` so clients can receive `subtitle.partial`, `subtitle.final`, and `session.closed`, while also accepting `session.ping`.

**Source of truth:**
- `docs/contracts.md`
- `docs/implementation-status.md`
- `docs/architecture.md`
- `services/speech-gateway/*`

---

### Task 1: Accept `session.ping` in gateway ingress

**Files:**
- `services/speech-gateway/src/main/java/com/kafkaasr/gateway/ws/protocol/SessionPingMessage.java`
- `services/speech-gateway/src/main/java/com/kafkaasr/gateway/ws/protocol/SessionPingMessageDecoder.java`
- `services/speech-gateway/src/main/java/com/kafkaasr/gateway/ws/protocol/GatewayMessageRouter.java`
- `services/speech-gateway/src/test/java/com/kafkaasr/gateway/ws/protocol/GatewayMessageRouterTests.java`
- `services/speech-gateway/src/test/java/com/kafkaasr/gateway/ws/protocol/SessionPingMessageDecoderTests.java`

- [x] Add ping message model + decoder with validation.
- [x] Route `session.ping` as a successful no-op in `GatewayMessageRouter`.
- [x] Keep ingress metrics aligned with new message type.
- [x] Add/adjust tests for ping decode and route behavior.

### Task 2: Add session registry and shared downlink publisher

**Files:**
- `services/speech-gateway/src/main/java/com/kafkaasr/gateway/ws/GatewaySessionRegistry.java`
- `services/speech-gateway/src/main/java/com/kafkaasr/gateway/ws/GatewayDownlinkPublisher.java`
- `services/speech-gateway/src/main/java/com/kafkaasr/gateway/ws/protocol/SubtitlePartialResponse.java`
- `services/speech-gateway/src/main/java/com/kafkaasr/gateway/ws/protocol/SubtitleFinalResponse.java`
- `services/speech-gateway/src/main/java/com/kafkaasr/gateway/ws/protocol/SessionClosedResponse.java`
- `services/speech-gateway/src/main/java/com/kafkaasr/gateway/ws/AudioWebSocketHandler.java`
- `services/speech-gateway/src/test/java/com/kafkaasr/gateway/ws/GatewayDownlinkPublisherTests.java`

- [x] Track active WebSocket connections and bind `sessionId` to connection.
- [x] Send all outbound payloads through one registry-backed channel per connection.
- [x] Route protocol errors via registry channel before close.
- [x] Add tests for downlink payload serialization and close behavior.

### Task 3: Consume Kafka events and downlink to WebSocket

**Files:**
- `services/speech-gateway/src/main/java/com/kafkaasr/gateway/ws/downlink/events/*.java`
- `services/speech-gateway/src/main/java/com/kafkaasr/gateway/ws/downlink/AsrFinalDownlinkConsumer.java`
- `services/speech-gateway/src/main/java/com/kafkaasr/gateway/ws/downlink/TranslationResultDownlinkConsumer.java`
- `services/speech-gateway/src/main/java/com/kafkaasr/gateway/ws/downlink/SessionControlDownlinkConsumer.java`
- `services/speech-gateway/src/main/resources/application.yml`
- `services/speech-gateway/src/test/java/com/kafkaasr/gateway/ws/downlink/*.java`

- [x] Consume `asr.final` and publish `subtitle.partial`.
- [x] Consume `translation.result` and publish `subtitle.final`.
- [x] Consume `session.control` with `status=CLOSED` and publish `session.closed` then close socket.
- [x] Add downlink Kafka properties and consumer defaults.
- [x] Add unit tests for three downlink consumers.

### Task 4: Sync docs and verify

**Files:**
- `docs/contracts.md`
- `docs/implementation-status.md`
- `docs/architecture.md`
- `docs/services.md`
- `docs/roadmap.md`
- `docs/README.md`

- [x] Update docs to reflect implemented `session.ping` and websocket downlink.
- [x] Keep unresolved gaps (`asr.partial`, real engines, DLQ/backpressure, etc.) explicit.
- [x] Run `tools/verify.sh`.

## Verification

- [x] `./gradlew :services:speech-gateway:test --no-daemon`
- [x] `tools/verify.sh`
