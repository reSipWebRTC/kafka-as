# asr-worker

`asr-worker` consumes `audio.ingress.raw` events and produces `asr.partial` + `asr.final` events.

Current scope:

- Spring Boot module scaffold
- Kafka integration foundation for ingest/publish flow
- Health and metrics endpoints for worker runtime
- Placeholder partial/final dual-event publishing
- Configurable inference modes: `placeholder` / `http` / `funasr`
- Configurable VAD segmentation (`asr.inference.vad`) for silence-boundary `asr.final` emission

Key VAD configs:

- `ASR_VAD_ENABLED`
- `ASR_VAD_SILENCE_FRAMES_TO_FINALIZE`
- `ASR_VAD_MIN_ACTIVE_FRAMES_PER_SEGMENT`
- `ASR_VAD_SILENCE_AMPLITUDE_THRESHOLD`
- `ASR_VAD_AUDIO_CODEC`

Out of scope in this phase:

- Production FunASR runtime validation and model-side scaling controls
- Advanced ASR context window management across long sessions
- Translation and websocket downstream delivery
