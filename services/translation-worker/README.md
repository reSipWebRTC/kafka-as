# translation-worker

`translation-worker` consumes `asr.final` events and produces `translation.result` events.

Current scope:

- Spring Boot module scaffold
- Kafka integration foundation for consume/publish flow
- Placeholder translation pipeline with contract-aligned event mapping

Out of scope in this phase:

- Real LLM/MT engine integration
- Terminology/glossary/context enhancement
- WebSocket subtitle downlink changes
