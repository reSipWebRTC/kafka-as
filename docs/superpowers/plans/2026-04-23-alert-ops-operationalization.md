# Plan: Alert Ops Operationalization

## Goal

将监控告警从“基线规则”升级为“可运营配置”：

1. 阈值参数化（模板 + env）
2. 告警分级（warning/critical）
3. 通知链路（critical 升级链路）
4. 一键校验与报告

## Tasks

### Task 1: 阈值参数化

- [x] 新增 `deploy/monitoring/alert-ops.env`
- [x] 新增 Prometheus 告警模板：`kafka-asr-alerts.template.yml`
- [x] 新增 Alertmanager 路由模板：`alertmanager.template.yml`
- [x] 新增渲染脚本：`tools/render-monitoring-config.sh`
- [x] `tools/monitoring-up.sh` 启动前自动渲染

### Task 2: 分级与通知链路

- [x] 告警规则补齐 warning/critical 分级
- [x] Alertmanager 增加 critical escalation 路由
- [x] `docker-compose` 增加 `ALERTMANAGER_CRITICAL_ESCALATION_WEBHOOK_URL`

### Task 3: 一键校验与文档

- [x] 新增 `tools/alert-ops-validate.sh`（JSON/Markdown 报告）
- [x] 更新 `deploy/monitoring/README.md`
- [x] 更新 `docs/automation.md`
- [x] 更新 `docs/runbooks/loadtest-alert-closure.md`
- [x] 更新 `docs/implementation-status.md`

## Verification

- [x] `tools/alert-ops-validate.sh`
- [x] `tools/verify.sh`
