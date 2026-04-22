# Contracts

## 1. 目标

本文件用于冻结 `Phase 0` 必须统一的接口与事件契约，避免各服务各自定义格式导致后期频繁返工。

冻结范围：

- WebSocket 上下行协议（实时链路）
- 统一事件 Envelope（跨服务异步链路）
- 第一批核心事件类型与字段
- 错误码与版本演进规则

## 1.1 当前实现注记（2026-04-22）

本文件仍然是外部行为的权威定义，但当前仓库只实现了其中一部分。

当前已经落地：

- WebSocket 上行：`session.start`、`session.ping`、`audio.frame`、`session.stop`
- WebSocket 下行：`session.error`、`subtitle.partial`、`subtitle.final`、`session.closed`
- 低频控制 API：会话 start/stop、租户策略 get/put
- 事件 Topic：`audio.ingress.raw`、`session.control`、`asr.partial`、`asr.final`、`translation.result`、`tts.request`
- 网关 `audio.frame` 会话级限流与背压保护（错误码：`RATE_LIMITED`、`BACKPRESSURE_DROP`）
- 核心 Kafka consumer 固定重试与按源 Topic 的 `.dlq` 死信回退
- 核心 Kafka consumer 已落地 `idempotencyKey` 判重（TTL 窗口）与重复消息丢弃
- 核心 Kafka consumer 重复失败达到阈值后会发送 `ops.compensation` 信号到 `platform.compensation`
- `control-plane` 租户策略已包含灰度/回退字段：`grayEnabled`、`grayTrafficPercent`、`controlPlaneFallbackFailOpen`、`controlPlaneFallbackCacheTtlMs`
- `session-orchestrator` 查询租户策略时已落地第一版熔断与缓存回退（支持 fail-open/fail-closed）

仍在 v1 契约中保留但尚未打通：

- `tts.chunk`
- `tts.ready`

## 2. 主数据路径（冻结）

统一规定如下：

1. 高频音频数据只走 `client -> speech-gateway -> Kafka`。
2. `session-orchestrator` 不直连承接高频音频帧，只消费并编排事件。
3. `speech-gateway <-> session-orchestrator` 只用于低频控制交互（会话初始化、策略查询、状态回传）。

该约束用于消除 “`gateway -> orchestrator -> kafka` vs `gateway -> kafka`” 的实现歧义。

## 3. 统一事件 Envelope

所有异步事件必须包含以下头字段：

```json
{
  "eventId": "01HXYZ...",
  "eventType": "audio.ingress.raw",
  "eventVersion": "v1",
  "traceId": "trc_123",
  "sessionId": "sess_456",
  "tenantId": "tenant_a",
  "roomId": "room_789",
  "producer": "speech-gateway",
  "seq": 1024,
  "ts": 1710000000000,
  "idempotencyKey": "sess_456:audio.ingress.raw:1024",
  "payload": {}
}
```

字段约束：

- `eventType` 采用点分命名法，如 `asr.final`。
- `eventVersion` 当前冻结为 `v1`。
- `sessionId + seq` 是会话内顺序与幂等核心键。
- `idempotencyKey` 必须可被下游持久化判重。

## 4. 第一批冻结事件

| 事件类型 | 说明 | Topic | Key |
| --- | --- | --- | --- |
| `audio.ingress.raw` | 网关接收原始音频分片 | `audio.ingress.raw` | `sessionId` |
| `session.control` | 会话生命周期控制事件（start/stop 等） | `session.control` | `sessionId` |
| `asr.partial` | ASR 中间识别结果 | `asr.partial` | `sessionId` |
| `asr.final` | ASR 最终识别结果 | `asr.final` | `sessionId` |
| `translation.result` | 翻译结果 | `translation.result` | `sessionId` |
| `tts.request` | TTS 合成请求 | `tts.request` | `sessionId` |

完整 JSON Schema 与 Protobuf 见第 8 节。

## 5. WebSocket 协议（v1）

### 5.1 客户端上行消息

| `type` | 说明 | 关键字段 |
| --- | --- | --- |
| `session.start` | 开始会话 | `sessionId` `tenantId` `sourceLang` `targetLang` |
| `audio.frame` | 音频分片 | `sessionId` `seq` `audioBase64` `codec` `sampleRate` |
| `session.ping` | 心跳 | `sessionId` `ts` |
| `session.stop` | 主动结束 | `sessionId` |

当前实现说明：

- `speech-gateway` 当前已接受 `session.start`、`session.ping`、`audio.frame`、`session.stop`

### 5.2 服务端下行消息

| `type` | 说明 | 关键字段 |
| --- | --- | --- |
| `subtitle.partial` | 中间字幕 | `sessionId` `seq` `text` |
| `subtitle.final` | 最终字幕 | `sessionId` `seq` `text` |
| `session.error` | 错误信息 | `sessionId` `code` `message` |
| `session.closed` | 会话关闭 | `sessionId` `reason` |

当前实现说明：

- `session.error` 已由 `speech-gateway` 落地，用于协议校验和控制面错误
- `subtitle.partial`、`subtitle.final`、`session.closed` 已通过 Kafka 下游事件回推到 WebSocket 客户端

## 6. 错误码（v1）

| 错误码 | 含义 |
| --- | --- |
| `AUTH_INVALID_TOKEN` | 鉴权失败或令牌过期 |
| `INVALID_MESSAGE` | 消息格式不合法、类型不支持或字段校验失败 |
| `SESSION_NOT_FOUND` | 会话不存在或已关闭 |
| `SESSION_SEQ_INVALID` | 序号乱序或重复超限 |
| `RATE_LIMITED` | 触发限流 |
| `BACKPRESSURE_DROP` | 背压触发丢弃 |
| `ASR_TIMEOUT` | ASR 处理超时 |
| `TRANSLATION_TIMEOUT` | 翻译处理超时 |
| `INTERNAL_ERROR` | 服务内部异常 |

## 7. 版本演进规则

1. `v1` 阶段只允许向后兼容变更（新增可选字段）。
2. 删除字段、修改字段语义、修改字段类型都视为破坏性变更，必须升级到 `v2`。
3. 破坏性变更必须经过双写或双消费过渡窗口后再下线旧版本。
4. Consumer 对未知字段必须忽略，对未知版本执行保护性失败并写审计日志。

## 8. 契约文件位置

- Protobuf
  - `api/protobuf/realtime_speech.proto`
- JSON Schema
  - `api/json-schema/audio.ingress.raw.v1.json`
  - `api/json-schema/session.control.v1.json`
  - `api/json-schema/asr.partial.v1.json`
  - `api/json-schema/asr.final.v1.json`
  - `api/json-schema/translation.result.v1.json`
  - `api/json-schema/tts.request.v1.json`
