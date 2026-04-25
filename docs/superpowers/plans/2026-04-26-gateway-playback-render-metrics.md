# 2026-04-26 Gateway Playback Render Metrics

## Goal

补齐客户端播放阶段“端侧渲染卡顿”细粒度指标：在现有 `playback.metric` 基线上增加 `stall.begin` / `stall.end` 事件语义，并在网关侧形成可观测指标。

## Scope

- `speech-gateway` WebSocket 协议解码与指标聚合
- `sherpa-asr-android` 远端播放上报逻辑
- 契约文档同步（`docs/contracts.md`、`docs/implementation-status.md`）
- 单元测试补齐

## Plan

1. 扩展 `playback.metric` stage 语义，支持 `stall.begin` / `stall.end`（保留 `stall` 兼容）。
2. 更新 `PlaybackMetricMessageDecoder` 语义校验（`durationMs` 对 `stall.end`/`stall` 必填，对 `stall.begin` 非必填）。
3. 更新 `GatewayClientPlaybackMetrics`：区分 `stall.begin`/`stall.end` 的计数与时长统计。
4. Android `RemoteTtsPlayer` 拆分卡顿回调为开始/结束，`AsrViewModel` 上报对应 stage。
5. 补充 gateway 与 Android 单测，跑模块测试与 `tools/verify.sh`。

## Done Criteria

- 网关可接受并记录 `stall.begin` / `stall.end`。
- Android 在远端播放缓冲开始/结束时分别上报指标。
- 旧 `stage=stall` 仍可被网关接受并统计。
- 文档与实现保持一致，受影响测试通过。
