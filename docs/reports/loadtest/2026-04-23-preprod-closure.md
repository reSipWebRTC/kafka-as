# Preprod Drill Closure (2026-04-23)

## 1. 执行信息

- 执行入口：`tools/preprod-drill-closure.sh`
- 本次校验模式：本地联调（`PREPROD_TARGET=local-preprod`，`PREPROD_SKIP_ALERT_CAPTURE=1`）
- 本次执行时间（UTC）：`2026-04-23T03:27:02.268800+00:00`
- 目标环境：`local-preprod`（预发脚本本地联调名）
- Alertmanager：`http://localhost:9093`（本次跳过抓取）
- Loadtest 命令：`tools/loadtest-alert-closure.sh`
- Fault-drill 命令：`tools/fault-drill-closure.sh`
- Watch alerts：`GatewayWsErrorRateHigh,DownlinkErrorRateHigh,PipelineErrorRateHigh,AsrPipelineP95LatencyHigh,TranslationPipelineP95LatencyHigh,TtsPipelineP95LatencyHigh,KafkaConsumerLagHigh`

产物路径：

- `build/reports/preprod-drill/preprod-drill-closure.json`
- `build/reports/preprod-drill/preprod-drill-closure-summary.md`
- `build/reports/preprod-drill/preprod-loadtest.log`
- `build/reports/preprod-drill/preprod-fault-drill.log`
- `build/reports/preprod-drill/alertmanager-*.json`

## 2. 聚合结果

- `overallPass`: `true`
- `phasesPass`: `true`
- `alertCapturePass`: `true`（跳过告警抓取）
- `recoveryPass`: `true (skipped)`

| phase | pass | exitCode | 备注 |
| --- | --- | ---: | --- |
| loadtest | `PASS` | `0` | 已真实执行本地 loadtest 脚本 |
| fault-drill | `PASS` | `0` | 已真实执行本地 fault-drill 脚本 |

## 3. 告警触发与恢复证据

- 触发前快照：`alertmanager-alerts-before.json`
- 触发后快照：`alertmanager-alerts-after-loadtest.json`
- 演练后快照：`alertmanager-alerts-after-fault-drill.json`
- 恢复采样：`alertmanager-alerts-recovery-*.json`

恢复轨迹摘要（来自 `recovery.samples`）：

| iteration | firingCount | 备注 |
| ---: | ---: | --- |
| `n/a` | `n/a` | 本次设置 `PREPROD_SKIP_ALERT_CAPTURE=1`，恢复采样跳过 |

## 4. 结论与后续

- 若 `overallPass=false`：按 runbook 执行回滚/降级，阻断提审。
- 若 `overallPass=true`：保留本报告与原始产物，进入发布前评审。
- 本次结果说明“预发收口脚本可串起真实 loadtest/fault-drill 命令并稳定产出聚合报告”，但尚未覆盖真实预发告警恢复证据。
- 下次需在真实预发环境执行并回填以下变量：`PREPROD_TARGET`、`PREPROD_ALERTMANAGER_URL`、`PREPROD_LOADTEST_COMMAND`、`PREPROD_FAULT_DRILL_COMMAND`。
- 持续动作：每次涉及网关下行、治理策略、真实引擎配置变更，均需回填同类预发报告。
