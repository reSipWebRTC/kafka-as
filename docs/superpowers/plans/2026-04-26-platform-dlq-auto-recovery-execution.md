# Plan: Platform DLQ Auto-Recovery Execution

Date: 2026-04-26  
Branch: `feature/platform-dlq-auto-recovery-execution`

## Goal

在已有 `platform.dlq` 统一重放脚本基础上，补齐“跨服务自动恢复编排”执行闭环：

1. 自动消费/导入 `platform.dlq` 事件。
2. 结合本地恢复账本做去重，避免重复回放。
3. 自动调用 `platform-dlq-replay` 执行恢复（dry-run/apply）。
4. 产出统一报告与失败明细，形成可审计操作路径。

## Scope

- 新增脚本：`tools/platform-dlq-auto-recovery.sh`
- 新增 runbook：`docs/runbooks/platform-dlq-auto-recovery.md`
- 文档同步：
  - `docs/automation.md`
  - `docs/implementation-status.md`
  - `docs/event-model.md`

## Steps

1. 实现自动恢复编排脚本（状态账本、候选筛选、回放调用、收口报告）。
2. 增加 runbook 与自动化入口，明确参数、产物、判定标准与风险控制。
3. 用 simulated `platform.dlq` 样本执行脚本验收，再跑 `tools/verify.sh`。

## DoD

- 支持按租户/来源 Topic/原因/服务筛选候选恢复事件。
- 支持恢复账本去重，已成功恢复事件不重复执行。
- 默认 dry-run，显式 `APPLY=1` 才执行回放。
- 生成结构化 JSON 报告与 Markdown 摘要，并包含 replay 子报告引用。
- 文档与实现一致，`tools/verify.sh` 通过。
