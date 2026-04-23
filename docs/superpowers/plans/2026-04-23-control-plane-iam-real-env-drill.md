# Plan: Control-Plane Real IAM Env Drill

## Goal

在真实 IAM 参数就绪后，提供“可一键执行且可审计”的联调入口，串联 strict precheck、auth drill 与可选 preprod 闭环，减少手工误操作。

## Tasks

### Task 1: 一键脚本

- [x] 新增 `tools/control-plane-iam-real-env-drill.sh`
- [x] 默认读取 `.secrets/control-plane-iam.env`，支持 `--env-file`
- [x] 串行执行 `control-plane-iam-precheck`（真实值校验）+ `control-plane-auth-drill`
- [x] 支持可选 `--run-preprod-closure` 开关
- [x] 产出 JSON + Markdown 汇总报告

### Task 2: 文档同步

- [x] `docs/runbooks/control-plane-iam-provider-integration.md` 增加真实联调入口
- [x] `docs/automation.md` 增加命令和产物说明
- [x] `docs/implementation-status.md` 记录能力状态

## Verification

- [x] `tools/control-plane-iam-real-env-drill.sh --help`
- [ ] `tools/control-plane-iam-real-env-drill.sh --env-file .secrets/control-plane-iam.env`
- [x] `tools/verify.sh`

说明：第二项依赖真实 `.secrets/control-plane-iam.env` 与外部 IAM/预发连通性，本次在代码侧只做入口与文档收口。
