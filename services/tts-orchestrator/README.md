# tts-orchestrator

`tts-orchestrator` consumes `translation.result` events and produces `tts.request`, `tts.chunk`, and `tts.ready` events.

Current scope:

- Spring Boot module scaffold
- Kafka integration foundation for consume/publish flow
- Placeholder voice/cache pipeline with contract-aligned event mapping
- Configurable synthesis modes: `placeholder` / `http`
- HTTP synthesis production-drill baseline: health probe, bounded concurrency, explicit error semantics, and engine-level metrics
- Configurable object storage upload for `tts.ready` playback URL (`tts.storage`: `none` / `s3`, MinIO-compatible endpoint/path-style)
- Configurable CDN delivery policy for uploaded playback URLs (`cache-control`, `expires/sig` signing)
- CDN governance baseline for playback distribution (tenant-region routing, origin fallback, cache scope + shard path strategy)

Out of scope in this phase:

- TTS real-traffic capacity validation and fault drills
- Large-scale playback distribution governance (dynamic multi-origin routing, watermarking, and region-level traffic policy orchestration)
