# Plan: TTS Command Result Routing

## Goal

Implement PR4 delivery:

- keep translation path behavior unchanged for `TRANSLATION` tenants
- add SMART_HOME routing in `tts-orchestrator` driven by `command.result`
- publish `tts.request` / `tts.chunk` / `tts.ready` for both routed inputs

## Scope

- `services/tts-orchestrator` event ingestion and routing logic
- `tts` tenant policy model (`sessionMode`) alignment with control-plane response
- pipeline mapping support for `command.result`
- tests and docs synchronization

## Tasks

### 1) Routing model and config

- Files:
  - `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/events/TtsKafkaProperties.java`
  - `services/tts-orchestrator/src/main/resources/application.yml`
  - `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/policy/*`
- Checklist:
  - [x] add `tts.kafka.command-result-topic` config
  - [x] extend tenant policy with `sessionMode` (`TRANSLATION` / `SMART_HOME`)
  - [x] preserve existing retry/backoff/DLQ policy behavior

### 2) Consumer and pipeline flow

- Files:
  - `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/kafka/TranslationResultConsumer.java`
  - `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/pipeline/TtsRequestPipelineService.java`
  - `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/events/CommandResultEvent.java`
  - `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/events/CommandResultPayload.java`
- Checklist:
  - [x] keep `translation.result` processing only for `TRANSLATION` tenants
  - [x] add `command.result` consumer and process only for `SMART_HOME` tenants
  - [x] map `command.result` payload text (`ttsText`/`replyText`) to `tts.*` outputs
  - [x] keep idempotency/retry/DLQ/compensation behavior in both paths

### 3) Validation and docs

- Files:
  - `services/tts-orchestrator/src/test/java/com/kafkaasr/tts/kafka/TranslationResultConsumerTests.java`
  - `services/tts-orchestrator/src/test/java/com/kafkaasr/tts/pipeline/TtsRequestPipelineServiceTests.java`
  - `services/tts-orchestrator/src/test/java/com/kafkaasr/tts/policy/TenantReliabilityPolicyResolverTests.java`
  - `services/tts-orchestrator/README.md`
  - `docs/contracts.md`
  - `docs/event-model.md`
  - `docs/implementation-status.md`
  - `docs/services.md`
  - `docs/README.md`
- Checklist:
  - [x] add/adjust unit tests for routing and command mapping
  - [x] run `./gradlew :services:tts-orchestrator:test`
  - [x] run `tools/verify.sh`
  - [x] sync docs to remove outdated “command.result routing pending” statements
