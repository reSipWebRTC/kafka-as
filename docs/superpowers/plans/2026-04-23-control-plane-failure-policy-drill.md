# Plan: Control-Plane Auth Failure Policy Drill (Simulated)

## Goal

补齐第 3 项能力：在无真实 IAM 环境下，完成 `external-iam/hybrid` 失败策略（JWKS 不可用、超时、回退）与指标/告警的可审计演练证据。

## Tasks

### Task 1: 失败分类健壮性

- [x] 强化 JWKS 解码失败的 unavailable 分类（连接拒绝/超时/远端 JWK 集不可达）
- [x] 补齐对应单测（包含 invalid token 对照）

### Task 2: 一键 simulated 演练脚本

- [x] 新增 `tools/control-plane-auth-failure-drill.sh`
- [x] 覆盖场景：JWKS 不可用、JWKS 超时、hybrid fallback、指标计数、告警规则存在性
- [x] 输出 JSON + Markdown 报告（标记 `mode=simulated/mock`）

### Task 3: 文档同步

- [x] 在 runbook 增加失败策略演练入口与产物
- [x] 在 automation 增加脚本使用说明
- [x] 在 implementation status 记录“simulated 演练能力已补齐”

## Verification

- [x] `./gradlew :services:control-plane:test --no-daemon`
- [x] `tools/control-plane-auth-failure-drill.sh`
- [x] `tools/verify.sh`
