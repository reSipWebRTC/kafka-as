# Event Model

## 1. 事件模型原则

- 所有跨服务异步通信都必须显式建模为事件
- 只追求会话内顺序，不追求全局顺序
- 默认采用至少一次投递，靠下游幂等消化重复
- 所有重要事件都要可追踪、可重放、可审计

## 2. 统一事件头

当前 v1 事件统一使用如下 Envelope：

```json
{
  "eventId": "evt_123",
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

字段要求：

- `eventType` 使用点分命名法
- `eventVersion` 当前冻结为 `v1`
- `sessionId + seq` 是会话内顺序与幂等核心键
- `idempotencyKey` 必须可被下游持久化判重

## 3. 当前已实现 Topic

截至 `2026-04-23`，仓库里已经落地并有代码/测试支撑的 Topic 如下：

| Topic | Producer | Consumer | Key | 说明 |
| --- | --- | --- | --- | --- |
| `audio.ingress.raw` | `speech-gateway` | `asr-worker` | `sessionId` | 高频音频主链路 |
| `session.control` | `session-orchestrator` | 暂无仓库内下游 | `sessionId` | 生命周期控制事件 |
| `asr.partial` | `asr-worker` | `speech-gateway` | `sessionId` | 中间识别结果，用于 `subtitle.partial` |
| `asr.final` | `asr-worker` | `translation-worker` | `sessionId` | 当前翻译入口 |
| `translation.result` | `translation-worker` | `tts-orchestrator` | `sessionId` | 当前 TTS 入口 |
| `tts.request` | `tts-orchestrator` | 暂无仓库内下游 | `sessionId` | TTS 编排输出 |
| `tts.chunk` | `tts-orchestrator` | 暂无仓库内下游 | `sessionId` | TTS 流式音频分片输出 |
| `tts.ready` | `tts-orchestrator` | 暂无仓库内下游 | `sessionId` | TTS 回放就绪输出 |

说明：

- 当前所有已落地 publisher 都按 `sessionId` 发送 Kafka Key
- `translation-worker` 当前直接消费 `asr.final`，尚未引入独立 `translation.request`
- `tts-orchestrator` 当前会从同一输入事件同步产出 `tts.request`、`tts.chunk`、`tts.ready`
- `asr-worker`、`translation-worker`、`tts-orchestrator`、`speech-gateway` 下行消费者已接入固定重试 + `<source-topic>.dlq` 死信回退
- 上述核心消费者均已接入基于 `idempotencyKey` 的 TTL 判重，重复消息按成功路径 no-op
- 上述核心消费者在重复失败达到阈值后会发出 `ops.compensation` 信号到 `platform.compensation`

## 4. 计划扩展 Topic

以下 Topic 仍然属于目标架构的一部分，但当前未在仓库实现中落地：

| Topic | 说明 | 计划用途 |
| --- | --- | --- |
| `audio.vad.segmented` | VAD 切分后的语音段 | 支撑更细粒度 ASR 管线 |
| `translation.request` | 待翻译文本 | 将翻译入队与 ASR 最终结果解耦 |
| `platform.audit` | 审计事件 | 配置与治理追踪 |
| `platform.dlq` | 死信队列 | 补偿与排障 |

这些 Topic 在文档中应该明确标记为“计划扩展”，不要写成当前已上线能力。

## 5. 分区与顺序策略

### 5.1 分区键

默认优先使用：

- `sessionId`

原因：

- 能天然保证单会话顺序
- 能稳定支撑会话亲和消费
- 便于状态机和幂等落地

除非业务明确接受更粗粒度顺序控制，否则避免直接使用：

- `tenantId`
- `languagePair`
- `roomId`

### 5.2 顺序边界

系统只保证：

- 同一个 `sessionId` 在同一 Topic 内相对有序

系统不保证：

- 不同 Topic 之间的绝对时间顺序
- 跨会话全局顺序
- 多下游分支消费结果的完全同步到达

因此编排层必须按业务语义而不是按消息到达时间推进状态。

## 6. 事件消费语义

### 幂等

消费者应记录：

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
  如网络抖动、瞬时超时、资源繁忙，可有限重试
- 业务错误
  如参数非法、会话已关闭、模型不支持，应尽快失败并记录审计

### 死信

当前实现（`2026-04-22`）：

- 核心 consumer 失败后会写入 `<source-topic>.dlq`
- `IllegalArgumentException`（如 payload 非法）按不可重试处理，直接进入对应 DLQ
- 核心 consumer 失败达到重试阈值后会额外发送 `ops.compensation` 信号

目标形态（规划中）：

- 统一汇聚到 `platform.dlq` 进行跨服务补偿与排障
- 建立标准化重放与审计流程

以下情况最终都应进入统一死信治理：

- 超出最大重试次数
- 反序列化失败
- 事件版本不兼容
- 下游依赖持续不可用

## 7. 状态机建议

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

## 8. 版本演进策略

- 事件体必须带 `eventVersion`
- Producer 只能做向后兼容演进
- Consumer 对未知字段保持忽略，对未知版本执行保护性失败
- 跨服务大改优先使用双写或多版本并行过渡

## 9. 当前最小事件闭环

当前仓库已具备的事件闭环如下：

1. `speech-gateway` 发布 `audio.ingress.raw`
2. `session-orchestrator` 发布 `session.control`
3. `asr-worker` 消费 `audio.ingress.raw` 并产出 `asr.partial` 与 `asr.final`
4. `translation-worker` 消费 `asr.final` 并发布 `translation.result`
5. `tts-orchestrator` 消费 `translation.result` 并发布 `tts.request`、`tts.chunk`、`tts.ready`

仍未打通的部分：

- `translation.request`
- DLQ、补偿和故障恢复链路
