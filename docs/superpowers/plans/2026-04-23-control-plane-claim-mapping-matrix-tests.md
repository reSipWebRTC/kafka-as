# Plan: Control-Plane Claim Mapping and Authorization Matrix Tests

## Goal

补齐 `external-iam` 模式下的 claim 映射与授权矩阵测试，覆盖读写权限、租户范围与拒绝原因，确保行为可回归。

## Tasks

### Task 1: Claim 映射测试覆盖

- [x] 覆盖自定义 claim 名（permission/tenant）映射
- [x] 覆盖权限值的不同编码形式（空格分隔、逗号分隔、数组）
- [x] 覆盖租户范围 claim 的字符串/数组形式

### Task 2: 授权矩阵与拒绝原因

- [x] 覆盖 GET/HEAD（read）与 PUT（write）矩阵
- [x] 覆盖租户越权拒绝（`TENANT_SCOPE_DENIED`）
- [x] 覆盖权限不足拒绝（`OPERATION_DENIED`）
- [x] 覆盖无效 token/header 拒绝（`MISSING_OR_INVALID_TOKEN`）

### Task 3: 文档同步

- [x] 在 IAM runbook 中补充“claim 映射矩阵回归”命令与检查项
- [x] 在实现状态中记录“矩阵测试已补齐”

## Verification

- [x] `./gradlew :services:control-plane:test --no-daemon`
- [x] `tools/verify.sh`
