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
  "userId": "user_001",
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

截至 `2026-04-26`，仓库里已经落地并有代码/测试支撑的 Topic 如下：

| Topic | Producer | Consumer | Key | 说明 |
| --- | --- | --- | --- | --- |
| `audio.ingress.raw` | `speech-gateway` | `asr-worker` | `sessionId` | 高频音频主链路 |
| `session.control` | `session-orchestrator` | `speech-gateway` | `sessionId` | 生命周期控制事件，用于 `session.closed` 下行 |
| `asr.partial` | `asr-worker` | `speech-gateway` | `sessionId` | 中间识别结果，用于 `subtitle.partial` |
| `asr.final` | `asr-worker` | `translation-worker`、`command-worker` | `sessionId` | 翻译请求入口与 SMART_HOME 命令入口 |
| `translation.request` | `translation-worker` | `translation-worker` | `sessionId` | 翻译入队事件（将 ASR 终态与翻译执行解耦） |
| `translation.result` | `translation-worker` | `tts-orchestrator`、`speech-gateway` | `sessionId` | 当前 TTS 入口，同时用于 `subtitle.final` 下行 |
| `tts.request` | `tts-orchestrator` | 暂无仓库内下游 | `sessionId` | TTS 编排输出 |
| `tts.chunk` | `tts-orchestrator` | `speech-gateway` | `sessionId` | TTS 流式音频分片输出，用于 `tts.chunk` 下行 |
| `tts.ready` | `tts-orchestrator` | `speech-gateway` | `sessionId` | TTS 回放就绪输出，用于 `tts.ready` 下行 |
| `tenant.policy.changed` | `control-plane` | `session-orchestrator`、`asr-worker`、`translation-worker`、`tts-orchestrator` | `tenantId` | 租户策略变更通知，用于动态策略分发 |
| `platform.audit` | `asr-worker`、`translation-worker`、`tts-orchestrator`、`command-worker`、`speech-gateway`、`session-orchestrator` | 暂无仓库内下游 | `tenantId` | 治理审计事件（补偿/超时等） |
| `platform.dlq` | `asr-worker`、`translation-worker`、`tts-orchestrator`、`command-worker`、`speech-gateway` | 暂无仓库内下游 | `tenantId` | 统一死信治理事件（保留按源 `.dlq`） |

说明：

- 主链路 Topic 继续按 `sessionId` 发送 Kafka Key，治理事件 `tenant.policy.changed` 使用 `tenantId` 作为 Key
- `translation-worker` 当前采用两段式治理链路：`asr.final -> translation.request -> translation.result`
- `tts-orchestrator` 当前会从同一输入事件同步产出 `tts.request`、`tts.chunk`、`tts.ready`
- `asr-worker`、`translation-worker`、`tts-orchestrator`、`speech-gateway` 下行消费者已接入固定重试 + `<source-topic>.dlq` 死信回退
- 上述核心消费者均已接入基于 `idempotencyKey` 的 TTL 判重，重复消息按成功路径 no-op
- 上述核心消费者在重复失败达到阈值后会发出 `ops.compensation` 信号到 `platform.compensation`
- 上述核心消费者补偿路径已并行发布 `platform.audit`，并在死信恢复时追加 `platform.dlq`（兼容保留旧治理主题）

## 4. 已冻结并分阶段落地 Topic

以下 Topic 契约已在 `api/json-schema` 与 `api/protobuf` 冻结，当前为分阶段落地状态：

| Topic | Producer | Consumer | Key | 说明 |
| --- | --- | --- | --- | --- |
| `command.confirm.request` | `speech-gateway` | `command-worker` | `sessionId` | 客户端二次确认请求（`confirm_token + accept`） |
| `command.result` | `command-worker` | `speech-gateway`、`tts-orchestrator` | `sessionId` | 智能家居命令执行回执（含 `confirm_required`） |

当前状态：

- `speech-gateway` 已完成 `command.confirm.request` 发布与 `command.result` 消费下发
- `command-worker` 已完成 `asr.final` / `command.confirm.request` 消费、smartHomeNlu 调用与 `command.result` 发布（含重试/DLQ/幂等/补偿）
- `tts-orchestrator` 已完成 `command.result` 消费并按 `sessionMode=SMART_HOME` 产出 `tts.request` / `tts.chunk` / `tts.ready`
- `tts-orchestrator` 对 `translation.result` 已按租户 `sessionMode` 分流：`TRANSLATION` 处理，`SMART_HOME` 忽略

## 5. 扩展 Topic（契约冻结与实现状态）

以下 Topic 属于目标架构扩展能力，当前分为“契约已冻结待接入”和“规划中”两类：

| Topic | 说明 | 计划用途 | 当前状态 |
| --- | --- | --- | --- |
| `audio.vad.segmented` | VAD 切分后的语音段 | 支撑更细粒度 ASR 管线 | 规划中 |
| `tenant.policy.distribution.result` | 策略分发执行回执 | 运行时服务执行结果回传与 control-plane 聚合 | 已实现（运行时发布 + control-plane 聚合查询） |
| `platform.audit` | 审计事件 | 配置与治理追踪 | 已实现（补偿路径审计双写） |
| `platform.dlq` | 统一死信治理事件 | 补偿与排障 | 已实现（消费失败统一上报） |

说明：

- `platform.audit` / `platform.dlq` 已新增 JSON Schema 与 Protobuf 契约并接入核心运行时路径。
- `tenant.policy.distribution.result` 已接入运行时发布与 control-plane 聚合消费，支持查询 API `GET /api/v1/tenants/{tenantId}/policy:distribution-status?policyVersion=<n>`。
- 为兼容旧链路，现有 `<source-topic>.dlq` 与 `platform.compensation` 行为保持不变（并行双写）。

补充说明：

- `tenant.policy.changed` 的 JSON Schema / Protobuf 契约、`control-plane` 发布以及运行时服务消费刷新均已落地。
- `command.confirm.request` 与 `command.result` 的 JSON Schema / Protobuf 契约已冻结。

## 6. 分区与顺序策略

### 6.1 分区键

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

### 6.2 顺序边界

系统只保证：

- 同一个 `sessionId` 在同一 Topic 内相对有序

系统不保证：

- 不同 Topic 之间的绝对时间顺序
- 跨会话全局顺序
- 多下游分支消费结果的完全同步到达

因此编排层必须按业务语义而不是按消息到达时间推进状态。

## 7. 事件消费语义

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

当前实现（`2026-04-25`）：

- 核心 consumer 失败后会写入 `<source-topic>.dlq`
- 核心 consumer 在死信恢复时会额外发布 `platform.dlq` 统一治理事件
- `IllegalArgumentException`（如 payload 非法）按不可重试处理，直接进入对应 DLQ
- 核心 consumer 失败达到重试阈值后会额外发送 `ops.compensation`，并双写 `platform.audit` 审计事件

运营化现状（`2026-04-25`）：

- 已提供 `tools/platform-dlq-replay.sh` 作为标准化重放入口（默认 dry-run，支持筛选与报告产物）
- 重放 runbook 已落地：`docs/runbooks/platform-dlq-replay.md`
- 已提供 `tools/platform-dlq-auto-recovery.sh` 作为跨服务自动恢复执行入口（恢复账本去重 + replay 编排 + 报告产物）
- 自动恢复 runbook 已落地：`docs/runbooks/platform-dlq-auto-recovery.md`

以下情况最终都应进入统一死信治理：

- 超出最大重试次数
- 反序列化失败
- 事件版本不兼容
- 下游依赖持续不可用

## 8. 状态机建议

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

## 9. 版本演进策略

- 事件体必须带 `eventVersion`
- Producer 只能做向后兼容演进
- Consumer 对未知字段保持忽略，对未知版本执行保护性失败
- 跨服务大改优先使用双写或多版本并行过渡

## 10. 当前最小事件闭环

当前仓库已具备的事件闭环如下：

1. `speech-gateway` 发布 `audio.ingress.raw`
2. `session-orchestrator` 发布 `session.control`
3. `asr-worker` 消费 `audio.ingress.raw` 并产出 `asr.partial` 与 `asr.final`
4. `translation-worker` 消费 `asr.final` 并发布 `translation.request`
5. `translation-worker` 消费 `translation.request` 并发布 `translation.result`
6. `tts-orchestrator` 消费 `translation.result` 并发布 `tts.request`、`tts.chunk`、`tts.ready`
6. `control-plane` 在策略 upsert 后发布 `tenant.policy.changed`，运行时服务消费后立即失效本地策略缓存
7. 运行时服务发布 `tenant.policy.distribution.result`，`control-plane` 聚合并支持按 `tenantId + policyVersion` 查询

仍待深化的部分：

- `platform.dlq` 自动恢复后的更细粒度会话级补偿编排策略
