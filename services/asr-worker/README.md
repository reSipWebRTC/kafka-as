# asr-worker

`asr-worker` consumes `audio.ingress.raw` events and produces `asr.partial` + `asr.final` events.

Current scope:

- Spring Boot module scaffold
- Kafka integration foundation for ingest/publish flow
- Health and metrics endpoints for worker runtime
- Placeholder partial/final dual-event publishing

Out of scope in this phase:

- Real FunASR model runtime integration
- Translation and websocket downstream delivery
