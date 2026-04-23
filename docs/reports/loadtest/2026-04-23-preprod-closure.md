# Preprod Drill Closure (2026-04-23)

## 0. 当前状态判定

- `tools/preprod-drill-closure.sh` 已完成两类验证：
  - 本地联调模式（`PREPROD_SKIP_ALERT_CAPTURE=1`）通过。
  - mock Alertmanager 模式（`PREPROD_ALERTMANAGER_URL=http://127.0.0.1:19093`）通过，聚合结果 `overallPass=true`。
- 当前团队尚未提供“真实预发 Alertmanager 地址”，因此该报告暂不计入“真实预发告警恢复闭环完成”。
- 当前判定：`流程已验证可用，真实预发证据待补跑`。

## 1. 执行信息

- 执行入口：`tools/preprod-drill-closure.sh`
- 本次校验模式 A：本地联调（`PREPROD_TARGET=local-preprod`，`PREPROD_SKIP_ALERT_CAPTURE=1`）
- 本次执行时间 A（UTC）：`2026-04-23T03:27:02.268800+00:00`
- 本次校验模式 B：mock Alertmanager（`PREPROD_ALERTMANAGER_URL=http://127.0.0.1:19093`，`PREPROD_SKIP_ALERT_CAPTURE=0`）
- 本次执行时间 B（UTC）：`2026-04-23T05:37:41.756798+00:00`
- 目标环境：`preprod-cn-a`（脚本目标名，用于流程校验）
- Alertmanager：`http://127.0.0.1:19093`（mock，非真实预发）
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
- `alertCapturePass`: `true`
- `recoveryPass`: `true (recovered)`

| phase | pass | exitCode | 备注 |
| --- | --- | ---: | --- |
| loadtest | `PASS` | `0` | 已真实执行本地 loadtest 脚本（默认三场景） |
| fault-drill | `PASS` | `0` | 已真实执行本地 fault-drill 脚本（默认六场景） |

## 3. 告警触发与恢复证据

- 触发前快照：`alertmanager-alerts-before.json`
- 触发后快照：`alertmanager-alerts-after-loadtest.json`
- 演练后快照：`alertmanager-alerts-after-fault-drill.json`
- 恢复采样：`alertmanager-alerts-recovery-*.json`

恢复轨迹摘要（来自 `recovery.samples`）：

| iteration | firingCount | 备注 |
| ---: | ---: | --- |
| `0` | `0` | 基于 mock Alertmanager 的恢复采样（`recovered`） |

## 4.1 暂行执行命令（无真实预发地址）

默认不阻塞开发，先跑：

```bash
PREPROD_SKIP_ALERT_CAPTURE=1 tools/preprod-drill-closure.sh
```

## 4.2 待补跑命令（拿到真实预发地址后）

```bash
PREPROD_SKIP_ALERT_CAPTURE=0 \
PREPROD_ALERTMANAGER_URL="<real-preprod-alertmanager-url>" \
tools/preprod-drill-closure.sh
```

## 5. 结论与后续

- 若 `overallPass=false`：按 runbook 执行回滚/降级，阻断提审。
- 若 `overallPass=true`：保留本报告与原始产物，进入发布前评审。
- 本次结果说明“预发收口脚本可串起真实 loadtest/fault-drill 命令并稳定产出聚合报告”。
- 当前缺口仅剩“真实预发 Alertmanager 地址 + 真实恢复轨迹回填”。
- 拿到真实地址后，按 `4.2` 补跑并覆盖本报告中的 mock 标记字段。
- 持续动作：每次涉及网关下行、治理策略、真实引擎配置变更，均需回填同类预发报告。
