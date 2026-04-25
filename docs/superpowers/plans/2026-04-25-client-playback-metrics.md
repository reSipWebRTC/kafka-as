# Plan: Client Playback Stage Metrics

Date: 2026-04-25  
Branch: `feature/client-playback-metrics`

## Goal

补齐“客户端播放阶段指标”最小闭环，覆盖：

- 播放首包时延（`tts.ready -> playback start`）
- 播放中断次数与中断时长
- 回退到本地 TTS 的触发率

## Scope

- 契约层：新增 WebSocket 上行 `playback.metric` 消息定义（文档 + protobuf）
- `speech-gateway`：
  - 新增 `playback.metric` 解码与路由
  - 记录 `gateway.client.playback.total` / `gateway.client.playback.duration`
- Android (`sherpa-asr-android`)：
  - 在远端播放启动/卡顿/完成/回退路径上报 `playback.metric`
  - 保持现有 WS 命令流与本地 TTS 回退行为不变
- 监控与文档：
  - Prometheus 告警基线（播放首包 P95、中断率）
  - Grafana 看板补齐播放阶段曲线
  - `docs/contracts.md` / `docs/observability.md` / `docs/implementation-status.md` 同步

## Steps

1. 契约与协议：定义 `playback.metric` 字段和枚举约束。  
2. 网关实现：增加 decoder/router 分支和指标记录器。  
3. Android 实现：在 `RemoteTtsPlayer` 与 `AsrViewModel` 中上报播放指标。  
4. 监控和文档：新增告警规则与文档真相对齐。  
5. 验证：`./gradlew :services:speech-gateway:test`、`tools/verify.sh`，并补 Android 单测。

## DoD

- `playback.metric` 上行可被网关识别且不影响现有消息路由
- `gateway.client.playback.*` 指标在 `/actuator/prometheus` 可见
- 至少覆盖 4 类事件：`start` / `stall` / `complete` / `fallback`
- 告警规则、看板、文档与实现同步
- 仓库验证通过

