# Event Model

## 1. 事件模型原则

- 所有跨服务异步通信都必须显式建模为事件。
- 只追求会话内顺序，不追求全局顺序。
- 默认采用至少一次投递，靠下游幂等消化重复。
- 所有重要事件都要可追踪、可重放、可审计。

## 2. 统一事件头

建议所有事件统一使用如下 Envelope：

```json
{
  "eventId": "01HXYZ...",
  "eventType": "asr.partial",
  "eventVersion": "v1",
  "traceId": "trc_123",
  "sessionId": "sess_456",
  "tenantId": "tenant_a",
  "roomId": "room_789",
  "producer": "speech-gateway",
  "seq": 1024,
  "ts": 1710000000000,
  "idempotencyKey": "sess_456:asr.partial:1024",
  "payload": {}
}
```

字段建议：

- `eventId`
  全局唯一事件 ID，用于追踪与审计。
- `eventType`
  明确区分事件语义，而不是复用一个通用消息体。
- `eventVersion`
  支持未来演进与兼容处理。
- `traceId`
  串联网关、编排、推理和分发的全链路观测。
- `sessionId`
  顺序、路由、状态机、缓存和幂等的核心键。
- `seq`
  会话内严格递增序号，用于去重、乱序检测和补偿。
- `idempotencyKey`
  下游消费幂等落地键。

## 3. 推荐 Topic 规划

| Topic | 说明 | 建议 Key | 备注 |
| --- | --- | --- | --- |
| `audio.ingress.raw` | 网关接收的原始音频帧 | `sessionId` | 高频数据流 |
| `audio.vad.segmented` | VAD 切分后的语音段 | `sessionId` | 可选 |
| `session.control` | 会话开始、暂停、结束、重连 | `sessionId` | 状态切换关键主题 |
| `asr.partial` | 流式中间识别结果 | `sessionId` | 不要求全部持久保存 |
| `asr.final` | 最终识别结果 | `sessionId` | 下游翻译主入口 |
| `translation.request` | 待翻译文本 | `sessionId` | 支持多语种扩展 |
| `translation.result` | 翻译结果 | `sessionId` | 下游字幕/TTS 使用 |
| `tts.request` | TTS 合成请求 | `sessionId` 或 `cacheKey` | 依场景取舍 |
| `tts.chunk` | 流式音频分片 | `sessionId` | 实时回放使用 |
| `tts.ready` | 音频文件已可回放 | `sessionId` | 分发或回放地址 |
| `platform.audit` | 运营审计、配置变更 | `tenantId` | 低频 |
| `platform.dlq` | 死信队列 | 原始 Key | 补偿和排障 |

## 4. 分区与顺序策略

### 4.1 分区键

默认优先使用：

- `sessionId`

原因：

- 能天然保证单会话顺序
- 能稳定支撑会话亲和消费
- 便于状态机和幂等落地

避免直接使用：

- `tenantId`
- `languagePair`
- `roomId`

除非业务明确接受更粗粒度的顺序控制，否则容易形成热点。

### 4.2 顺序边界

系统只保证：

- 同一个 `sessionId` 在同一 Topic 内相对有序

系统不保证：

- 不同 Topic 之间的绝对时间顺序
- 跨会话全局顺序
- 多下游分支消费结果的完全同步到达

因此编排层必须按业务语义而不是按“消息到达时间”来推进状态。

## 5. 事件消费语义

### 幂等

所有消费者都应在持久层或缓存层记录：

- 最近处理的 `seq`
- `idempotencyKey`
- 消费结果摘要

建议规则：

- `seq` 小于已确认序号时直接丢弃
- `seq` 等于已确认序号时按重复消息处理
- `seq` 大于期望序号过多时触发乱序观察或补偿逻辑

### 重试

区分两类错误：

- 短暂错误
  比如网络抖动、瞬时超时、资源繁忙，可有限重试。
- 业务错误
  比如参数非法、会话已关闭、模型不支持，应尽快失败并记录审计。

### 死信

以下情况应进入 `platform.dlq`：

- 超出最大重试次数
- 反序列化失败
- 事件版本不兼容
- 下游依赖持续不可用

## 6. 状态机建议

建议会话状态最少包含：

```text
INIT
CONNECTING
STREAMING
ASR_ACTIVE
TRANSLATING
TTS_ACTIVE
DRAINING
CLOSED
FAILED
```

状态变更原则：

- 状态切换由编排层统一推进
- 网关和 Worker 不直接跨层修改全局会话状态
- 每次切换都应记录事件、时间戳和责任服务

## 7. 版本演进策略

- 事件体必须带 `eventVersion`
- Producer 只能做向后兼容演进
- Consumer 对未知字段保持忽略，对未知版本执行保护性失败
- 跨服务大改优先使用双写或多版本并行过渡

## 8. 一个最小闭环

最小实时字幕链路建议如下：

1. `speech-gateway` 发布 `audio.ingress.raw`
2. `asr-worker` 消费并产出 `asr.partial`
3. `asr-worker` 产出 `asr.final`
4. `translation-worker` 消费 `asr.final` 并发布 `translation.result`
5. `session-orchestrator` 汇聚结果并推送客户端

这条链路稳定后，再补 `tts.request -> tts.chunk -> tts.ready`。

