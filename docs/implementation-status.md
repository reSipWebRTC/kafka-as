# Implementation Status

## 1. 当前基线

截至 `2026-04-23`，仓库已经从“纯资料收敛”演进为“文档 + 契约 + 服务骨架并存”的工程仓库。

当前已实现的主链路是：

`client -> speech-gateway -> Kafka -> asr-worker -> translation-worker -> tts-orchestrator`

配套的低频控制链路是：

`speech-gateway -> session-orchestrator -> control-plane / Redis`

这条链路已经具备：

- 统一 v1 事件 Envelope
- 8 个已落地 Topic：`audio.ingress.raw`、`session.control`、`asr.partial`、`asr.final`、`translation.result`、`tts.request`、`tts.chunk`、`tts.ready`
- 低频控制 API：会话 start/stop、租户策略 get/put
- 网关 `audio.frame` 会话级限流/背压保护（`RATE_LIMITED` / `BACKPRESSURE_DROP`）
- 核心 Kafka 消费链路已落地重试与按源 Topic 的 `.dlq` 死信回退（`asr-worker`、`translation-worker`、`tts-orchestrator` 已升级到按租户策略驱动重试/DLQ）
- 核心 Kafka 消费链路 `idempotencyKey` 判重与重复消息 no-op
- 核心 Kafka 消费链路重复失败阈值补偿信号（`ops.compensation -> platform.compensation`）
- `session-orchestrator` 查询 `control-plane` 已落地第一版熔断 + 缓存回退（fail-open/fail-closed）
- `asr-worker` 已补齐会话级 VAD 静音切段基线（命中阈值时发布 `asr.final`）
- `asr-worker` FunASR 适配已补齐生产联调基线（health 探测、并发保护、错误语义映射与引擎级指标）
- `translation-worker` OpenAI 适配已补齐生产联调基线（health 探测、并发保护、错误语义映射与引擎级指标）
- `tts-orchestrator` HTTP synthesis 适配已补齐生产联调基线（health 探测、并发保护、错误语义映射与引擎级指标）
- `speech-gateway` 下行链路已补充仓库内 E2E 稳定性验证（顺序、终态、重复/异常计数）
- `deploy/monitoring` 已补齐 Prometheus/Grafana 资产（含 Kafka lag、延迟、错误率看板与告警规则）
- `deploy/monitoring` 已补齐 Alertmanager 通知路由基线（default/warning/critical）
- `tools/loadtest-alert-closure.sh` 已升级为多场景（smoke/baseline/stress）压测聚合收口，并产出机器可读报告
- `tools/fault-drill-closure.sh` 已补齐 ASR/Translation/TTS 故障演练收口，并产出机器可读报告
- `tools/preprod-drill-closure.sh` 已补齐预发一键收口入口（loadtest/fault-drill/Alertmanager 恢复采样聚合）
- 全仓测试与 `tools/verify.sh` 校验基线

## 2. 服务模块现状

| 服务 | 端口 | 当前已实现 | 当前未实现 |
| --- | --- | --- | --- |
| `speech-gateway` | `8080` | WebFlux 启动、`/ws/audio`、`session.start` / `session.ping` / `audio.frame` / `session.stop` 路由、`audio.ingress.raw` Kafka 发布、会话级限流/背压控制、错误下行 `session.error`、Kafka 驱动的 `subtitle.partial` / `subtitle.final` / `session.closed` 下行、下行 E2E 稳定性测试基线 | 鉴权、更完整的下行聚合策略 |
| `session-orchestrator` | `8081` | `POST /api/v1/sessions:start`、`POST /api/v1/sessions/{sessionId}:stop`、控制面策略校验、控制面熔断与缓存回退、Redis 会话状态、`session.control` Kafka 发布 | 超时编排、结果聚合、补偿工作流 |
| `asr-worker` | `8082` | 消费 `audio.ingress.raw`、默认 placeholder 推理 + 可切换 HTTP/FunASR ASR 适配（含 FunASR v2 响应兼容、health 探测、并发保护、错误语义映射）、按稳定度 + VAD 静音切段分流发布 `asr.partial` / `asr.final`、按租户策略驱动重试/DLQ（含控制面失败回退） | FunASR 真机容量/故障演练、高级上下文与切段策略 |
| `translation-worker` | `8083` | 消费 `asr.final`、默认 placeholder 翻译 + 可切换 HTTP/OpenAI 翻译适配（含 OpenAI v2 响应兼容、health 探测、并发保护、错误语义映射）、发布 `translation.result`、按租户策略驱动重试/DLQ（含控制面失败回退） | OpenAI 真机容量/故障演练、术语治理、上下文增强 |
| `tts-orchestrator` | `8084` | 消费 `translation.result`、规则 voice 选择 + 可切换 HTTP voice-policy 适配、可切换 HTTP TTS synthesis 适配（含 synthesis v2 响应兼容、health 探测、并发保护、错误语义映射）、生成 cacheKey、发布 `tts.request`/`tts.chunk`/`tts.ready`、`tts.ready` 支持可配置 S3/MinIO 上传并回填真实 `playbackUrl`、支持 `cache-control` 与 `expires/sig` URL 签名策略、按租户策略驱动重试/DLQ（含控制面失败回退） | TTS 真机容量/故障演练、对象存储高可用治理、CDN 区域路由与高级缓存治理 |
| `control-plane` | `8085` | `PUT/GET /api/v1/tenants/{tenantId}/policy`、Redis 策略存储、版本化 upsert、灰度/回退/可靠性策略字段 | 认证鉴权、持久化数据库、动态策略下发 |

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
| `tts.chunk` | `tts-orchestrator` | 暂无仓库内下游 | TTS 分片输出，供实时播放链路接入 |
| `tts.ready` | `tts-orchestrator` | 暂无仓库内下游 | TTS 回放就绪输出，供对象存储/CDN 链路接入 |

同时，`speech-gateway` 当前也消费以下下行 Topic 并回推 WebSocket：

- `asr.partial` -> `subtitle.partial`
- `translation.result` -> `subtitle.final`
- `session.control(status=CLOSED)` -> `session.closed`

## 5. 当前缺口

- `translation.request` 仍是计划扩展 Topic
- ASR / Translation / TTS 已落地第一版生产联调基线，并补齐仓库内 fault-drill 收口与预发收口入口；但尚未完成真实流量闭环与预发/生产容量实战
- 对象存储 HA 治理、CDN 区域路由/多级缓存治理、完整补偿编排、自适应熔断/灰度治理，以及压测/告警升级实战证据仍待完善

## 6. 文档使用建议

- 看当前行为：优先读本文件
- 看外部契约：读 `contracts.md` 与 `api/`
- 看目标架构：读 `architecture.md`
- 看历史背景：读 `html/README.md` 与 `docs/html/*.html`
