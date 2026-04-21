# speech-gateway

This module is the isolated starting point for `feature/gateway`.

Current scope:

- WebFlux application bootstrap
- Internal health endpoint
- WebSocket entrypoint placeholder for `/ws/audio`
- Kafka publishing seam for raw audio ingress

Out of scope for this skeleton:

- Real authentication
- Session orchestration calls
- Real Kafka publishing
- Backpressure and rate limiting policies
- Full client/server protocol handling

Before adding behavior, align with:

- `docs/contracts.md`
- `docs/architecture.md`
- `docs/services.md`

