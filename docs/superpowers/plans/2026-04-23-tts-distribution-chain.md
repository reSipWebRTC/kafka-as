# Plan: TTS Distribution Chain

## Goal

补齐 `speech-gateway` 对 `tts.chunk` / `tts.ready` 的下行消费与 WebSocket 回推，形成仓库内可验证的 TTS 分发后链路最小闭环。

## Tasks

### Task 1: Gateway 下行能力补齐

- [x] 扩展 `GatewayDownlinkProperties`：新增 `tts.chunk` / `tts.ready` topic 配置
- [x] 新增 `tts.chunk` / `tts.ready` 事件模型（gateway downlink events）
- [x] 扩展 `GatewayDownlinkPublisher`：新增 `publishTtsChunk` / `publishTtsReady`
- [x] 新增 `TtsChunkDownlinkConsumer` / `TtsReadyDownlinkConsumer`

### Task 2: 测试补齐

- [x] 新增 `TtsChunkDownlinkConsumerTests`
- [x] 新增 `TtsReadyDownlinkConsumerTests`
- [x] 扩展 `GatewayDownlinkPublisherTests`
- [x] 扩展 `DownlinkE2EStabilityTests` 覆盖 `tts.chunk` / `tts.ready`

### Task 3: 文档同步

- [x] 更新 `docs/contracts.md`（WebSocket 下行类型与实现注记）
- [x] 更新 `docs/implementation-status.md`
- [x] 更新 `docs/services.md` 与 `services/speech-gateway/README.md`

## Verification

- [x] `./gradlew :services:speech-gateway:test`
- [x] `tools/verify.sh`
