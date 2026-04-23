# Roadmap

## 1. 当前阶段判断

仓库当前不再处于“只有设计，没有代码”的阶段。

截至 `2026-04-22`，已经完成：

- 架构、服务边界、事件契约和协议文档基线
- `api/protobuf` 与 `api/json-schema` 契约文件
- 6 个核心服务加 `control-plane` 的工程骨架
- 从 `audio.ingress.raw` 到 `tts.request` 的主链路（默认占位实现 + provider 适配入口，ASR 含 FunASR mode，Translation 含 OpenAI mode，TTS 含 synthesis mode）
- Redis 会话状态和租户策略的最小落地
- 统一的管理端点、Tracing 和第一版服务指标
- `deploy/monitoring` 下 Prometheus/Grafana/Alertmanager 看板、告警与通知路由基线

当前真正的状态是：

- `Phase 0` 基本完成
- `Phase 1` 已经启动并跑通了“骨架链路”
- 距离“生产可运行闭环”还差真实引擎、治理能力和压测证据

## 2. Phase 0：文档与契约冻结

目标：

- 完成总体架构和服务边界设计
- 冻结事件模型和 Topic 规划
- 冻结会话状态机
- 冻结外部协议

当前完成情况：

- 已完成 `docs/` 主文档基线
- 已完成 `docs/html` 到 Markdown 的映射和维护边界划分
- 已完成 `docs/contracts.md`
- 已完成 `api/protobuf/realtime_speech.proto`
- 已完成 `api/json-schema/*.v1.json`

后续只保留两项持续动作：

- 外部行为变化时同步更新 `docs/contracts.md` 与 `api/`
- 防止“未来能力”和“当前实现”再次混写

## 3. Phase 1：最小可运行链路

目标：

- 跑通实时字幕闭环

当前已落地范围：

- `speech-gateway`
- `session-orchestrator`
- `Kafka`
- `asr-worker`
- `translation-worker`
- `Redis`
- 占位态的 `tts-orchestrator`
- 占位态的 `control-plane`

当前已具备：

- WebSocket 音频上行
- WebSocket `session.ping`
- Kafka 驱动的 `subtitle.partial` / `subtitle.final` / `session.closed` 下行
- 下行链路仓库内 E2E smoke 基线（顺序、终态、重复与异常计数）
- 会话 start/stop 控制
- `audio.ingress.raw -> asr.partial / asr.final -> translation.result -> tts.request / tts.chunk / tts.ready`
- 基础管理端点和服务指标

当前仍缺：

- 面向浏览器/SDK 的明确端到端 demo 与用户可见闭环
- 压测窗口下的延迟和稳定性证据

退出标准保持不变，但当前还未达成：

- 能稳定完成“音频上行 -> ASR -> 翻译 -> 字幕下发”
- WebSocket 连接成功率 `>= 99.9%`
- 实时字幕端到端延迟 `P95 <= 1500ms`
- 关键 Topic lag 可回落
- DLQ 比例 `< 0.1%`

## 4. Phase 2：生产治理补齐

目标：

- 从“能跑”升级到“能扛”

范围：

- 幂等
- 重试
- DLQ
- 限流
- 熔断
- 背压
- 灰度开关

当前判断：

- 文档已经冻结方向
- 代码基线已落地第一版重试、DLQ、限流、背压
- 代码基线已落地第一版幂等判重、补偿信号、控制面熔断与灰度字段
- 下一步是把治理从“固定策略”升级到“自适应策略 + 补偿编排 + 动态灰度路由”

## 5. Phase 3：TTS 与分发体系

目标：

- 支持语音回放和大规模下发

范围：

- 真实 `tts-orchestrator`
- TTS 引擎接入
- 文本归一化
- 缓存键策略
- 对象存储
- CDN 分发

当前判断：

- `tts-orchestrator` 模块已存在
- 已具备 `translation.result -> tts.request / tts.chunk / tts.ready` 编排与 HTTP synthesis 适配入口
- 已补齐 `tts.ready` 的可配置 S3/MinIO 上传与真实 `playbackUrl` 回填基线
- 已补齐 `tts.ready` 的 `cache-control` 与 `expires/sig` 签名 URL 策略基线
- 下一步是完成 TTS 引擎生产联调与 CDN 回放分发深度治理（区域路由、回源、命中率优化）

## 6. Phase 4：控制面与多租户

目标：

- 把系统做成平台，而不是单业务项目

范围：

- `control-plane`
- 租户管理
- 配额管理
- 模型版本管理
- 语言对策略
- 动态路由与灰度

当前判断：

- `control-plane` 模块已存在
- 当前已覆盖租户策略 GET / PUT、版本化 upsert 以及灰度/回退策略字段

## 7. Phase 5：容量、压测与弹性

目标：

- 验证平台的上限和弹性能力

范围：

- 长连接压测
- Kafka 容量规划
- GPU 队列调优
- K8s HPA / KEDA
- 故障演练

当前判断：

- 观测基线和仓库级看板/告警资产已接入
- 告警通知路由和值班升级 runbook 基线已落地
- 压测、告警、恢复与弹性实战证据仍未建立

## 8. 当前建议优先级

如果资源有限，建议优先顺序如下：

1. 补齐下行链路的稳定性与端到端验证
2. 补齐幂等、补偿、熔断与灰度治理
3. 完成 ASR FunASR、Translation OpenAI 与 TTS synthesis 生产联调
4. 用预发/生产流量持续标定告警阈值，并将通知路由接入真实值班系统
5. 落地压测与故障演练
6. 补齐 TTS 分发与控制面高级能力

## 9. 主要风险

### 风险一：契约继续落后于实现

后果：

- 文档和代码再次出现双重事实来源
- 后续服务只能通过读代码猜协议

### 风险二：网关重新承载过多业务逻辑

后果：

- 长连接服务不可扩展
- 实例重启影响面过大

### 风险三：只看单测，不做压测和故障演练

后果：

- 线上瓶颈定位成本极高
- 无法判断扩容是否真的有效

### 风险四：把 TTS 当成简单模型调用

后果：

- 高并发下成本失控
- 重复合成与缓存击穿严重

## 10. 仓库结构演进

仓库已经完成了从“原始资料目录”到“工程骨架仓库”的第一步。

下一步建议补齐：

1. `deploy/` 下的 k8s / monitoring 资产
2. `tools/` 下的 load-test / local-dev 入口
3. `shared/` 下的公共契约模型和测试夹具
4. 字幕下行与 TTS 分发所需的端到端集成样例

## 11. HTML 原文处理策略

- 保留 `docs/html/*.html` 作为参考资料
- 新增或修订内容优先写入 `docs/*.md`
- 不再把 HTML 当作主维护文档
