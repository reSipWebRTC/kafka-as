# command-worker

This module handles smart-home command runtime orchestration on the Kafka side.

Current scope:

- consume `asr.final` and process only tenants in `SMART_HOME` session mode
- consume `command.confirm.request` and submit confirm decisions to smartHomeNlu
- call smartHomeNlu HTTP APIs:
  - `POST /api/v1/command`
  - `POST /api/v1/confirm`
- publish `command.result` back to Kafka
- tenant-aware retry/backoff/DLQ suffix, idempotency guard, and compensation signal
- consume `tenant.policy.changed` and refresh tenant routing/reliability cache

Out of scope for this module:

- TTS route decision and rendering orchestration (handled in `tts-orchestrator`)
- gateway websocket protocol and downlink push (handled in `speech-gateway`)
- smartHomeNlu internal policy/execution implementation details

Before adding behavior, align with:

- `docs/contracts.md`
- `docs/event-model.md`
- `docs/services.md`
