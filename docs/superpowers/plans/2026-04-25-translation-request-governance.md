# Plan: Translation Request Governance

Date: 2026-04-25  
Branch: `feature/translation-request-governance`

## Goal

把 `translation-worker` 从当前直连模式（`asr.final -> translation.result`）升级为两段式治理链路：

- `asr.final -> translation.request`
- `translation.request -> translation.result`

并保持现有重试 / DLQ / 幂等 / 补偿语义可用。

## Scope

- 契约：
  - `api/json-schema/translation.request.v1.json`
  - `api/protobuf/realtime_speech.proto`
  - `docs/contracts.md`
  - `docs/event-model.md`
  - `docs/implementation-status.md`
- `translation-worker`：
  - 新增 `translation.request` 事件模型与 publisher
  - `asr.final` 消费路径改为发布 `translation.request`
  - 新增 `translation.request` consumer 产出 `translation.result`
  - 保持租户策略驱动重试 / DLQ / 幂等 / 补偿
- 测试与收口：
  - 更新/新增 pipeline、consumer、publisher 单测
  - 运行 `:services:translation-worker:test` 与 `tools/verify.sh`

## Steps

1. 冻结契约：新增 `translation.request` schema/proto，更新文档中的 Topic 与事件语义。  
2. 改造实现：落地 `translation.request` publisher + consumer，打通两段式事件流并保留治理行为。  
3. 补齐测试并全仓验证，准备 PR 说明与联调证据。

## DoD

- `translation.request` 在契约与代码中都落地，不再是“计划扩展”状态
- `translation-worker` 具备两段式链路且重试/DLQ/幂等行为不回退
- `./gradlew :services:translation-worker:test` 通过
- `tools/verify.sh` 通过
