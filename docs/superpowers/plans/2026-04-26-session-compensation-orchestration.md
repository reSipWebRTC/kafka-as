# Plan: Session Compensation Orchestration (Stalled v1)

Date: 2026-04-26  
Branch: `feature/session-compensation-orchestration`  
Owner: Codex

## Goal

在现有 idle/hard timeout 编排之外，补齐第一版“会话级 stalled 补偿编排”，用于 `platform.dlq` 自动恢复后的会话治理闭环。

## Scope

- `session-orchestrator` 增加 stalled 判定（`post_final` / `post_translation`）
- 自动触发 `session.stop` 关闭会话（reason 带 stalled stage）
- 补偿双写：`platform.compensation` + `platform.audit`
- 配置项、单测与文档同步

## Steps

1. [x] 扩展 `SessionCompensationPublisher` 接口，新增 stalled 发布方法。
2. [x] 在 `KafkaSessionCompensationPublisher` 增加 `session.stalled` 补偿事件构建与双写审计。
3. [x] 在 `SessionTimeoutOrchestrator` 增加 stalled 判定与关闭执行路径。
4. [x] 增加配置项：
   - `orchestrator.session-orchestration.stalled-timeout`
   - `orchestrator.session-orchestration.stalled-timeout-reason-prefix`
5. [x] 补齐 `SessionTimeoutOrchestratorTests` 的 stalled 正常/异常路径用例。
6. [x] 同步实现文档（`services.md`、`event-model.md`、`implementation-status.md`、`roadmap.md`、模块 README）。
7. [ ] 跑全仓验证 `tools/verify.sh`。

## Validation

- `./gradlew :services:session-orchestrator:test`
- `tools/verify.sh`
