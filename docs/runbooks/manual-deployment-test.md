# Manual Deployment & Test Runbook

## 1. 目标

在没有应用层一键部署脚本的前提下，提供一套可重复执行的人工部署与验收流程，覆盖：

1. 七个核心服务启动与连通性检查。
2. `control-plane` 鉴权行为验收（静态凭据模式）。
3. 预发收口脚本执行与报告留档。

## 2. 前置条件

执行目录：仓库根目录。

基础依赖：

- Java 21
- Kafka（`KAFKA_BOOTSTRAP_SERVERS` 可达）
- Redis（`REDIS_HOST` / `REDIS_PORT` 可达）

建议先确认主干版本：

```bash
git rev-parse --short HEAD
```

再执行仓库基线校验：

```bash
tools/verify.sh
```

## 3. 部署参数（最小可用）

先加载本地功能测试默认参数（推荐）：

```bash
source tools/local-functional-test-env.sh
```

如果你需要覆盖默认值，再手工导出对应变量即可。

脚本默认依赖地址如下（可按需覆盖）：

- `KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:9092`
- `REDIS_HOST=127.0.0.1`
- `REDIS_PORT=6379`

`control-plane` 鉴权采用静态凭据（无需真实 IAM）：

```bash
export CONTROL_AUTH_ENABLED=true
export CONTROL_AUTH_MODE=static

export CONTROL_AUTH_CREDENTIALS_0_TOKEN=viewer-tenant-a
export CONTROL_AUTH_CREDENTIALS_0_READ=true
export CONTROL_AUTH_CREDENTIALS_0_WRITE=false
export CONTROL_AUTH_CREDENTIALS_0_TENANT_PATTERNS_0=tenant-a

export CONTROL_AUTH_CREDENTIALS_1_TOKEN=writer-tenant-a
export CONTROL_AUTH_CREDENTIALS_1_READ=true
export CONTROL_AUTH_CREDENTIALS_1_WRITE=true
export CONTROL_AUTH_CREDENTIALS_1_TENANT_PATTERNS_0=tenant-a
```

## 4. 人工部署步骤

按顺序在独立终端启动服务：

```bash
./gradlew :services:control-plane:bootRun
./gradlew :services:session-orchestrator:bootRun
./gradlew :services:asr-worker:bootRun
./gradlew :services:translation-worker:bootRun
./gradlew :services:command-worker:bootRun
./gradlew :services:tts-orchestrator:bootRun
./gradlew :services:speech-gateway:bootRun
```

端口映射：

- `speech-gateway` `8080`
- `session-orchestrator` `8081`
- `asr-worker` `8082`
- `translation-worker` `8083`
- `command-worker` `8086`
- `tts-orchestrator` `8084`
- `control-plane` `8085`

## 5. 部署后验收

### 5.1 健康检查

```bash
for p in 8080 8081 8082 8083 8084 8085 8086; do
  curl -sf "http://127.0.0.1:$p/actuator/health"
done
```

### 5.2 控制面鉴权演练

```bash
export CONTROL_AUTH_DRILL_BASE_URL="http://127.0.0.1:8085"
export CONTROL_AUTH_DRILL_TENANT_ID="tenant-a"
export CONTROL_AUTH_DRILL_CROSS_TENANT_ID="tenant-b"
export CONTROL_AUTH_DRILL_ENABLE_CROSS_TENANT_CHECK=1
export CONTROL_AUTH_DRILL_READ_TOKEN="viewer-tenant-a"
export CONTROL_AUTH_DRILL_WRITE_TOKEN="writer-tenant-a"
tools/control-plane-auth-drill.sh
```

预期产物：

- `build/reports/preprod-drill/control-plane-auth-drill.json`
- `build/reports/preprod-drill/control-plane-auth-drill-summary.md`

### 5.3 回滚编排接口验证（targetVersion/distributionRegions）

先创建两版策略，再触发指定版本回滚：

```bash
curl -sS -X PUT "http://127.0.0.1:8085/api/v1/tenants/tenant-a/policy" \
  -H "Authorization: Bearer writer-tenant-a" \
  -H "content-type: application/json" \
  -d '{"sourceLang":"zh-CN","targetLang":"en-US","asrModel":"funasr-v1","translationModel":"mt-v1","ttsVoice":"alloy","maxConcurrentSessions":200,"rateLimitPerMinute":6000,"enabled":true}'

curl -sS -X PUT "http://127.0.0.1:8085/api/v1/tenants/tenant-a/policy" \
  -H "Authorization: Bearer writer-tenant-a" \
  -H "content-type: application/json" \
  -d '{"sourceLang":"zh-CN","targetLang":"en-US","asrModel":"funasr-v2","translationModel":"mt-v2","ttsVoice":"alloy","maxConcurrentSessions":220,"rateLimitPerMinute":6500,"enabled":true}'

curl -sS -X POST "http://127.0.0.1:8085/api/v1/tenants/tenant-a/policy:rollback" \
  -H "Authorization: Bearer writer-tenant-a" \
  -H "content-type: application/json" \
  -d '{"targetVersion":1,"distributionRegions":["cn-east-1","ap-southeast-1"]}'
```

检查点：

- 返回 `200`。
- 返回体版本号递增（回滚生成新版本）。
- Kafka 侧 `tenant.policy.changed` 事件应包含：
  - `operation=ROLLED_BACK_TO_VERSION`
  - `sourcePolicyVersion`
  - `targetPolicyVersion`
  - `distributionRegions`

### 5.4 预发收口（无真实 Alertmanager）

```bash
PREPROD_SKIP_ALERT_CAPTURE=1 \
PREPROD_AUTH_DRILL_REQUIRED=1 \
PREPROD_AUTH_DRILL_COMMAND="tools/control-plane-auth-drill.sh" \
tools/preprod-drill-closure.sh
```

预期产物：

- `build/reports/preprod-drill/preprod-drill-closure.json`
- `build/reports/preprod-drill/preprod-drill-closure-summary.md`

## 6. 通过标准

以下全部满足才算通过：

1. 七个服务健康检查全部成功。
2. `control-plane-auth-drill.json` 中 `overallPass=true`。
3. `preprod-drill-closure.json` 中 `overallPass=true`。
4. 回滚编排接口验证成功，且事件元数据完整。

## 7. 失败定位与回退

优先排查：

1. Kafka / Redis 连通性。
2. 鉴权 token 与租户范围映射（`viewer-tenant-a` / `writer-tenant-a`）。
3. `control-plane` 鉴权模式是否被错误切到 `external-iam` 且缺少真实参数。

回退建议：

1. 将 `CONTROL_AUTH_MODE` 固定为 `static`（并保留已验证凭据）。
2. 下调演练范围，仅保留 `control-plane-auth-drill` 与核心健康检查。
3. 问题修复后重新执行本 runbook 全流程并保存报告。
