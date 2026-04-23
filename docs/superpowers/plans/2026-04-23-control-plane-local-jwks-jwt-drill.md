# Plan: Control-Plane Local JWKS + JWT End-to-End Drill

## Goal

补齐无真实 IAM 环境下的本地/测试联调能力：通过本地 mock JWKS + 签发 JWT，验证 `external-iam` 与 `hybrid` 模式的 HTTP 鉴权全链路行为并产出演练证据。

## Tasks

### Task 1: External-IAM 全链路集成测试

- [x] 新增基于本地 JWKS server 的 `external-iam` Web 层集成测试
- [x] 覆盖读写权限、租户范围和拒绝路径（401/403）
- [x] 验证 `iss/aud` + claim 映射默认配置在真实 JWT 解码链路可用

### Task 2: Hybrid 回退全链路集成测试

- [x] 新增 `hybrid` 模式下外部 IAM 不可用时回退 static 的 Web 层集成测试
- [x] 覆盖允许路径（fallback 后可读/可写）与拒绝路径（fallback 后仍无凭据）

### Task 3: 一键演练与文档

- [x] 新增 `tools/control-plane-jwks-jwt-drill.sh`
- [x] 输出 JSON + Markdown 报告（标注 `mode=simulated/mock`）
- [x] 更新 runbook / automation / implementation status

## Verification

- [x] `./gradlew :services:control-plane:test --no-daemon`
- [x] `tools/control-plane-jwks-jwt-drill.sh`
- [x] `tools/verify.sh`
