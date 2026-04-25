# Plan: Platform DLQ Replay Operations

Date: 2026-04-25  
Branch: `feature/platform-dlq-replay-operations`

## Goal

补齐 `platform.dlq` 的统一重放运营流程，使“死信排障 -> 过滤 -> 重放 -> 证据留存”形成固定路径。

## Scope

- 脚本：
  - `tools/platform-dlq-replay.sh`
- 文档：
  - `docs/runbooks/platform-dlq-replay.md`
  - `docs/automation.md`
  - `docs/implementation-status.md`
  - `docs/event-model.md`（重放状态补充）

## Steps

1. 实现 `platform.dlq` 重放脚本（默认 dry-run，显式 apply）。  
2. 落地 runbook 和自动化入口文档，给出执行参数、判定标准、回滚策略。  
3. 用 simulated 输入执行一轮 dry-run/apply 验证，并跑 `tools/verify.sh` 收口。  

## DoD

- 可以从 `platform.dlq`（或文件）筛选并重放事件到目标 topic。  
- 默认不写回放（dry-run），避免误操作；apply 模式有明确证据产物。  
- 报告产物包含回放结果、失败明细和重放路径。  
- 文档与实现一致，`tools/verify.sh` 通过。  
