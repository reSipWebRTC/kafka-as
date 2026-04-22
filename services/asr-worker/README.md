# asr-worker

`asr-worker` consumes `audio.ingress.raw` events and produces `asr.final` events.

Current scope:

- Spring Boot module scaffold
- Kafka integration foundation for ingest/publish flow
- Health and metrics endpoints for worker runtime

Out of scope in this phase:

- Real FunASR model runtime integration
- `asr.partial` event publishing
- Translation and websocket downstream delivery
