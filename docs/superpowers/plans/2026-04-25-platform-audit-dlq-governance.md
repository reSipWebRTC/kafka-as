# Plan: Platform Audit and DLQ Governance

Date: 2026-04-25  
Branch: `feature/platform-audit-dlq-governance`

## Goal

落地统一事件治理主题：

- `platform.audit`
- `platform.dlq`

并将当前各服务分散的补偿/死信元信息纳入统一审计与排障路径。

## Scope

- 契约与文档：
  - `api/json-schema/platform.audit.v1.json`
  - `api/json-schema/platform.dlq.v1.json`
  - `api/protobuf/realtime_speech.proto`
  - `docs/contracts.md`
  - `docs/event-model.md`
  - `docs/implementation-status.md`
- 实现：
  - 新增统一发布器（按主题发布审计与 DLQ 事件）
  - 核心 consumer 失败路径接入统一 `platform.dlq` 上报（保留已有 `<source>.dlq` 行为）
  - 关键控制操作接入 `platform.audit` 事件发布
- 验证：
  - 相关服务单测
  - `tools/verify.sh`

## Steps

1. 冻结 `platform.audit/platform.dlq` 契约并同步文档。  
2. 在核心失败与治理路径接入统一事件发布，不破坏现有重试/DLQ行为。  
3. 补齐测试与回归，提 PR 收口。

## DoD

- `platform.audit` / `platform.dlq` 契约落地并被实现使用
- 原有链路行为不回退（重试、幂等、按源 `.dlq`）
- `tools/verify.sh` 通过
