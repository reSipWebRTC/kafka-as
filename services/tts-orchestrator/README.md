# tts-orchestrator

`tts-orchestrator` consumes `translation.result` events and produces `tts.request` events.

Current scope:

- Spring Boot module scaffold
- Kafka integration foundation for consume/publish flow
- Placeholder voice/cache pipeline with contract-aligned event mapping

Out of scope in this phase:

- Real TTS engine integration
- `tts.chunk` and `tts.ready` event publishing
- Object storage/CDN distribution flow
