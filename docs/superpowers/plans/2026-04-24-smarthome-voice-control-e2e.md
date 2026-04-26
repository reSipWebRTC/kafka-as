# 语音控制智能家居完整方案与实现计划（本地先闭环，后续平滑上云）

## Summary

- 目标能力：一套完整的“按住说话 -> 识别 -> 意图/策略 -> 设备执行 -> 回执播报”的智能家居语音控制系统。
- 技术路线：`sherpa-asr-android` 只做采集/交互，数据面统一走 `kafka-asr`；`smartHomeNlu` 作为意图与执行中枢，通过新增 `command-worker` 接入事件链路。
- 已冻结决策：`command-worker` 独立部署、按租户策略触发、新增 `command.result` 下行、确认流一期做完整、`userId` 一期贯穿全链路。
- 约束保持不变：高频音频不经 `session-orchestrator`；`subtitle.partial`/`subtitle.final` 语义不变；`tts.ready` 优先，保留本地 TTS fallback。

## Key Design & Contracts

- 会话模式：control-plane 租户策略新增 `sessionMode`，取值 `TRANSLATION`（默认）/`SMART_HOME`。
- WS 上行变更：
- `session.start` 新增必填 `userId`。
- 新增 `command.confirm`（`sessionId`,`seq`,`confirmToken`,`accept`,`traceId`）。
- WS 下行新增：
- `command.result`（`sessionId`,`seq`,`status`,`code`,`replyText`,`retryable`,`confirmToken?`,`expiresInSec?`）。
- Kafka 新增事件：
- `command.confirm.request`（gateway 发布，command-worker 消费）。
- `command.result`（command-worker 发布，gateway 与 tts-orchestrator 消费）。
- 现有事件 envelope 增加 `userId` 并贯穿：至少 `audio.ingress.raw`、`asr.partial`、`asr.final`（并同步 schema/proto/Java record）。
- `smartHomeNlu` 调用约定：
- 命令：`POST /api/v1/command`，入参 `session_id,user_id,text,user_role`。
- 确认：`POST /api/v1/confirm`，入参 `confirm_token,accept`。
- 错误语义与重试判定（command-worker 固定）：
- 业务终态不重试：`ENTITY_NOT_FOUND`,`FORBIDDEN`,`BAD_REQUEST`,`NOT_FOUND`,`CONFLICT`,`POLICY_CONFIRM_REQUIRED`,`CONFIRM_TOKEN_EXPIRED`。
- 技术失败重试+DLQ：`UPSTREAM_TIMEOUT`,`UPSTREAM_ERROR`,`INTERNAL_ERROR`。
- 重试参数复用租户策略 `retryMaxAttempts/retryBackoffMs/dlqTopicSuffix`。

## Implementation Plan (1/2/3)

1. 契约冻结与兼容迁移层
- 先更新 `docs/contracts.md` 与 `api/`（JSON Schema + proto）再改代码。
- 扩展 WS/Kafka 契约、`sessionMode`、`userId`；网关先支持“新字段必填校验 + 旧消息保护性失败码”。
- 输出：契约评审通过，生成迁移说明（旧客户端不兼容点明确）。

2. 平台实现（gateway + control-plane + command-worker + tts-orchestrator）
- `control-plane`：策略模型与 API 增加 `sessionMode`，保留版本化 upsert/rollback/tenant.policy.changed。
- `speech-gateway`：接收 `userId`，透传到 ingress 事件；新增 `command.confirm` 解码与 `command.result` 下行 consumer。
- 新建 `command-worker`：
- 消费 `asr.final`，仅 `sessionMode=SMART_HOME` 执行 smartHomeNlu。
- 发布 `command.result`。
- 消费 `command.confirm.request` 完成确认闭环。
- 接入判重、重试、DLQ、补偿信号、指标。
- `tts-orchestrator` 路由规则：
- `SMART_HOME` 租户：TTS 输入改为 `command.result`（`status=ok/confirm_required/failed` 可配置是否播报），忽略该租户 `translation.result` 的语音合成。
- `TRANSLATION` 租户：保持现状。
- `speech-gateway` 下行规则：
- 继续 `asr.partial->subtitle.partial`、`translation.result->subtitle.final`。
- 新增 `command.result->command.result`。

3. Android 迁移与本地闭环验收
- 删除本地 sherpa 识别主链路与旧 `/smart-home` 直连调用。
- 统一走 `/ws/audio`：上行 `session.start/audio.frame/session.stop/command.confirm`，下行处理 `subtitle.partial/subtitle.final/command.result/tts.ready`。
- 交互策略：
- 命令执行反馈以 `command.result` 为准。
- `confirm_required` 时展示确认 UI 并回发 `command.confirm`。
- 播报优先 `tts.ready`，超时 fallback 本地 TTS。
- 提供一键本地演练脚本与报告产物（`simulated/local` 标记），覆盖成功、确认、拒绝、超时重试、DLQ。

## Test Plan & Acceptance

- 单测：
- WS 编解码（`userId`,`command.confirm`,`command.result`）。
- command-worker code 映射与重试判定、幂等判重。
- tts-orchestrator 的 `SMART_HOME/TRANSLATION` 双模式路由。
- 集成测试：
- `asr.final -> command.result(ok) -> tts.ready -> ws.command.result`。
- `POLICY_CONFIRM_REQUIRED -> ws.command.result(confirm_required) -> command.confirm -> command.result(ok/cancel)`。
- 技术失败进入重试，超阈值入 DLQ，补偿信号可见。
- E2E 验收场景（必须全通过）：
- 开灯成功。
- 多设备同名澄清+确认成功。
- 权限拒绝。
- 设备不存在。
- 上游超时后重试成功。
- 上游持续失败进入 DLQ。
- 验收标准：
- 功能链路通过。
- 关键消息无重复执行（幂等）。
- 失败可分类、可追踪、可回放。
- `tools/verify.sh` 全仓通过。

## Assumptions & Defaults

- 当前阶段以本地/测试环境为主，不依赖真实云环境；真实 IAM/RBAC 与云部署在下一阶段接入。
- `smartHomeNlu` 作为独立运行时服务可达，默认通过 HTTP 调用。
- `sessionMode` 默认 `TRANSLATION`，仅显式配置租户进入 `SMART_HOME`，避免影响现有链路。
- 不改变现有字幕语义与会话主数据面架构，仅新增智能家居命令支线。
