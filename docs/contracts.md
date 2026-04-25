# Contracts

## 1. 目标

本文件用于冻结 `Phase 0` 必须统一的接口与事件契约，避免各服务各自定义格式导致后期频繁返工。

冻结范围：

- WebSocket 上下行协议（实时链路）
- 统一事件 Envelope（跨服务异步链路）
- 第一批核心事件类型与字段
- 错误码与版本演进规则

## 1.1 当前实现注记（2026-04-25）

本文件仍然是外部行为的权威定义，但当前仓库只实现了其中一部分。

当前已经落地：

- WebSocket 上行：`session.start`、`session.ping`、`audio.frame`、`session.stop`、`command.confirm`、`playback.metric`
- WebSocket 下行：`session.error`、`subtitle.partial`、`subtitle.final`、`tts.chunk`、`tts.ready`、`command.result`、`session.closed`
- 低频控制 API：会话 start/stop、租户策略 get/put/rollback
- 事件 Topic：`audio.ingress.raw`、`session.control`、`asr.partial`、`asr.final`、`translation.request`、`translation.result`、`tts.request`、`tts.chunk`、`tts.ready`、`tenant.policy.changed`、`command.confirm.request`、`command.result`、`platform.audit`、`platform.dlq`
- 网关 `audio.frame` 会话级限流与背压保护（错误码：`RATE_LIMITED`、`BACKPRESSURE_DROP`）
- 核心 Kafka consumer 已落地重试与按源 Topic 的 `.dlq` 死信回退；`asr-worker`、`translation-worker`、`tts-orchestrator` 已支持按租户策略驱动重试参数与 DLQ 后缀
- 核心 Kafka consumer 在 `.dlq` 死信回退时会额外发布统一治理事件到 `platform.dlq`（兼容保留按源 Topic 的 `.dlq`）
- 核心 Kafka consumer 已落地 `idempotencyKey` 判重（TTL 窗口）与重复消息丢弃
- 核心 Kafka consumer 重复失败达到阈值后会发送 `ops.compensation` 信号到 `platform.compensation`
- 核心补偿信号同时发布 `platform.audit` 审计事件（兼容保留 `platform.compensation`）
- `asr-worker` FunASR 适配已补齐可用性探测、并发保护与错误语义映射（用于重试/DLQ 分类）
- `translation-worker` OpenAI 适配已补齐可用性探测、并发保护与错误语义映射（用于重试/DLQ 分类）
- `tts-orchestrator` HTTP synthesis 适配已补齐可用性探测、并发保护与错误语义映射（用于重试/DLQ 分类）
- `control-plane` 租户策略已包含灰度/回退与可靠性字段：`grayEnabled`、`grayTrafficPercent`、`controlPlaneFallbackFailOpen`、`controlPlaneFallbackCacheTtlMs`、`retryMaxAttempts`、`retryBackoffMs`、`dlqTopicSuffix`
- `control-plane` 租户策略模型已扩展 `sessionMode`（`TRANSLATION`/`SMART_HOME`，默认 `TRANSLATION`）
- `session-orchestrator` 查询租户策略时已落地第一版熔断与缓存回退（支持 fail-open/fail-closed）
- 下行 `asr.partial -> subtitle.partial`、`translation.result -> subtitle.final`、`tts.chunk -> tts.chunk`、`tts.ready -> tts.ready`、`session.control(CLOSED) -> session.closed` 已有仓库内 E2E 稳定性回归测试
- `session.closed` 触发后下行通道会终止并丢弃晚到消息
- `asr-worker` 已支持基于静音帧阈值的 VAD 切段终态发布（`asr.final`）
- `speech-gateway` 已支持可配置 WS token 鉴权（`Authorization: Bearer` 或 query `access_token`），失败返回 `AUTH_INVALID_TOKEN`
- `control-plane` 已支持可配置 Bearer Token 鉴权与授权（`/api/v1/tenants/**`，支持读/写权限与租户范围约束）

新增说明：

- `tts-orchestrator` 已实现 `translation.result` 同步产出 `tts.request`、`tts.chunk`、`tts.ready` 三类事件
- `translation-worker` 已实现两段式翻译治理链路：`asr.final -> translation.request -> translation.result`
- `tts.ready.payload.playbackUrl` 已支持按租户映射的区域 CDN 路由，并可在区域路由缺失时回退到 origin URL
- `control-plane` 已实现 `tenant.policy.changed` 事件发布（upsert/rollback），`session-orchestrator` / `asr-worker` / `translation-worker` / `tts-orchestrator` 已消费该事件用于策略缓存刷新
- `control-plane` 已实现并冻结回滚编排契约：`POST /api/v1/tenants/{tenantId}/policy:rollback` 支持可选请求体 `targetVersion`、`distributionRegions`；请求体缺失时语义保持为“回滚上一版本”
- `tenant.policy.changed` 已实现并冻结编排元数据扩展：`sourcePolicyVersion`、`targetPolicyVersion`、`distributionRegions`（均为可选字段，向后兼容）

已冻结并分阶段落地：

- `speech-gateway` 已实现：
  - WebSocket 上行 `command.confirm` -> Kafka `command.confirm.request`
  - Kafka `command.result` -> WebSocket 下行 `command.result`
- `command-worker` 已实现：
  - 消费 `asr.final`（仅 `sessionMode=SMART_HOME`）
  - 消费 `command.confirm.request`
  - 调用 smartHomeNlu `/api/v1/command`、`/api/v1/confirm`
  - 发布 `command.result`（含租户策略驱动重试/DLQ、幂等与补偿信号）
- `tts-orchestrator` 已实现：
  - 消费 `command.result`（仅 `sessionMode=SMART_HOME`）
  - 消费 `translation.result`（仅 `sessionMode=TRANSLATION`）
  - 双路径统一发布 `tts.request` / `tts.chunk` / `tts.ready`
- 事件 Envelope 扩展字段 `userId` 已在契约与当前网关实现中支持（随会话上下文透传）

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
  "userId": "user_001",
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
| `translation.request` | 待翻译文本请求 | `translation.request` | `sessionId` |
| `translation.result` | 翻译结果 | `translation.result` | `sessionId` |
| `tts.request` | TTS 合成请求 | `tts.request` | `sessionId` |
| `tts.chunk` | TTS 流式音频分片 | `tts.chunk` | `sessionId` |
| `tts.ready` | TTS 回放就绪事件 | `tts.ready` | `sessionId` |
| `tenant.policy.changed` | 租户策略变更通知（`control-plane` 发布，运行时服务消费刷新） | `tenant.policy.changed` | `tenantId` |
| `command.confirm.request` | 客户端确认请求（`confirm_token + accept`） | `command.confirm.request` | `sessionId` |
| `command.result` | 智能家居命令执行结果/确认要求回执 | `command.result` | `sessionId` |
| `platform.audit` | 治理审计事件（配置变更、补偿、关键策略动作） | `platform.audit` | `tenantId` |
| `platform.dlq` | 统一死信治理事件（跨服务排障与重放） | `platform.dlq` | `tenantId` |

当前实现语义（`asr-worker`）：

- 非稳定识别结果发布为 `asr.partial`
- 稳定结果、`endOfStream=true`，或命中 VAD 静音切段阈值的结果发布为 `asr.final`
- 治理事件 `tenant.policy.changed` 没有真实会话上下文时，`sessionId` 使用合成值（建议 `tenant-policy::<tenantId>`）
- 治理事件（`tenant.policy.changed` / `platform.audit` / `platform.dlq`）没有真实会话上下文时，`sessionId` 使用合成值（建议 `governance::<tenantId>`）
- 治理事件 `tenant.policy.changed.payload.operation` 当前取值：`CREATED`、`UPDATED`、`ROLLED_BACK`、`ROLLED_BACK_TO_VERSION`
- 当 `operation=ROLLED_BACK_TO_VERSION` 时，`sourcePolicyVersion` 与 `targetPolicyVersion` 必填

完整 JSON Schema 与 Protobuf 见第 8 节。

## 5. WebSocket 协议（v1）

### 5.1 客户端上行消息

| `type` | 说明 | 关键字段 |
| --- | --- | --- |
| `session.start` | 开始会话 | `sessionId` `tenantId` `userId` `sourceLang` `targetLang` |
| `audio.frame` | 音频分片 | `sessionId` `seq` `audioBase64` `codec` `sampleRate` |
| `session.ping` | 心跳 | `sessionId` `ts` |
| `session.stop` | 主动结束 | `sessionId` |
| `command.confirm` | 命令确认提交 | `sessionId` `seq` `confirmToken` `accept` |
| `playback.metric` | 客户端播放阶段指标上报 | `sessionId` `seq` `stage(start/stall/complete/fallback)` `source(remote/local)` `durationMs?` `stallCount?` `reason?` |

当前实现说明：

- `speech-gateway` 已接受 `session.start`、`session.ping`、`audio.frame`、`session.stop`、`command.confirm`、`playback.metric`
- `command.confirm` 已由 `speech-gateway` 转发至 Kafka Topic `command.confirm.request`（需先完成 `session.start` 建立 `tenantId/userId` 会话上下文）
- `playback.metric` 由 `speech-gateway` 直接转化为观测指标（`gateway.client.playback.*`），不进入 Kafka 高频链路
- 当 `gateway.auth.enabled=true` 时，`/ws/audio` 需要携带合法 token（`Authorization: Bearer <token>` 或 query 参数 `access_token`）
- token 缺失/非法时，网关会下发 `session.error(code=AUTH_INVALID_TOKEN)` 并关闭连接

### 5.2 服务端下行消息

| `type` | 说明 | 关键字段 |
| --- | --- | --- |
| `subtitle.partial` | 中间字幕 | `sessionId` `seq` `text` |
| `subtitle.final` | 最终字幕 | `sessionId` `seq` `text` |
| `tts.chunk` | TTS 音频分片下行 | `sessionId` `seq` `audioBase64` `codec` `sampleRate` `chunkSeq` `lastChunk` |
| `tts.ready` | TTS 回放就绪下行 | `sessionId` `seq` `playbackUrl` `codec` `sampleRate` `durationMs` `cacheKey` |
| `command.result` | 智能家居命令执行回执下行 | `sessionId` `seq` `status` `code` `replyText` `retryable` `confirmToken?` `expiresInSec?` |
| `session.error` | 错误信息 | `sessionId` `code` `message` |
| `session.closed` | 会话关闭 | `sessionId` `reason` |

当前实现说明：

- `session.error` 已由 `speech-gateway` 落地，用于协议校验和控制面错误
- `subtitle.partial`、`subtitle.final`、`tts.chunk`、`tts.ready`、`session.closed` 已通过 Kafka 下游事件回推到 WebSocket 客户端
- `command.result` 已由 `speech-gateway` 消费 Kafka Topic `command.result` 后下发到 WebSocket 客户端

### 5.3 Control-Plane 回滚编排 API（v1）

路径：

- `POST /api/v1/tenants/{tenantId}/policy:rollback`

请求体（可选）：

```json
{
  "targetVersion": 3,
  "distributionRegions": ["cn-east-1", "ap-southeast-1"]
}
```

字段约束：

- `targetVersion`：可选，`>=1`；缺失时语义为“回滚上一版本”
- `distributionRegions`：可选，非空字符串数组，去重后用于表达跨区域分发意图

响应语义：

- 成功时返回 `TenantPolicyResponse`，`version` 为新生效版本（递增）
- 若指定 `targetVersion` 不存在，返回 `TENANT_POLICY_VERSION_NOT_FOUND`
- 若指定 `targetVersion` 非法（如 `>=` 当前版本），返回 `TENANT_POLICY_ROLLBACK_VERSION_INVALID`

## 6. 错误码（v1）

| 错误码 | 含义 |
| --- | --- |
| `AUTH_INVALID_TOKEN` | 鉴权失败或令牌过期 |
| `AUTH_FORBIDDEN` | 已认证但无权限访问目标租户或操作 |
| `INVALID_MESSAGE` | 消息格式不合法、类型不支持或字段校验失败 |
| `SESSION_NOT_FOUND` | 会话不存在或已关闭 |
| `SESSION_SEQ_INVALID` | 序号乱序或重复超限 |
| `RATE_LIMITED` | 触发限流 |
| `BACKPRESSURE_DROP` | 背压触发丢弃 |
| `ASR_TIMEOUT` | ASR 处理超时 |
| `TRANSLATION_TIMEOUT` | 翻译处理超时 |
| `TENANT_POLICY_VERSION_NOT_FOUND` | 控制面指定回滚版本不存在 |
| `TENANT_POLICY_ROLLBACK_VERSION_INVALID` | 控制面指定回滚版本非法（超前、等于当前或不允许） |
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
  - `api/json-schema/translation.request.v1.json`
  - `api/json-schema/translation.result.v1.json`
  - `api/json-schema/tts.request.v1.json`
  - `api/json-schema/tts.chunk.v1.json`
  - `api/json-schema/tts.ready.v1.json`
  - `api/json-schema/tenant.policy.changed.v1.json`
  - `api/json-schema/command.confirm.request.v1.json`
  - `api/json-schema/command.result.v1.json`
  - `api/json-schema/platform.audit.v1.json`
  - `api/json-schema/platform.dlq.v1.json`
