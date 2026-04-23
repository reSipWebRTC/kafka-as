# asr-worker

`asr-worker` consumes `audio.ingress.raw` events and produces `asr.partial` + `asr.final` events.

Current scope:

- Spring Boot module scaffold
- Kafka integration foundation for ingest/publish flow
- Health and metrics endpoints for worker runtime
- Placeholder partial/final dual-event publishing
- Configurable inference modes: `placeholder` / `http` / `funasr`
- Configurable VAD segmentation (`asr.inference.vad`) for silence-boundary `asr.final` emission
- FunASR production drill baseline:
  - availability probe (`health`) with short-lived cache
  - bounded inference concurrency guard
  - explicit error-code mapping (`ASR_TIMEOUT` / `ASR_PROVIDER_*` / `ASR_INVALID_AUDIO`)
  - inference-level metrics (`asr.funasr.*`)

Key VAD configs:

- `ASR_VAD_ENABLED`
- `ASR_VAD_SILENCE_FRAMES_TO_FINALIZE`
- `ASR_VAD_MIN_ACTIVE_FRAMES_PER_SEGMENT`
- `ASR_VAD_SILENCE_AMPLITUDE_THRESHOLD`
- `ASR_VAD_AUDIO_CODEC`

Key FunASR drill configs:

- `ASR_FUNASR_MAX_CONCURRENT_REQUESTS`
- `ASR_FUNASR_HEALTH_ENABLED`
- `ASR_FUNASR_HEALTH_PATH`
- `ASR_FUNASR_HEALTH_TIMEOUT_MS`
- `ASR_FUNASR_HEALTH_CACHE_TTL_MS`
- `ASR_FUNASR_HEALTH_FAIL_OPEN_ON_ERROR`

Out of scope in this phase:

- Full production cluster drill (real traffic replay, capacity saturation, failover game day)
- Advanced ASR context window management across long sessions
- Translation and websocket downstream delivery
