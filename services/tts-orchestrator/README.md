# tts-orchestrator

`tts-orchestrator` consumes `translation.result` events and produces `tts.request`, `tts.chunk`, and `tts.ready` events.

Current scope:

- Spring Boot module scaffold
- Kafka integration foundation for consume/publish flow
- Placeholder voice/cache pipeline with contract-aligned event mapping
- Configurable synthesis modes: `placeholder` / `http`
- Configurable object storage upload for `tts.ready` playback URL (`tts.storage`: `none` / `s3`, MinIO-compatible endpoint/path-style)
- Configurable CDN delivery policy for uploaded playback URLs (`cache-control`, `expires/sig` signing)

Out of scope in this phase:

- Production TTS runtime validation and model-side scaling controls
- Large-scale playback distribution governance (advanced CDN routing, watermarking, regional fallback)
