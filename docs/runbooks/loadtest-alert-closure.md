# Loadtest & Alert Closure Runbook

## 1. 目标

建立一条可重复执行的闭环：

1. 运行多场景网关压测并产出聚合报告。
2. 运行 ASR/Translation/TTS 故障演练测试并产出聚合报告。
3. 在预发环境执行真实链路收口并采集告警恢复证据。
4. 对照门槛判断是否退化。
5. 按告警阈值和处置流程执行回滚/升级。

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
LOADTEST_SCENARIOS="smoke baseline stress" \
LOADTEST_BASELINE_SESSIONS=24 \
LOADTEST_BASELINE_FRAMES_PER_SESSION=180 \
LOADTEST_BASELINE_MIN_THROUGHPUT_FPS=0 \
LOADTEST_STRESS_SESSIONS=48 \
LOADTEST_STRESS_FRAMES_PER_SESSION=240 \
LOADTEST_CAPACITY_TARGET_SCENARIO=stress \
tools/loadtest-alert-closure.sh
```

`tools/loadtest-alert-closure.sh` 默认场景：

- `smoke`
- `baseline`
- `stress`

场景级覆盖变量（优先于全局变量）：

- `LOADTEST_<SCENARIO>_SESSIONS`
- `LOADTEST_<SCENARIO>_FRAMES_PER_SESSION`
- `LOADTEST_<SCENARIO>_MIN_SUCCESS_RATIO`
- `LOADTEST_<SCENARIO>_MAX_P95_LATENCY_MS`
- `LOADTEST_<SCENARIO>_MIN_THROUGHPUT_FPS`

容量证据参数：

- `LOADTEST_CAPACITY_TARGET_SCENARIO`（默认取最后一个场景）
- 聚合报告会输出 `capacityEvidence.targetScenarioPass` 与 `capacityEvidence.highestPassingScenario`

压测产物：

- `build/reports/loadtest/gateway-pipeline-loadtest-aggregate.json`
- `build/reports/loadtest/gateway-pipeline-loadtest-summary.md`
- `build/reports/loadtest/gateway-pipeline-loadtest-<scenario>.json`
- `build/reports/loadtest/gateway-pipeline-loadtest-<scenario>.log`
- `build/reports/loadtest/gateway-pipeline-loadtest.json`（兼容旧路径，优先复制 baseline）

再执行故障演练收口：

```bash
tools/fault-drill-closure.sh
```

可选参数：

```bash
FAULT_DRILL_SCENARIOS="asr-engine-fault-mapping translation-engine-fault-mapping tts-engine-fault-mapping" \
tools/fault-drill-closure.sh
```

故障演练产物：

- `build/reports/fault-drill/fault-drill-closure.json`
- `build/reports/fault-drill/fault-drill-closure-summary.md`
- `build/reports/fault-drill/fault-drill-<scenario>.log`

预发环境一键收口入口：

```bash
PREPROD_TARGET=preprod-cn-a \
PREPROD_ALERTMANAGER_URL="https://alertmanager.preprod.example.com" \
PREPROD_LOADTEST_COMMAND="bash scripts/preprod/loadtest.sh" \
PREPROD_FAULT_DRILL_COMMAND="bash scripts/preprod/fault-drill.sh" \
PREPROD_AUTH_DRILL_COMMAND="tools/control-plane-auth-drill.sh" \
PREPROD_AUTH_DRILL_REQUIRED=1 \
PREPROD_LOADTEST_REPORT_PATH="build/reports/loadtest/gateway-pipeline-loadtest-aggregate.json" \
PREPROD_FAULT_DRILL_REPORT_PATH="build/reports/fault-drill/fault-drill-closure.json" \
PREPROD_RECOVERY_MAX_SECONDS=900 \
PREPROD_WATCH_ALERTS="GatewayWsErrorRateHigh,PipelineErrorRateHigh,KafkaConsumerLagHigh,ControlPlaneAuthDenyRateHigh,ControlPlaneExternalIamUnavailableSpike,ControlPlaneHybridFallbackSpike" \
tools/preprod-drill-closure.sh
```

预发收口产物：

- `build/reports/preprod-drill/preprod-drill-closure.json`
- `build/reports/preprod-drill/preprod-drill-closure-summary.md`
- `build/reports/preprod-drill/preprod-loadtest.log`
- `build/reports/preprod-drill/preprod-fault-drill.log`
- `build/reports/preprod-drill/preprod-control-auth.log`（配置 `PREPROD_AUTH_DRILL_COMMAND` 时）
- `build/reports/preprod-drill/alertmanager-*.json`

控制面鉴权演练脚本（外部 IAM/RBAC）：

```bash
CONTROL_AUTH_DRILL_BASE_URL="https://control-plane.preprod.example.com" \
CONTROL_AUTH_DRILL_TENANT_ID="tenant-a" \
CONTROL_AUTH_DRILL_READ_TOKEN="..." \
CONTROL_AUTH_DRILL_WRITE_TOKEN="..." \
CONTROL_AUTH_DRILL_ENABLE_CROSS_TENANT_CHECK=1 \
CONTROL_AUTH_DRILL_CROSS_TENANT_ID="tenant-b" \
tools/control-plane-auth-drill.sh
```

鉴权演练产物：

- `build/reports/preprod-drill/control-plane-auth-drill.json`
- `build/reports/preprod-drill/control-plane-auth-drill-summary.md`
- `build/reports/preprod-drill/control-plane-auth-*.body.json`

可选校验参数（用于适配不同 IAM 策略）：

- `CONTROL_AUTH_DRILL_EXPECT_MISSING_TOKEN_STATUS`（默认 `401`）
- `CONTROL_AUTH_DRILL_EXPECT_WRITE_STATUS`（默认 `200`）
- `CONTROL_AUTH_DRILL_EXPECT_READ_STATUS`（默认 `200`）
- `CONTROL_AUTH_DRILL_EXPECT_READ_PUT_STATUS`（默认 `403`）
- `CONTROL_AUTH_DRILL_EXPECT_CROSS_TENANT_STATUS`（默认 `403`）

调试模式（仅验证脚本流程，不访问预发服务）：

```bash
PREPROD_DRY_RUN=1 tools/preprod-drill-closure.sh
```

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

- `tools/loadtest-alert-closure.sh` 聚合报告中 `overallPass` 必须为 `true`。
- 每个 loadtest 场景必须满足其门槛：
  - `successRatio >= minSuccessRatio`
  - `frameLatencyMsP95 <= maxP95LatencyMs`
  - `throughputFramesPerSecond >= minThroughputFps`（当 `minThroughputFps > 0` 时）
- `capacityEvidence.targetScenarioPass` 必须为 `true`，且应产出 `highestPassingScenario`。
- baseline 场景必须满足 `gatewayWsErrorCount == 0` 且 `downlinkErrorCount == 0`（基线阶段要求无错误）。
- `tools/fault-drill-closure.sh` 聚合报告中 `overallPass` 必须为 `true`。
- `tools/preprod-drill-closure.sh` 聚合报告中 `overallPass` 必须为 `true`。
- 若配置 `PREPROD_AUTH_DRILL_COMMAND`，`control-auth` phase 必须为 `PASS`。
- `tools/preprod-drill-closure.sh` 必须记录 “告警触发后恢复” 采样轨迹（`recovery.samples`）。
- `tools/preprod-drill-closure.sh` 的 `sloEvidence.pass` 必须为 `true`，且：
  - `sloEvidence.loadtest.pass=true`（若 `PREPROD_REQUIRE_LOADTEST_EVIDENCE=1`）
  - `sloEvidence.faultDrill.pass=true`（若 `PREPROD_REQUIRE_FAULT_EVIDENCE=1`）
  - `sloEvidence.recovery.pass=true`（`durationSeconds <= PREPROD_RECOVERY_MAX_SECONDS`）

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
| `ControlPlaneAuthDenyRateHigh` | `controlplane_auth_decision_total`（mode=`external_iam|hybrid`） | 检查 IAM 权限变更、token audience/claim 映射与租户范围策略 |
| `ControlPlaneExternalIamUnavailableSpike` | `controlplane_auth_decision_total{reason="external_unavailable"}` | 检查 JWKS/issuer 连通性，必要时切到 `hybrid` 保障可用性 |
| `ControlPlaneHybridFallbackSpike` | `controlplane_auth_hybrid_fallback_total` | 检查 external IAM 稳定性与 fallback 比例，避免长期退化为 static |

## 6. 升级策略

- 同一告警连续 `> 30m` 未恢复，升级到负责人处理。
- 同时出现 `GatewayWsErrorRateHigh + KafkaConsumerLagHigh`，按 P1 处理，优先恢复用户体验链路。
- 若告警和压测结果均退化，优先回滚最近涉及网关/管道的变更。

推荐值班分派（可按团队实际覆盖调整）：

- `severity=warning`：当班开发处理，`15m` 内确认，`30m` 内给出处理结论。
- `severity=critical`：直接升级到值班 owner，`10m` 内拉起应急通话。
- `critical` 且影响用户链路：同步通知产品/运营，执行回滚优先。

## 7. 记录要求

每次执行后记录到版本化报告（示例：`docs/reports/loadtest/2026-04-22-baseline.md`、`docs/reports/loadtest/2026-04-23-closure.md`、`docs/reports/loadtest/2026-04-23-preprod-closure.md`），至少包含：

- 执行参数
- 关键指标结果
- 是否通过门槛
- 是否触发告警/回滚动作
