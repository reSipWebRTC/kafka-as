# session-orchestrator

`session-orchestrator` handles low-frequency session lifecycle control and publishes `session.control` events.

Current scope:

- `POST /api/v1/sessions:start`
- `POST /api/v1/sessions/{sessionId}:stop`
- Redis-backed lifecycle state machine
- Kafka publication for `session.control`

Out of scope in this phase:

- ASR/translation aggregation
- timeout scheduler and compensation workflows
