# tts-orchestrator

`tts-orchestrator` consumes `translation.result` events and produces `tts.request` events.

Current scope:

- Spring Boot module scaffold
- Kafka integration foundation for consume/publish flow
- Placeholder voice/cache pipeline with contract-aligned event mapping
- Configurable synthesis modes: `placeholder` / `http`

Out of scope in this phase:

- Production TTS runtime validation and model-side scaling controls
- `tts.chunk` and `tts.ready` event publishing
- Object storage/CDN distribution flow
