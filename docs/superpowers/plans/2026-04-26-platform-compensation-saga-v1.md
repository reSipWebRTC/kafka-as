# Plan: Platform Compensation Saga v1

Date: 2026-04-26  
Branch: `feature/session-compensation-saga-v1`  
Owner: Codex

## Goal

补齐 `platform.dlq` 自动恢复之后的跨服务补偿编排缺口，提供第一版可执行 Saga 批处理入口：

- 统一动作路由：`replay | session-close | manual`
- 统一审计证据：生成（可选发布）`platform.audit`
- 统一状态账本：避免同一 `eventId` 重复处理

## Scope

- 新增脚本：`tools/platform-compensation-saga.sh`
- 复用：`tools/platform-dlq-replay.sh`
- 新增 runbook：`docs/runbooks/platform-compensation-saga.md`
- 文档同步：`docs/automation.md`、`docs/event-model.md`、`docs/roadmap.md`、`docs/implementation-status.md`、`docs/services.md`、`docs/README.md`

## Execution

1. [x] 实现 `platform.dlq` 读取/筛选/动作分类（含 `actionFilter` 与账本去重）。
2. [x] 接入 replay 子流程执行（dry-run/apply）。
3. [x] 实现 session-close 子流程执行（调用 `session-orchestrator` stop API）。
4. [x] 生成统一报告与 `platform.audit` 证据（apply 模式可发布 Kafka）。
5. [x] 增加 runbook 和自动化文档入口。
6. [ ] 运行 `tools/verify.sh` 全仓收口并提 PR。

## Validation

- `COMPENSATION_SAGA_EVENT_FILE=<mock.jsonl> COMPENSATION_SAGA_APPLY=0 tools/platform-compensation-saga.sh`
- `tools/verify.sh`
