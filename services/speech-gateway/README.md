# speech-gateway

This module is the isolated starting point for `feature/gateway`.

Current scope:

- WebFlux application bootstrap
- Internal health endpoint
- WebSocket entrypoint for `/ws/audio`
- direct Kafka publishing for raw `audio.frame` ingress (`audio.ingress.raw`)
- low-frequency `session.start` / `session.stop` forwarding to `session-orchestrator`

Out of scope for this skeleton:

- Real authentication
- Backpressure and rate limiting policies
- Full realtime response push handling (`subtitle.*`, `session.closed`)

Before adding behavior, align with:

- `docs/contracts.md`
- `docs/architecture.md`
- `docs/services.md`
