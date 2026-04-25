# Platform Compensation Saga Runbook

## 1. 目标

为 `platform.dlq` 提供第一版跨服务补偿编排执行路径：

1. 拉取 `platform.dlq` 事件（Kafka 或 JSONL）。
2. 按 `sourceTopic + reason` 分类为 `replay | session-close | manual`。
3. 执行 replay（复用 `tools/platform-dlq-replay.sh`）和会话关闭动作。
4. 生成 `platform.audit` 事件证据（apply 模式可发布到 Kafka）。
5. 记录成功账本，避免重复处理同一 `eventId`。

## 2. 执行入口

在仓库根目录执行：

```bash
tools/platform-compensation-saga.sh
```

默认行为：

- 扫描最多 `200` 条 `platform.dlq` 事件。
- 执行 dry-run（`COMPENSATION_SAGA_APPLY=0`）。
- 生成补偿动作和审计证据，但不执行真实回放/会话关闭。

## 3. 动作路由规则（v1）

- `NON_RETRYABLE` / `DESERIALIZATION_FAILED` / `UNKNOWN_EVENT_VERSION` -> `manual`
- `PROCESSING_ERROR` / `MAX_RETRIES_EXCEEDED` 且 `sourceTopic` 属于下行结果主题（`asr.partial` / `translation.result` / `tts.chunk` / `tts.ready` / `command.result`）-> `session-close`
- 其余 `PROCESSING_ERROR` / `MAX_RETRIES_EXCEEDED` -> `replay`

> 注：`session-close` 需要可解析 `sessionId`；缺失时会降级为 `manual`。

## 4. 常用参数

- `COMPENSATION_SAGA_BOOTSTRAP_SERVERS`：Kafka 地址，默认 `localhost:9092`
- `COMPENSATION_SAGA_INPUT_TOPIC`：输入 Topic，默认 `platform.dlq`
- `COMPENSATION_SAGA_CONSUME_OFFSET`：消费起点，默认 `beginning`
- `COMPENSATION_SAGA_MAX_EVENTS`：扫描条数上限，默认 `200`
- `COMPENSATION_SAGA_APPLY`：`0` dry-run，`1` 执行动作
- `COMPENSATION_SAGA_ACTION_FILTER`：动作过滤（`replay,session-close,manual`）
- `COMPENSATION_SAGA_TENANT_FILTER`：租户过滤
- `COMPENSATION_SAGA_SOURCE_TOPIC_FILTER`：来源 Topic 过滤
- `COMPENSATION_SAGA_REASON_FILTER`：reason 过滤
- `COMPENSATION_SAGA_SERVICE_FILTER`：service 过滤
- `COMPENSATION_SAGA_EVENT_FILE`：JSONL 输入（simulated/mock）
- `COMPENSATION_SAGA_SESSION_API_BASE_URL`：`session-orchestrator` 地址，默认 `http://localhost:8081`
- `COMPENSATION_SAGA_SESSION_API_TIMEOUT_SEC`：会话关闭请求超时秒数，默认 `3`
- `COMPENSATION_SAGA_PUBLISH_AUDIT`：apply 模式是否发布 `platform.audit`，默认 `1`
- `COMPENSATION_SAGA_AUDIT_TOPIC`：审计 Topic，默认 `platform.audit`

## 5. 执行示例

### 5.1 Dry-run（推荐先执行）

```bash
COMPENSATION_SAGA_TENANT_FILTER=tenant-a \
COMPENSATION_SAGA_REASON_FILTER=PROCESSING_ERROR \
tools/platform-compensation-saga.sh
```

### 5.2 Apply（执行 replay + session-close）

```bash
COMPENSATION_SAGA_BOOTSTRAP_SERVERS=localhost:9092 \
COMPENSATION_SAGA_APPLY=1 \
COMPENSATION_SAGA_ACTION_FILTER=replay,session-close \
COMPENSATION_SAGA_RATE_LIMIT_PER_SEC=20 \
tools/platform-compensation-saga.sh
```

### 5.3 Simulated 模式（无 Kafka 消费）

```bash
COMPENSATION_SAGA_EVENT_FILE=build/reports/platform-compensation-saga/mock-platform-dlq.jsonl \
COMPENSATION_SAGA_APPLY=0 \
tools/platform-compensation-saga.sh
```

## 6. 产物

默认输出目录：`build/reports/platform-compensation-saga`

- `platform-compensation-saga.json`：结构化总报告
- `platform-compensation-saga-summary.md`：摘要
- `platform-compensation-saga-candidates.jsonl`：候选 `platform.dlq` 事件
- `platform-compensation-saga-selected.jsonl`：动作选择结果
- `platform-compensation-saga-failed.jsonl`：失败/无效明细
- `platform-compensation-saga-session-close-report.json`：会话关闭执行报告
- `platform-compensation-saga-audit-events.jsonl`：生成的 `platform.audit` 事件
- `replay/platform-dlq-replay.json`：replay 子流程报告

默认成功账本：

- `build/state/platform-compensation-saga-success.jsonl`

## 7. 通过判定

- `overallPass=true`
- `parseError=0` 且 `invalid=0`
- apply 模式下 `replayFailure=0` 且 `closeFailure=0`
- 若开启审计发布（`COMPENSATION_SAGA_PUBLISH_AUDIT=1`），则 `auditPublishFailure=0`

## 8. 失败处置

1. 检查 `platform-compensation-saga-failed.jsonl` 的 `stage/error`。
2. 对失败动作缩小过滤范围（租户/主题/reason）后重试。
3. 对 `manual` 动作创建人工工单并附 `platform-compensation-saga-audit-events.jsonl` 证据。
