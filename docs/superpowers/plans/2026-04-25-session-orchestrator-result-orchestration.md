# Plan: Session-Orchestrator Result Orchestration

Date: 2026-04-25  
Branch: `feature/session-orchestrator-result-orchestration`  
Owner: Codex

## 1. Goal

补齐 `session-orchestrator` 当前缺口中的三项核心能力：

- 结果聚合（ASR / Translation / TTS / Command 终态对会话状态的汇总）
- 超时编排（会话级 idle / hard timeout）
- 基础补偿工作流（超时/失败后的可观测关闭与补偿信号）

本次优先做“可运行第一版闭环”，不引入复杂跨服务 Saga。

## 2. Scope

In scope:

- `services/session-orchestrator` 增加 Kafka 输入消费（最小集合）
- 会话聚合状态模型（Redis 落库）
- timeout scheduler（基于现有服务内定时器）
- 触发 `session.control` 的终态事件发布（`CLOSED` + reason）
- 指标与日志（timeout、compensation、aggregation latency）
- 单测 + 集成测试更新
- 文档同步（`docs/implementation-status.md`、必要时 `docs/contracts.md`）

Out of scope:

- 全量跨服务补偿编排引擎
- 新增数据库持久化
- 外部 IAM/RBAC 与控制面平台化扩展

## 3. Design

### 3.1 Aggregation Model

新增会话聚合快照（Redis）：

- `sessionId`
- `tenantId`
- `status`（ACTIVE/CLOSED）
- `lastPartialAt`
- `lastFinalAt`
- `lastTtsReadyAt`
- `lastCommandResultAt`
- `closeReason`

来源事件（第一版）：

- `asr.partial`
- `asr.final`
- `translation.result`
- `tts.ready`
- `command.result`

### 3.2 Timeout Orchestration

新增两类 timeout（可配置）：

- `idleTimeoutMs`：会话长时间无输入/无进展自动关闭
- `hardTimeoutMs`：会话总时长上限自动关闭

触发行为：

1. 标记会话 `CLOSED`
2. 发布 `session.control(status=CLOSED, reason=TIMEOUT_*)`
3. 记录指标与补偿信号

### 3.3 Compensation Baseline

对 timeout/异常关闭，发布第一版补偿事件（沿用既有补偿通道），用于后续统一治理接入。

## 4. Execution Steps

1. 引入聚合输入消费者与快照模型
2. 实现 timeout scheduler 与关闭编排
3. 补齐补偿信号、指标、错误语义
4. 测试补齐（正常路径、超时路径、重复关闭幂等）
5. 文档与运行参数更新

## 5. Validation

- `./gradlew :services:session-orchestrator:test`
- `tools/verify.sh`
- 最小联调证据：
  - 正常会话完成并关闭
  - idle timeout 自动关闭
  - hard timeout 自动关闭
  - 重复 stop/close 幂等

## 6. Risks and Guardrails

- 风险：状态竞争导致重复关闭  
  对策：会话关闭 CAS + 幂等发布

- 风险：高并发下定时器资源占用  
  对策：统一调度器 + 分桶扫描，避免每会话独立线程

- 风险：聚合消费反压影响主链路  
  对策：消费者独立 group 与限速配置，失败走重试/DLQ

## 7. Definition of Done

- `session-orchestrator` 具备可配置 timeout 自动关闭能力
- 聚合状态可用于会话终态判定
- timeout 与补偿路径具备可观测指标
- 文档与实现同步，`tools/verify.sh` 通过
