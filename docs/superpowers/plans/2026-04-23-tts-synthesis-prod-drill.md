# TTS Synthesis Production Drill Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补齐 `tts-orchestrator` 在 HTTP synthesis 模式下的生产联调基线（健康探测、并发保护、错误语义、指标），并打通消费者重试/DLQ 分类。

**Scope:**
- `services/tts-orchestrator/*`
- `docs/contracts.md`
- `docs/services.md`
- `docs/implementation-status.md`
- `docs/roadmap.md`

---

### Task 1: TTS synthesis 引擎生产加固

**Files:**
- `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/pipeline/TtsSynthesisException.java` (new)
- `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/pipeline/TtsSynthesisProperties.java`
- `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/pipeline/HttpTtsSynthesisEngine.java`
- `services/tts-orchestrator/src/main/resources/application.yml`

- [x] 新增 `TtsSynthesisException(errorCode, retryable)`，统一错误语义出口。
- [x] 扩展 `tts.synthesis.http` 配置：`maxConcurrentRequests` + `health`（`enabled/path/timeout/cacheTtl/failOpenOnError`）。
- [x] 在 HTTP synthesis 引擎加入并发信号量保护、健康探测缓存、请求延迟/结果指标，以及 provider/network/timeout 的错误码映射。

### Task 2: 消费侧重试/DLQ 语义对齐 + 测试

**Files:**
- `services/tts-orchestrator/src/main/java/com/kafkaasr/tts/kafka/TranslationResultConsumer.java`
- `services/tts-orchestrator/src/test/java/com/kafkaasr/tts/pipeline/HttpTtsSynthesisEngineTests.java`
- `services/tts-orchestrator/src/test/java/com/kafkaasr/tts/kafka/TranslationResultConsumerTests.java`

- [x] 消费侧改为基于 `TtsSynthesisException` 判断 `errorCode/retryable`，避免统一 `PIPELINE_FAILURE`。
- [x] 补充单测：健康 fail-fast、timeout 映射、并发拒绝、消费侧 retryable 分类。
- [x] 跑通 `./gradlew :services:tts-orchestrator:test`。

### Task 3: 文档同步与仓库校验

**Files:**
- `services/tts-orchestrator/README.md`
- `docs/contracts.md`
- `docs/services.md`
- `docs/implementation-status.md`
- `docs/roadmap.md`
- `docs/superpowers/plans/2026-04-23-tts-synthesis-prod-drill.md`

- [x] 文档补充 TTS synthesis 生产联调能力和可配置项，更新“已实现/未实现”边界。
- [x] 计划勾选收口（全部任务完成后打勾）。
- [x] 跑通 `bash tools/verify.sh`。
