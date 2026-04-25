# Implementation Status

## 1. 当前基线

截至 `2026-04-25`，仓库已经从“纯资料收敛”演进为“文档 + 契约 + 服务骨架并存”的工程仓库。

当前已实现的主链路是：

- 翻译主链路：`client -> speech-gateway -> Kafka -> asr-worker -> translation-worker -> tts-orchestrator`
- 智能家居命令链路：`client -> speech-gateway -> Kafka(asr.final / command.confirm.request) -> command-worker -> Kafka(command.result) -> tts-orchestrator + speech-gateway`

配套的低频控制链路是：

`speech-gateway -> session-orchestrator -> control-plane / Redis`

这条链路已经具备：

- 统一 v1 事件 Envelope
- 11 个已落地 Topic：`audio.ingress.raw`、`session.control`、`asr.partial`、`asr.final`、`translation.result`、`tts.request`、`tts.chunk`、`tts.ready`、`tenant.policy.changed`、`command.confirm.request`、`command.result`
- 低频控制 API：会话 start/stop、租户策略 get/put/rollback
- 网关 `audio.frame` 会话级限流/背压保护（`RATE_LIMITED` / `BACKPRESSURE_DROP`）
- 核心 Kafka 消费链路已落地重试与按源 Topic 的 `.dlq` 死信回退（`asr-worker`、`translation-worker`、`tts-orchestrator`、`command-worker` 已升级到按租户策略驱动重试/DLQ）
- 核心 Kafka 消费链路 `idempotencyKey` 判重与重复消息 no-op（含 `command-worker`）
- 核心 Kafka 消费链路重复失败阈值补偿信号（`ops.compensation -> platform.compensation`，含 `command-worker`）
- `session-orchestrator` 查询 `control-plane` 已落地第一版熔断 + 缓存回退（fail-open/fail-closed）
- `session-orchestrator` 已消费 `asr.partial` / `asr.final` / `translation.result` / `tts.ready` / `command.result` 并写入会话聚合进度快照
- `session-orchestrator` 已补齐 idle/hard timeout 自动关闭基线（超时触发 `session.control(status=CLOSED)`）与 timeout 补偿信号发布
- `control-plane` 已在租户策略 upsert/rollback 后发布 `tenant.policy.changed`，运行时服务（`session-orchestrator` / `asr-worker` / `translation-worker` / `tts-orchestrator`）已消费并刷新本地策略缓存
- `control-plane` 已支持租户策略上一版本回滚（`POST /api/v1/tenants/{tenantId}/policy:rollback`）与历史快照栈
- `control-plane` 已支持指定版本回滚与分发意图发布：`policy:rollback` 可选 `targetVersion` / `distributionRegions`，`tenant.policy.changed` 已附带编排元数据（`sourcePolicyVersion` / `targetPolicyVersion` / `distributionRegions`）
- `asr-worker` 已补齐会话级 VAD 静音切段基线（命中阈值时发布 `asr.final`）
- `asr-worker` FunASR 适配已补齐生产联调基线（health 探测、并发保护、错误语义映射与引擎级指标）
- `translation-worker` OpenAI 适配已补齐生产联调基线（health 探测、并发保护、错误语义映射与引擎级指标）
- `tts-orchestrator` HTTP synthesis 适配已补齐生产联调基线（health 探测、并发保护、错误语义映射与引擎级指标）
- `tts-orchestrator` 对象存储/CDN 分发已补齐区域路由与回源回退策略基线（按租户区域映射可选路由，区域缺失可回退 origin URL）
- `tts-orchestrator` 对象键策略已支持可配置 cache scope（tenant/global）与 shard 前缀，用于缓存命中优化
- `speech-gateway` 下行链路已补充仓库内 E2E 稳定性验证（顺序、终态、重复/异常计数）
- `speech-gateway` 已补齐客户端可感知时延指标基线：`session.start -> subtitle.first/subtitle.final/tts.ready`（含重复事件幂等语义）
- `deploy/monitoring` 已补齐 Prometheus/Grafana 资产（含 Kafka lag、延迟、错误率看板与告警规则）
- `deploy/monitoring` 已补齐 Alertmanager 通知路由基线（default/warning/critical + critical escalation）
- `deploy/monitoring` 告警阈值与路由参数已支持模板化渲染（`alert-ops.env` + `tools/render-monitoring-config.sh`）
- `tools/alert-ops-validate.sh` 已补齐告警运营化一键校验（阈值顺序、分级规则覆盖、通知链路完整性）并产出机器可读报告
- `tools/loadtest-alert-closure.sh` 已升级为多场景（smoke/baseline/stress）压测聚合收口，支持吞吐门槛与容量上限证据（`capacityEvidence`）输出
- `tools/fault-drill-closure.sh` 已补齐 ASR/Translation/TTS 故障演练收口，并产出机器可读报告
- `tools/preprod-drill-closure.sh` 已补齐预发一键收口入口（loadtest/fault-drill/Alertmanager 恢复采样聚合），并输出统一 `sloEvidence`（loadtest/fault/recovery）
- `control-plane` 鉴权链路已补齐后端级决策/耗时指标与 hybrid 回退计数（`controlplane.auth.*`）
- `tools/control-plane-auth-drill.sh` 已补齐控制面鉴权预发演练脚本，并可接入 `tools/preprod-drill-closure.sh` 的 `control-auth` 阶段
- 已补齐真实 IAM 对接准备层：参数模板（`deploy/env/control-plane-iam.env.template`）、预检脚本（`tools/control-plane-iam-precheck.sh`）与 runbook 清单
- 已补齐 external-iam claim 映射与授权矩阵单测（读/写权限、租户范围、拒绝原因）
- 已补齐控制面鉴权失败策略 simulated 演练脚本（`tools/control-plane-auth-failure-drill.sh`）：覆盖 JWKS 不可用/超时分类、hybrid fallback、指标与告警规则校验
- 已补齐本地 JWKS + JWT 全链路 simulated 演练脚本（`tools/control-plane-jwks-jwt-drill.sh`）：覆盖 `external-iam` JWT 校验链路与 `hybrid` fallback 行为
- Android 客户端示例已切换 WS 命令流主链路（`sherpa-asr-android`）：`session.start/audio.frame/session.stop/command.confirm` 上行与 `command.result/tts.ready` 下行，支持 `tts.ready` 优先与本地 TTS 回退
- 全仓测试与 `tools/verify.sh` 校验基线

## 2. 服务模块现状

| 服务 | 端口 | 当前已实现 | 当前未实现 |
| --- | --- | --- | --- |
| `speech-gateway` | `8080` | WebFlux 启动、`/ws/audio`、`session.start` / `session.ping` / `audio.frame` / `session.stop` / `command.confirm` 路由、`audio.ingress.raw` / `command.confirm.request` Kafka 发布、会话级限流/背压控制、错误下行 `session.error`、Kafka 驱动的 `subtitle.partial` / `subtitle.final` / `tts.chunk` / `tts.ready` / `command.result` / `session.closed` 下行、下行 E2E 稳定性测试基线、客户端可感知时延指标基线（first/final/tts.ready）、可配置 WS token 鉴权（`Authorization: Bearer` 或 `access_token`） | 外部 IAM/RBAC 集成、更完整的下行聚合策略 |
| `session-orchestrator` | `8081` | `POST /api/v1/sessions:start`、`POST /api/v1/sessions/{sessionId}:stop`、控制面策略校验、控制面熔断与缓存回退、Redis 会话状态、`session.control` Kafka 发布、会话聚合进度消费（`asr.partial`/`asr.final`/`translation.result`/`tts.ready`/`command.result`）、idle/hard timeout 自动关闭、timeout 补偿信号发布 | 高级补偿编排与结果聚合策略优化 |
| `asr-worker` | `8082` | 消费 `audio.ingress.raw`、默认 placeholder 推理 + 可切换 HTTP/FunASR ASR 适配（含 FunASR v2 响应兼容、health 探测、并发保护、错误语义映射）、按稳定度 + VAD 静音切段分流发布 `asr.partial` / `asr.final`、按租户策略驱动重试/DLQ（含控制面失败回退） | FunASR 真机容量/故障演练、高级上下文与切段策略 |
| `translation-worker` | `8083` | 消费 `asr.final`、默认 placeholder 翻译 + 可切换 HTTP/OpenAI 翻译适配（含 OpenAI v2 响应兼容、health 探测、并发保护、错误语义映射）、发布 `translation.result`、按租户策略驱动重试/DLQ（含控制面失败回退） | OpenAI 真机容量/故障演练、术语治理、上下文增强 |
| `tts-orchestrator` | `8084` | 消费 `translation.result`（仅 `sessionMode=TRANSLATION`）与 `command.result`（仅 `sessionMode=SMART_HOME`）、规则 voice 选择 + 可切换 HTTP voice-policy 适配、可切换 HTTP TTS synthesis 适配（含 synthesis v2 响应兼容、health 探测、并发保护、错误语义映射）、生成 cacheKey、发布 `tts.request`/`tts.chunk`/`tts.ready`、`tts.ready` 支持可配置 S3/MinIO 上传并回填真实 `playbackUrl`、支持 `cache-control` 与 `expires/sig` URL 签名策略、支持区域 CDN 路由/回源回退与可配置 cache scope/shard 策略、按租户策略驱动重试/DLQ（含控制面失败回退） | TTS 真机容量/故障演练、对象存储高可用治理、CDN 区域路由与高级缓存治理 |
| `command-worker` | `8086` | 消费 `asr.final`（仅 `sessionMode=SMART_HOME`）与 `command.confirm.request`、调用 smartHomeNlu `/api/v1/command` 与 `/api/v1/confirm`、发布 `command.result`、按租户策略驱动重试/DLQ（含控制面失败回退）、`idempotencyKey` 判重与补偿信号 | smartHomeNlu 真实环境联调、命令执行侧容量/故障演练 |
| `control-plane` | `8085` | `PUT/GET /api/v1/tenants/{tenantId}/policy`、`POST /api/v1/tenants/{tenantId}/policy:rollback`（支持可选 `targetVersion` / `distributionRegions`）、Redis 策略存储、版本化 upsert/rollback、历史快照栈、灰度/回退/可靠性策略字段、可配置 Bearer Token 鉴权与授权（读/写权限 + 租户范围）、`control.auth.mode=static/external-iam/hybrid` 切换与 JWKS 外部 IAM 校验后端骨架、鉴权决策/耗时/回退指标（`controlplane.auth.*`）、真实 IAM 对接参数模板与预检工具、external-iam claim 映射与授权矩阵单测、鉴权失败策略 simulated 演练脚本、本地 JWKS + JWT 全链路 simulated 演练脚本、`tenant.policy.changed` 发布（含 `sourcePolicyVersion` / `targetPolicyVersion` / `distributionRegions`） | 外部 IAM/RBAC 提供方联调与生产级运行保障（真实参数、阈值与告警闭环）、持久化数据库、跨区域分发与高级版本编排治理、跨区域分发实际执行链路 |

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
- `command.confirm`
- 当 `gateway.auth.enabled=true` 时，连接需携带合法 token（`Authorization: Bearer` 或 query `access_token`）

当前已下发：

- `session.error`
- `subtitle.partial`
- `subtitle.final`
- `command.result`
- `tts.chunk`
- `tts.ready`
- `session.closed`

### 3.2 HTTP API

| 服务 | 接口 | 说明 |
| --- | --- | --- |
| `session-orchestrator` | `POST /api/v1/sessions:start` | 创建或幂等返回会话 |
| `session-orchestrator` | `POST /api/v1/sessions/{sessionId}:stop` | 关闭或幂等返回会话 |
| `control-plane` | `PUT /api/v1/tenants/{tenantId}/policy` | 创建/更新租户策略（当 `control.auth.enabled=true` 时需 Bearer Token 且具备写权限） |
| `control-plane` | `GET /api/v1/tenants/{tenantId}/policy` | 查询租户策略（当 `control.auth.enabled=true` 时需 Bearer Token 且具备读权限） |
| `control-plane` | `POST /api/v1/tenants/{tenantId}/policy:rollback` | 支持回滚上一版本或指定 `targetVersion` 并生成新版本；可选携带 `distributionRegions` 分发意图（当 `control.auth.enabled=true` 时需 Bearer Token 且具备写权限） |

补充说明：

- `control-plane` 当前默认鉴权模式为 `static`；已支持切换到 `external-iam` 或 `hybrid`，但生产仍需真实 IAM 参数与联调验证。
- 真实 IAM 环境到位前，可先用 `tools/control-plane-iam-precheck.sh` 完成参数与连通性预检，减少上线前变更风险。

## 4. 当前 Kafka 事件路径

主链路 Topic 当前以 `sessionId` 作为消息 Key；治理事件 `tenant.policy.changed` 使用 `tenantId` 作为 Key。

| Topic | Producer | Consumer | 说明 |
| --- | --- | --- | --- |
| `audio.ingress.raw` | `speech-gateway` | `asr-worker` | 高频音频主链路 |
| `session.control` | `session-orchestrator` | 暂无仓库内下游 | 生命周期审计与编排事件 |
| `asr.partial` | `asr-worker` | `speech-gateway` | 中间识别结果，当前用于 `subtitle.partial` |
| `asr.final` | `asr-worker` | `translation-worker`、`command-worker` | 翻译入口与 SMART_HOME 命令入口（由租户 `sessionMode` 区分） |
| `translation.result` | `translation-worker` | `tts-orchestrator`、`speech-gateway` | 当前 TTS 入口，同时用于 `subtitle.final` 下行 |
| `tts.request` | `tts-orchestrator` | 暂无仓库内下游 | TTS 编排输出，等待真实引擎接入 |
| `tts.chunk` | `tts-orchestrator` | `speech-gateway` | TTS 分片输出，当前回推到 WebSocket 下行 |
| `tts.ready` | `tts-orchestrator` | `speech-gateway` | TTS 回放就绪输出，当前回推到 WebSocket 下行 |
| `tenant.policy.changed` | `control-plane` | `session-orchestrator`、`asr-worker`、`translation-worker`、`tts-orchestrator` | 租户策略变更通知（upsert/rollback 发布，运行时消费刷新已落地；支持 `sourcePolicyVersion` / `targetPolicyVersion` / `distributionRegions` 元数据） |
| `command.confirm.request` | `speech-gateway` | `command-worker` | 客户端二次确认请求入口（`confirm_token + accept`） |
| `command.result` | `command-worker` | `speech-gateway`、`tts-orchestrator` | 智能家居命令执行回执；网关下行与 SMART_HOME TTS 路由均已落地 |

同时，`speech-gateway` 当前也消费以下下行 Topic 并回推 WebSocket：

- `asr.partial` -> `subtitle.partial`
- `translation.result` -> `subtitle.final`
- `command.result` -> `command.result`
- `tts.chunk` -> `tts.chunk`
- `tts.ready` -> `tts.ready`
- `session.control(status=CLOSED)` -> `session.closed`

## 5. 当前缺口

- `translation.request` 仍是计划扩展 Topic
- 客户端播放阶段体验指标（播放首包/播放中断率/端侧卡顿）仍待补齐
- ASR / Translation / TTS 已落地第一版生产联调基线，并补齐仓库内 fault-drill 收口与预发收口入口；但尚未完成真实流量闭环与预发/生产容量实战
- 对象存储 HA 治理、CDN 区域路由/多级缓存治理、完整补偿编排、自适应熔断/灰度治理，以及压测/告警升级实战证据仍待完善

## 6. 文档使用建议

- 看当前行为：优先读本文件
- 看外部契约：读 `contracts.md` 与 `api/`
- 看目标架构：读 `architecture.md`
- 看历史背景：读 `html/README.md` 与 `docs/html/*.html`
