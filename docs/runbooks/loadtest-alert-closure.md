# Loadtest & Alert Closure Runbook

## 1. 目标

建立一条可重复执行的闭环：

1. 运行网关压测夹具并产出报告。
2. 对照门槛判断是否退化。
3. 按告警阈值和处置流程执行回滚/升级。

## 2. 执行节奏

- 代码变更触发：凡修改 `speech-gateway` 下行映射、流控、消息路由、幂等判重逻辑时，必须执行一次。
- 日常节奏：主分支每周至少执行一次。
- 发布前：灰度前必须执行一次并留档。

## 3. 执行步骤

在仓库根目录执行：

```bash
tools/loadtest-alert-closure.sh
```

可选参数：

```bash
LOADTEST_SESSIONS=48 \
LOADTEST_FRAMES_PER_SESSION=240 \
LOADTEST_MIN_SUCCESS_RATIO=0.999 \
LOADTEST_MAX_P95_LATENCY_MS=1500 \
tools/loadtest-alert-closure.sh
```

产物：

- `build/reports/loadtest/gateway-pipeline-loadtest.json`
- `build/reports/loadtest/gateway-pipeline-loadtest-summary.md`

### 3.1 告警路由启动与检查

在仓库根目录先启动观测栈：

```bash
tools/monitoring-up.sh
```

检查 Alertmanager 路由与告警状态：

```bash
curl -s http://localhost:9093/api/v2/status
curl -s http://localhost:9093/api/v2/alerts
```

如需对接真实通知通道（飞书/Slack/PagerDuty/Webhook），先配置：

```bash
export ALERTMANAGER_DEFAULT_WEBHOOK_URL="https://alerts.example.com/default"
export ALERTMANAGER_WARNING_WEBHOOK_URL="https://alerts.example.com/warning"
export ALERTMANAGER_CRITICAL_WEBHOOK_URL="https://alerts.example.com/critical"
tools/monitoring-up.sh
```

## 4. 通过/失败判定

- 必须满足 `successRatio >= 0.999`。
- 必须满足 `frameLatencyMsP95 <= 1500ms`。
- 必须满足 `gatewayWsErrorCount == 0` 且 `downlinkErrorCount == 0`（基线阶段要求无错误）。

若任一条件失败，当前分支不得提审。

## 5. 告警联动处理

当 Prometheus 告警触发后，按下表处理：

| 告警 | 首要检查 | 处置动作 |
| --- | --- | --- |
| `GatewayWsErrorRateHigh` | `gateway_ws_messages_total` 按错误码拆分 | 先检查最近网关变更与异常码分布，必要时回滚网关 |
| `DownlinkErrorRateHigh` | `gateway_downlink_messages_total`、补偿信号 | 检查下行 payload 兼容性与幂等键冲突，必要时降级下行 |
| `PipelineErrorRateHigh` | `*_pipeline_messages_total{result="error"}` | 定位异常服务并切换到稳定 provider/策略 |
| `*PipelineP95LatencyHigh` | `*_pipeline_duration_seconds_bucket` | 检查外部引擎超时和积压，必要时限流或降级 |
| `KafkaConsumerLagHigh` | `kafka_consumer_records_lag_max` | 增加 consumer 并发或排查 broker 抖动 |

## 6. 升级策略

- 同一告警连续 `> 30m` 未恢复，升级到负责人处理。
- 同时出现 `GatewayWsErrorRateHigh + KafkaConsumerLagHigh`，按 P1 处理，优先恢复用户体验链路。
- 若告警和压测结果均退化，优先回滚最近涉及网关/管道的变更。

推荐值班分派（可按团队实际覆盖调整）：

- `severity=warning`：当班开发处理，`15m` 内确认，`30m` 内给出处理结论。
- `severity=critical`：直接升级到值班 owner，`10m` 内拉起应急通话。
- `critical` 且影响用户链路：同步通知产品/运营，执行回滚优先。

## 7. 记录要求

每次执行后记录到版本化报告（示例：`docs/reports/loadtest/2026-04-22-baseline.md`），至少包含：

- 执行参数
- 关键指标结果
- 是否通过门槛
- 是否触发告警/回滚动作
