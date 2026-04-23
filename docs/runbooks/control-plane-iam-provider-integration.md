# Control-Plane External IAM/RBAC Provider Integration Runbook

## 1. 目标

在不改变当前 `control-plane` API 行为的前提下，把鉴权模式从 `static` 平滑切到 `external-iam` / `hybrid`，并形成可审计的预发证据。

## 2. 需要向 IAM 团队获取的真实参数

必须项：

1. `issuer`（完整 URL）
2. `audience`（control-plane 受众）
3. `jwks_uri`
4. claim 映射：
   - 权限 claim（默认 `scp`）
   - 租户范围 claim（默认 `tenant_ids`）
5. 权限名映射：
   - 读权限（默认 `control.policy.read`）
   - 写权限（默认 `control.policy.write`）

联调演练项：

1. 只读 token（具备读权限和目标租户范围）
2. 读写 token（具备读写权限和目标租户范围）
3. 跨租户验证 token（可选）

## 3. 参数模板与预检

复制模板：

```bash
cp deploy/env/control-plane-iam.env.template .secrets/control-plane-iam.env
```

填充后执行预检（推荐）：

```bash
tools/control-plane-iam-precheck.sh --env-file .secrets/control-plane-iam.env
```

如果当前只想验证结构，不做网络连通性：

```bash
tools/control-plane-iam-precheck.sh \
  --env-file deploy/env/control-plane-iam.env.template \
  --skip-network \
  --allow-placeholder-values
```

预检产物：

- `build/reports/control-plane-iam-precheck/control-plane-iam-precheck.json`
- `build/reports/control-plane-iam-precheck/control-plane-iam-precheck-summary.md`

### 3.1 Claim 映射与授权矩阵回归（simulated）

在接入真实 IAM 前，先用仓库内单测验证 claim 映射和授权矩阵行为：

```bash
./gradlew :services:control-plane:test --no-daemon --tests "*ExternalIamAuthBackendTests"
```

重点检查：

1. claim 名映射：默认 `scp`/`tenant_ids` 与自定义 claim 名都可生效。
2. 读写矩阵：GET/HEAD 需要读权限，PUT 需要写权限。
3. 租户范围：跨租户访问被拒绝。
4. 拒绝原因：`OPERATION_DENIED`、`TENANT_SCOPE_DENIED`、`MISSING_OR_INVALID_TOKEN`。

## 4. 切换策略

推荐顺序：

1. `static`（当前稳定基线）
2. `hybrid`（外部 IAM 失败时回退到 static）
3. `external-iam`（确认外部 IAM 稳定后切换）

关键环境变量：

- `CONTROL_AUTH_ENABLED=true`
- `CONTROL_AUTH_MODE=hybrid|external-iam`
- `CONTROL_AUTH_EXTERNAL_ISSUER`
- `CONTROL_AUTH_EXTERNAL_AUDIENCE`
- `CONTROL_AUTH_EXTERNAL_JWKS_URI`
- `CONTROL_AUTH_EXTERNAL_PERMISSION_CLAIM`
- `CONTROL_AUTH_EXTERNAL_TENANT_CLAIM`
- `CONTROL_AUTH_EXTERNAL_READ_PERMISSION`
- `CONTROL_AUTH_EXTERNAL_WRITE_PERMISSION`
- `CONTROL_AUTH_EXTERNAL_CONNECT_TIMEOUT_MS`
- `CONTROL_AUTH_EXTERNAL_READ_TIMEOUT_MS`

## 5. 预发演练闭环

仅鉴权链路演练：

```bash
CONTROL_AUTH_DRILL_BASE_URL="https://control-plane.preprod.example.com" \
CONTROL_AUTH_DRILL_TENANT_ID="tenant-a" \
CONTROL_AUTH_DRILL_READ_TOKEN="<read-token>" \
CONTROL_AUTH_DRILL_WRITE_TOKEN="<write-token>" \
CONTROL_AUTH_DRILL_ENABLE_CROSS_TENANT_CHECK=1 \
CONTROL_AUTH_DRILL_CROSS_TENANT_ID="tenant-b" \
tools/control-plane-auth-drill.sh
```

全链路收口（含 control-auth phase）：

```bash
PREPROD_AUTH_DRILL_REQUIRED=1 \
PREPROD_AUTH_DRILL_COMMAND="tools/control-plane-auth-drill.sh" \
tools/preprod-drill-closure.sh
```

### 5.1 失败策略演练（simulated/mock）

在真实 IAM 环境尚未到位时，先跑 simulated 演练，验证失败策略与告警规则：

```bash
tools/control-plane-auth-failure-drill.sh
```

产物：

- `build/reports/preprod-drill/control-plane-auth-failure-drill.json`
- `build/reports/preprod-drill/control-plane-auth-failure-drill-summary.md`
- `build/reports/preprod-drill/control-plane-auth-failure-*.log`

覆盖项：

1. JWKS 不可用分类（missing uri / remote jwk set fetch fail）
2. JWKS 超时分类（connect/read timeout）
3. `hybrid` 回退逻辑与 fallback 指标计数
4. 关键告警规则存在性（deny ratio / external unavailable / hybrid fallback）

## 6. 通过标准

1. 预检 `overallPass=true`
2. `control-plane-auth-drill` `overallPass=true`
3. `preprod-drill-closure` `overallPass=true`
4. `control-plane-auth-failure-drill`（simulated）`overallPass=true`
5. 关键告警无持续异常：
   - `ControlPlaneAuthDenyRateHigh`
   - `ControlPlaneExternalIamUnavailableSpike`
   - `ControlPlaneHybridFallbackSpike`

## 7. 风险与回退

风险：

1. `issuer/audience/jwks_uri` 参数错配导致全量 401。
2. claim 名称或权限字符串不一致导致高比例 403。
3. 外部 IAM 短时不可用导致请求抖动。

回退动作：

1. 紧急切回 `CONTROL_AUTH_MODE=hybrid`（保留 static fallback）。
2. 若仍异常，切回 `CONTROL_AUTH_MODE=static`。
3. 记录告警与变更窗口，复盘后再推进下一轮。
