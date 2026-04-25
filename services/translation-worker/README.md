# translation-worker

`translation-worker` runs a two-stage flow:

- consume `asr.final` and publish `translation.request`
- consume `translation.request` and publish `translation.result`

Current scope:

- Spring Boot module scaffold
- Kafka integration foundation for consume/publish flow
- Placeholder translation pipeline with contract-aligned event mapping
- Configurable translation modes: `placeholder` / `http` / `openai`

Out of scope in this phase:

- Production OpenAI runtime validation and model-side scaling controls
- Terminology/glossary/context enhancement
- WebSocket subtitle downlink changes
