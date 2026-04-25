# Platform DLQ Auto-Recovery Runbook

## 1. 目标

为 `platform.dlq` 提供“自动恢复执行”路径：

1. 拉取 `platform.dlq` 事件（Kafka 或 JSONL）。
2. 按租户/来源 Topic/原因/服务筛选恢复候选。
3. 通过恢复账本跳过已成功恢复事件，避免重复回放。
4. 自动调用 `tools/platform-dlq-replay.sh` 执行 dry-run/apply。
5. 产出统一报告与失败明细。

## 2. 执行入口

在仓库根目录执行：

```bash
tools/platform-dlq-auto-recovery.sh
```

默认行为：

- 消费最多 `200` 条 `platform.dlq` 事件。
- 执行到 replay dry-run（`DLQ_AUTO_RECOVERY_APPLY=0`）。
- 写入/读取恢复账本，但 dry-run 不会新增成功账本记录。

## 3. 常用参数

- `DLQ_AUTO_RECOVERY_BOOTSTRAP_SERVERS`：Kafka 地址，默认 `localhost:9092`
- `DLQ_AUTO_RECOVERY_INPUT_TOPIC`：输入 Topic，默认 `platform.dlq`
- `DLQ_AUTO_RECOVERY_CONSUME_OFFSET`：消费起点，默认 `beginning`
- `DLQ_AUTO_RECOVERY_MAX_EVENTS`：扫描条数上限，默认 `200`
- `DLQ_AUTO_RECOVERY_OUTPUT_MODE`：`source-topic` / `fixed-topic`
- `DLQ_AUTO_RECOVERY_FIXED_TOPIC`：固定回放 Topic（仅 `fixed-topic` 模式）
- `DLQ_AUTO_RECOVERY_KEY_FIELD`：回放 key 字段，默认 `sessionId`
- `DLQ_AUTO_RECOVERY_TENANT_FILTER`：逗号分隔租户过滤
- `DLQ_AUTO_RECOVERY_SOURCE_TOPIC_FILTER`：逗号分隔来源 Topic 过滤
- `DLQ_AUTO_RECOVERY_REASON_FILTER`：逗号分隔 reason 过滤
- `DLQ_AUTO_RECOVERY_SERVICE_FILTER`：逗号分隔 service 过滤
- `DLQ_AUTO_RECOVERY_APPLY`：`0` dry-run，`1` 执行回放
- `DLQ_AUTO_RECOVERY_RATE_LIMIT_PER_SEC`：回放速率限制（每秒）
- `DLQ_AUTO_RECOVERY_STATE_PATH`：恢复账本路径（JSONL）
- `DLQ_AUTO_RECOVERY_EVENT_FILE`：JSONL 输入（simulated/mock）

## 4. 执行示例

### 4.1 Dry-run（推荐先执行）

```bash
DLQ_AUTO_RECOVERY_BOOTSTRAP_SERVERS=localhost:9092 \
DLQ_AUTO_RECOVERY_TENANT_FILTER=tenant-a \
DLQ_AUTO_RECOVERY_SOURCE_TOPIC_FILTER=translation.result \
tools/platform-dlq-auto-recovery.sh
```

### 4.2 Apply（执行自动恢复）

```bash
DLQ_AUTO_RECOVERY_BOOTSTRAP_SERVERS=localhost:9092 \
DLQ_AUTO_RECOVERY_APPLY=1 \
DLQ_AUTO_RECOVERY_REASON_FILTER=MAX_RETRIES_EXCEEDED \
DLQ_AUTO_RECOVERY_RATE_LIMIT_PER_SEC=20 \
tools/platform-dlq-auto-recovery.sh
```

### 4.3 Simulated 模式（无 Kafka 消费）

```bash
DLQ_AUTO_RECOVERY_EVENT_FILE=build/reports/platform-dlq-auto-recovery/mock-platform-dlq.jsonl \
DLQ_AUTO_RECOVERY_APPLY=0 \
tools/platform-dlq-auto-recovery.sh
```

## 5. 产物

默认输出目录：`build/reports/platform-dlq-auto-recovery`

- `platform-dlq-auto-recovery.json`：结构化总报告
- `platform-dlq-auto-recovery-summary.md`：摘要
- `platform-dlq-auto-recovery-candidates.jsonl`：候选 `platform.dlq` 事件
- `platform-dlq-auto-recovery-selected.jsonl`：筛选后的恢复记录
- `platform-dlq-auto-recovery-failed.jsonl`：失败/无效明细
- `replay/platform-dlq-replay.json`：子流程 replay 报告
- `replay/platform-dlq-replay-summary.md`：子流程 replay 摘要

默认恢复账本：

- `build/state/platform-dlq-auto-recovery-success.jsonl`

## 6. 通过判定

- `overallPass=true`
- `parseError=0` 且 `invalid=0`
- apply 模式下 `replayFailure=0`

若失败：

1. 检查 `platform-dlq-auto-recovery-failed.jsonl` 的 `stage` 与错误明细。
2. 先缩小租户/服务/来源 Topic 范围，再重试。
3. 降低 `DLQ_AUTO_RECOVERY_RATE_LIMIT_PER_SEC` 后再执行 apply。

## 7. 风险控制

- 默认 dry-run，避免误回放。
- 通过恢复账本避免重复恢复同一 `eventId`。
- apply 前建议先做一轮 dry-run 并确认下游幂等行为可用。
