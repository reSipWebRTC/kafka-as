# Plan: Client Perceived Metrics Baseline

Date: 2026-04-25  
Branch: `feature/client-perceived-metrics`

## Goal

落地用户可感知体验指标基线，并接入监控资产：

- 首个字幕时间（time to first subtitle）
- 最终字幕时间（time to final subtitle）
- TTS ready 时间（time to tts ready）

## Scope

- `speech-gateway`：按 `sessionId` 记录 session.start 到下行关键事件的时延并打点
- `deploy/monitoring`：Prometheus 规则与 Grafana 面板新增体验指标
- `tools/`：补最小 drill/验证脚本（可选）
- 文档同步：`docs/implementation-status.md`、`docs/observability.md`

## Steps

1. Gateway 增加会话级首包/终态/TTS ready 指标埋点与测试。  
2. 监控资产新增体验指标可视化与告警阈值基线。  
3. 跑 `:services:speech-gateway:test` + `tools/verify.sh`，更新文档并开 PR。

## DoD

- 指标在本地可见（prometheus endpoint 可抓取）
- 单测覆盖指标触发与幂等语义
- `tools/verify.sh` 通过
