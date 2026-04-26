# Plan: Platform Compensation Saga Realtime v2

Date: 2026-04-26  
Branch: `feature/platform-compensation-saga-realtime-v2`  
Owner: Codex

## Goal

把跨服务补偿编排从批处理脚本扩展到服务内实时执行：

- `session-orchestrator` 实时消费 `platform.dlq`
- 按 `sourceTopic + reason` 路由 `replay | session-close | manual`
- 动作重试 + Redis 幂等状态
- 同步发布 `platform.audit` 审计证据

## Scope

- 新增 `compensation` 运行时模块（properties、state repository、consumer）
- 配置接入（`application.yml`、`SessionOrchestratorApplication`、Kafka properties）
- 单测补齐（consumer 路由/错误路径、Redis state 行为）
- 文档同步（contracts/event-model/services/implementation-status/roadmap/runbook）

## Execution

1. [x] 新增实时补偿配置与状态仓储接口/Redis 实现。
2. [x] 实现 `platform.dlq` 实时 consumer（动作分类、重试、幂等、审计发布）。
3. [x] 接入运行配置与 `platform.dlq` topic 配置项。
4. [x] 补齐关键单测（replay/session-close/manual/幂等/失败路径）。
5. [x] 同步文档与 runbook。
6. [ ] 运行 `:services:session-orchestrator:test` 与 `tools/verify.sh` 并提 PR。

## Validation

- `./gradlew :services:session-orchestrator:test --no-daemon`
- `tools/verify.sh`
