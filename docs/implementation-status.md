# Implementation Status

## 1. 当前基线

截至 `2026-04-22`，仓库已经从“纯资料收敛”演进为“文档 + 契约 + 服务骨架并存”的工程仓库。

当前已实现的主链路是：

`client -> speech-gateway -> Kafka -> asr-worker -> translation-worker -> tts-orchestrator`

配套的低频控制链路是：

`speech-gateway -> session-orchestrator -> control-plane / Redis`

这条链路已经具备：

- 统一 v1 事件 Envelope
- 6 个已落地 Topic：`audio.ingress.raw`、`session.control`、`asr.partial`、`asr.final`、`translation.result`、`tts.request`
- 低频控制 API：会话 start/stop、租户策略 get/put
- 网关 `audio.frame` 会话级限流/背压保护（`RATE_LIMITED` / `BACKPRESSURE_DROP`）
- 核心 Kafka 消费链路固定重试与按源 Topic 的 `.dlq` 死信回退
- 核心 Kafka 消费链路 `idempotencyKey` 判重与重复消息 no-op
- 核心 Kafka 消费链路重复失败阈值补偿信号（`ops.compensation -> platform.compensation`）
- `session-orchestrator` 查询 `control-plane` 已落地第一版熔断 + 缓存回退（fail-open/fail-closed）
- `speech-gateway` 下行链路已补充仓库内 E2E 稳定性验证（顺序、终态、重复/异常计数）
- `deploy/monitoring` 已补齐 Prometheus/Grafana 资产（含 Kafka lag、延迟、错误率看板与告警规则）
- 全仓测试与 `tools/verify.sh` 校验基线

## 2. 服务模块现状

| 服务 | 端口 | 当前已实现 | 当前未实现 |
| --- | --- | --- | --- |
| `speech-gateway` | `8080` | WebFlux 启动、`/ws/audio`、`session.start` / `session.ping` / `audio.frame` / `session.stop` 路由、`audio.ingress.raw` Kafka 发布、会话级限流/背压控制、错误下行 `session.error`、Kafka 驱动的 `subtitle.partial` / `subtitle.final` / `session.closed` 下行、下行 E2E 稳定性测试基线 | 鉴权、更完整的下行聚合策略 |
| `session-orchestrator` | `8081` | `POST /api/v1/sessions:start`、`POST /api/v1/sessions/{sessionId}:stop`、控制面策略校验、控制面熔断与缓存回退、Redis 会话状态、`session.control` Kafka 发布 | 超时编排、结果聚合、补偿工作流 |
| `asr-worker` | `8082` | 消费 `audio.ingress.raw`、默认 placeholder 推理 + 可切换 HTTP/FunASR ASR 适配、发布 `asr.partial` / `asr.final` | FunASR 生产联调、VAD 分段 |
| `translation-worker` | `8083` | 消费 `asr.final`、默认 placeholder 翻译 + 可切换 HTTP 翻译适配、发布 `translation.result` | 真实 LLM/MT、术语治理、上下文增强 |
| `tts-orchestrator` | `8084` | 消费 `translation.result`、规则 voice 选择 + 可切换 HTTP voice-policy 适配、生成 cacheKey、发布 `tts.request` | 真实 TTS 引擎、`tts.chunk` / `tts.ready`、对象存储、CDN |
| `control-plane` | `8085` | `PUT/GET /api/v1/tenants/{tenantId}/policy`、Redis 策略存储、版本化 upsert、灰度与回退策略字段 | 认证鉴权、持久化数据库、动态策略下发 |

## 3. 当前协议与接口面

### 3.1 WebSocket

入口：

- `speech-gateway`
- `GET /ws/audio`

当前已接收：

- `session.start`
- `session.ping`
- `audio.frame`
- `session.stop`

当前已下发：

- `session.error`
- `subtitle.partial`
- `subtitle.final`
- `session.closed`

### 3.2 HTTP API

| 服务 | 接口 | 说明 |
| --- | --- | --- |
| `session-orchestrator` | `POST /api/v1/sessions:start` | 创建或幂等返回会话 |
| `session-orchestrator` | `POST /api/v1/sessions/{sessionId}:stop` | 关闭或幂等返回会话 |
| `control-plane` | `PUT /api/v1/tenants/{tenantId}/policy` | 创建/更新租户策略 |
| `control-plane` | `GET /api/v1/tenants/{tenantId}/policy` | 查询租户策略 |

## 4. 当前 Kafka 事件路径

所有已落地 Topic 当前都以 `sessionId` 作为消息 Key。

| Topic | Producer | Consumer | 说明 |
| --- | --- | --- | --- |
| `audio.ingress.raw` | `speech-gateway` | `asr-worker` | 高频音频主链路 |
| `session.control` | `session-orchestrator` | 暂无仓库内下游 | 生命周期审计与编排事件 |
| `asr.partial` | `asr-worker` | `speech-gateway` | 中间识别结果，当前用于 `subtitle.partial` |
| `asr.final` | `asr-worker` | `translation-worker` | 当前翻译入口 |
| `translation.result` | `translation-worker` | `tts-orchestrator` | 当前 TTS 入口 |
| `tts.request` | `tts-orchestrator` | 暂无仓库内下游 | TTS 编排输出，等待真实引擎接入 |

同时，`speech-gateway` 当前也消费以下下行 Topic 并回推 WebSocket：

- `asr.partial` -> `subtitle.partial`
- `translation.result` -> `subtitle.final`
- `session.control(status=CLOSED)` -> `session.closed`

## 5. 当前缺口

- `translation.request`、`tts.chunk`、`tts.ready` 仍是计划扩展 Topic
- ASR / Translation / TTS 尚未完成生产级真实引擎闭环，当前是“placeholder/规则默认 + provider 适配入口（ASR 含 FunASR mode）”阶段
- 尚未接入对象存储、CDN、完整补偿编排、自适应熔断/灰度治理和压测体系

## 6. 文档使用建议

- 看当前行为：优先读本文件
- 看外部契约：读 `contracts.md` 与 `api/`
- 看目标架构：读 `architecture.md`
- 看历史背景：读 `html/README.md` 与 `docs/html/*.html`
