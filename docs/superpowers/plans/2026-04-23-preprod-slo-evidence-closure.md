# Plan: Preprod SLO Evidence Closure

## Goal

补齐“压测与故障演练”收口中的 SLO 证据维度，确保一键脚本不仅有阶段通过结果，还能产出可追溯的容量上限与恢复能力证据。

## Tasks

### Task 1: Loadtest 容量证据增强

- [x] 扩展 `tools/loadtest-alert-closure.sh`，增加 `MIN_THROUGHPUT_FPS` 阈值门限
- [x] 增加 `LOADTEST_CAPACITY_TARGET_SCENARIO` 与 capacity evidence 输出
- [x] 在聚合 JSON/Markdown 中输出 `highestPassingScenario`

### Task 2: Preprod 收口 SLO 证据聚合

- [x] 扩展 `tools/preprod-drill-closure.sh`，引入 loadtest/fault 报告读取与校验
- [x] 增加 `recovery` 时长 SLO（`PREPROD_RECOVERY_MAX_SECONDS`）判断
- [x] 输出 `sloEvidence` 与对应 checks，并纳入 `overallPass`

### Task 3: 文档与验证

- [x] 更新 `docs/runbooks/loadtest-alert-closure.md`
- [x] 更新 `docs/automation.md`
- [x] 更新 `docs/implementation-status.md`
- [x] 运行脚本验证并记录结果

## Verification

- [x] `PREPROD_DRY_RUN=1 PREPROD_SKIP_ALERT_CAPTURE=1 tools/preprod-drill-closure.sh`
- [x] `tools/verify.sh`
