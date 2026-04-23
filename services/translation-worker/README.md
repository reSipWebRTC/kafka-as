# translation-worker

`translation-worker` consumes `asr.final` events and produces `translation.result` events.

Current scope:

- Spring Boot module scaffold
- Kafka integration foundation for consume/publish flow
- Placeholder translation pipeline with contract-aligned event mapping
- Configurable translation modes: `placeholder` / `http` / `openai`
- OpenAI production-drill baseline: health probe, bounded concurrency, explicit error semantics, and engine-level metrics

Out of scope in this phase:

- OpenAI real-traffic capacity validation and fault drills
- Terminology/glossary/context enhancement
- WebSocket subtitle downlink changes
