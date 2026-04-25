# Platform DLQ Replay Runbook

## 1. 目标

为 `platform.dlq` 提供统一的排障与重放路径：

1. 拉取 `platform.dlq` 事件。
2. 按租户 / 来源 Topic / 原因筛选候选集。
3. 默认 dry-run 预演。
4. 显式 apply 执行重放。
5. 产出报告和失败明细用于审计。

## 2. 执行入口

在仓库根目录执行：

```bash
tools/platform-dlq-replay.sh
```

默认行为：

- 从 Kafka `platform.dlq` 读取最多 `200` 条事件。
- 仅做筛选和报告（`DLQ_REPLAY_APPLY=0`）。
- 不向任何 Topic 回放。

## 3. 常用参数

- `DLQ_REPLAY_BOOTSTRAP_SERVERS`：Kafka 地址，默认 `localhost:9092`
- `DLQ_REPLAY_INPUT_TOPIC`：输入 Topic，默认 `platform.dlq`
- `DLQ_REPLAY_CONSUME_OFFSET`：消费起点，默认 `beginning`
- `DLQ_REPLAY_MAX_EVENTS`：扫描条数上限，默认 `200`
- `DLQ_REPLAY_OUTPUT_MODE`：`source-topic` / `fixed-topic`
- `DLQ_REPLAY_FIXED_TOPIC`：固定回放 Topic（仅 `fixed-topic` 模式）
- `DLQ_REPLAY_KEY_FIELD`：从原始 payload 提取 key 的字段，默认 `sessionId`
- `DLQ_REPLAY_TENANT_FILTER`：逗号分隔租户过滤
- `DLQ_REPLAY_SOURCE_TOPIC_FILTER`：逗号分隔来源 Topic 过滤
- `DLQ_REPLAY_REASON_FILTER`：逗号分隔 reason 过滤
- `DLQ_REPLAY_APPLY`：`0` dry-run，`1` 执行回放
- `DLQ_REPLAY_RATE_LIMIT_PER_SEC`：回放速率限制（每秒）
- `DLQ_REPLAY_EVENT_FILE`：JSONL 文件输入（simulated/mock）

## 4. 执行示例

### 4.1 Dry-run（推荐先执行）

```bash
DLQ_REPLAY_BOOTSTRAP_SERVERS=localhost:9092 \
DLQ_REPLAY_MAX_EVENTS=100 \
DLQ_REPLAY_TENANT_FILTER=tenant-a \
DLQ_REPLAY_SOURCE_TOPIC_FILTER=translation.result \
tools/platform-dlq-replay.sh
```

### 4.2 执行回放（按源 Topic）

```bash
DLQ_REPLAY_BOOTSTRAP_SERVERS=localhost:9092 \
DLQ_REPLAY_APPLY=1 \
DLQ_REPLAY_REASON_FILTER=MAX_RETRIES_EXCEEDED \
DLQ_REPLAY_RATE_LIMIT_PER_SEC=20 \
tools/platform-dlq-replay.sh
```

### 4.3 执行回放（固定 Topic）

```bash
DLQ_REPLAY_BOOTSTRAP_SERVERS=localhost:9092 \
DLQ_REPLAY_APPLY=1 \
DLQ_REPLAY_OUTPUT_MODE=fixed-topic \
DLQ_REPLAY_FIXED_TOPIC=replay.platform.dlq \
tools/platform-dlq-replay.sh
```

### 4.4 Simulated 模式（无 Kafka 消费）

```bash
DLQ_REPLAY_EVENT_FILE=build/reports/platform-dlq-replay/mock-platform-dlq.jsonl \
DLQ_REPLAY_APPLY=0 \
tools/platform-dlq-replay.sh
```

## 5. 产物

默认输出目录：`build/reports/platform-dlq-replay`

- `platform-dlq-replay.json`：结构化报告
- `platform-dlq-replay-summary.md`：摘要
- `platform-dlq-replay-selected.jsonl`：筛选后的候选重放事件
- `platform-dlq-replay-failed.jsonl`：无效事件与重放失败明细

## 6. 通过判定

- dry-run：`overallPass=true` 且 `parseError=0`、`invalid=0`
- apply：在 dry-run 通过前提下，`replayFailure=0`

如 `replayFailure>0`：

1. 优先检查 `platform-dlq-replay-failed.jsonl` 的 `stderr/exitCode`
2. 缩小过滤条件重试（按 tenant/sourceTopic/reason）
3. 降低 `DLQ_REPLAY_RATE_LIMIT_PER_SEC` 后再执行

## 7. 风险控制

- 默认 dry-run，避免误回放。
- 建议先按租户和来源 Topic 缩小范围再 apply。
- 执行 apply 前应确认目标 Topic 消费端幂等逻辑可用。
