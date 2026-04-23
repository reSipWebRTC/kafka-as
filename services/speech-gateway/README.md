# speech-gateway

This module is the isolated starting point for `feature/gateway`.

Current scope:

- WebFlux application bootstrap
- Internal health endpoint
- WebSocket entrypoint for `/ws/audio`
- configurable token auth on WebSocket handshake (`Authorization: Bearer` or query `access_token`)
- direct Kafka publishing for raw `audio.frame` ingress (`audio.ingress.raw`)
- low-frequency `session.start` / `session.stop` forwarding to `session-orchestrator`
- downlink Kafka consumers for `asr.partial` / `translation.result` / `tts.chunk` / `tts.ready` / `session.control(CLOSED)`
- realtime push handling for `subtitle.partial` / `subtitle.final` / `tts.chunk` / `tts.ready` / `session.closed`

Out of scope for this skeleton:

- External IAM integration and tenant-scoped credential lifecycle
- Backpressure and rate limiting policies
- Advanced multi-stream downlink aggregation and QoS policy orchestration

Before adding behavior, align with:

- `docs/contracts.md`
- `docs/architecture.md`
- `docs/services.md`
