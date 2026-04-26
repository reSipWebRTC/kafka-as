# Plan: Translation SessionMode Gate

Date: 2026-04-26  
Branch: `feature/translation-sessionmode-gate`  
Owner: Codex

## Goal

在 `translation-worker` 落地租户 `sessionMode` 门控：

- 仅 `sessionMode=TRANSLATION` 处理 `asr.final -> translation.request`
- `sessionMode=SMART_HOME` 场景直接忽略，不进入翻译链路

## Scope

- 扩展 `TenantReliabilityPolicy` / `ControlPlaneTenantPolicyResponse` 增加 `sessionMode`
- `TenantReliabilityPolicyResolver` 解析并归一化 `sessionMode`
- `AsrFinalConsumer` 增加 SMART_HOME 跳过逻辑与指标
- 补齐 resolver 与 consumer 单测
- 同步实现状态文档

## Execution

1. [x] 创建 feature worktree 并定位代码影响面。
2. [x] 扩展 translation policy 模型支持 `sessionMode`。
3. [x] 在 `AsrFinalConsumer` 增加 `sessionMode` 门控逻辑。
4. [x] 补齐/更新单测覆盖（resolver + consumer）。
5. [x] 同步 `docs/implementation-status.md`。
6. [x] 运行 `:services:translation-worker:test` 与 `tools/verify.sh`。

## Validation

- `./gradlew :services:translation-worker:test`
- `tools/verify.sh`
