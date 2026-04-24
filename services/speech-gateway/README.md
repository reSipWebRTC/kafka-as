# speech-gateway

This module is the isolated starting point for `feature/gateway`.

Current scope:

- WebFlux application bootstrap
- Internal health endpoint
- WebSocket entrypoint for `/ws/audio`
- configurable token auth on WebSocket handshake (`Authorization: Bearer` or query `access_token`)
- direct Kafka publishing for raw `audio.frame` ingress (`audio.ingress.raw`) and command confirmation ingress (`command.confirm.request`)
- low-frequency `session.start` / `session.stop` forwarding to `session-orchestrator`
- downlink Kafka consumers for `asr.partial` / `translation.result` / `tts.chunk` / `tts.ready` / `command.result` / `session.control(CLOSED)`
- realtime push handling for `subtitle.partial` / `subtitle.final` / `tts.chunk` / `tts.ready` / `command.result` / `session.closed`

Out of scope for this skeleton:

- External IAM integration and tenant-scoped credential lifecycle
- Advanced backpressure and rate limiting policy orchestration
- Advanced multi-stream downlink aggregation and QoS policy orchestration

Before adding behavior, align with:

- `docs/contracts.md`
- `docs/architecture.md`
- `docs/services.md`
