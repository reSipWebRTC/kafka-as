# session-orchestrator

`session-orchestrator` handles low-frequency session lifecycle control and publishes `session.control` events.

Current scope:

- `POST /api/v1/sessions:start`
- `POST /api/v1/sessions/{sessionId}:stop`
- tenant policy fetch from `control-plane` during session start
- policy enforcement: enabled flag, language pair match, max concurrent sessions
- Redis-backed lifecycle state machine
- Kafka publication for `session.control`
- progress aggregation from pipeline events (`asr.partial` / `asr.final` / `translation.result` / `tts.ready` / `command.result`)
- timeout orchestration (`idleTimeout` / `hardTimeout`) with auto-close to `session.control(status=CLOSED)`
- timeout/stalled compensation signal publication to `platform.compensation` and `platform.audit`

Out of scope in this phase:

- advanced multi-service compensation saga and rollback choreography
