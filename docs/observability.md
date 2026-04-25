# Observability

## 1. 观测目标

实时语音系统的观测目标不是“知道服务活着”，而是回答下面的问题：

- 用户现在是否觉得实时
- 延迟是卡在网关、Kafka、ASR、翻译还是 TTS
- 某个租户或语言对是否异常
- 某次故障属于容量不足、依赖抖动还是模型退化

## 2. 建议的 SLI 分层

### 用户体验层

- 首字结果时间
- 最终字幕时间
- 端到端翻译延迟
- TTS 首包时间
- 播放中断率

### 服务层

- 网关连接成功率
- 每 Topic 消费延迟
- ASR 推理时延
- 翻译成功率
- TTS 缓存命中率
- DLQ 入队速率

### 资源层

- CPU / 内存
- 网卡吞吐
- 文件描述符
- Reactor 事件循环延迟
- Kafka Broker 磁盘和网络
- GPU 利用率、显存占用、推理队列长度

## 3. 推荐 SLO

下面的数值不是当前已达成结果，而是第一版目标：

| 指标 | 建议值 |
| --- | --- |
| 网关连接成功率 | `>= 99.9%` |
| 实时字幕端到端 P95 | `<= 1500ms` |
| ASR partial 产出间隔 P95 | `<= 500ms` |
| 翻译结果成功率 | `>= 99.5%` |
| TTS 缓存命中率 | `>= 70%` |
| DLQ 比例 | `< 0.1%` |

## 4. 指标设计

### 4.1 Gateway

- 活跃连接数
- 每秒接入音频帧数
- 入站/出站带宽
- 鉴权失败率
- 限流触发次数
- 事件循环阻塞时间

### 4.2 Kafka

- Producer 发送失败率
- 各 Topic TPS
- Consumer Lag
- 重平衡次数
- 单分区热点程度

### 4.3 ASR

- 单请求推理时长
- chunk 处理耗时
- partial / final 产出频率
- VAD 切分数量
- GPU 利用率
- 队列等待时长

### 4.4 Translation

- 单条文本翻译耗时
- 失败率
- 第三方模型限流或拒绝次数
- 回退策略触发次数

### 4.5 TTS

- 缓存命中率
- 请求合并命中率
- 实时分片产出间隔
- 对象存储写入延迟
- CDN 回源率

### 4.6 Control-Plane Auth

- 鉴权决策总量（allow/unauthorized/forbidden）
- 拒绝原因分布（token 缺失、租户越权、权限不足、外部 IAM 不可用）
- hybrid 模式回退触发次数（external -> static）
- 鉴权延迟

## 5. Trace 与日志要求

### Trace

目标态要求所有核心链路贯通：

- `gateway -> orchestrator -> kafka -> asr -> translation -> tts`

最少要统一透传：

- `traceId`
- `sessionId`
- `tenantId`
- `eventType`
- `seq`

### 日志

目标态日志要求：

- 使用结构化日志
- 默认 JSON 输出
- 严禁只打印字符串不带主键

当前基线说明：

- 6 个服务都已统一 `traceId` / `spanId` 关联字段
- 当前日志格式仍是文本 pattern，不是完整 JSON 结构化日志

## 6. 告警设计

优先对“用户可感知退化”告警，而不是只对资源告警。

建议先上线的告警：

- 网关连接成功率下降
- 关键 Topic lag 持续升高
- ASR 推理耗时显著偏离基线
- 翻译失败率升高
- TTS 缓存命中率断崖式下降
- DLQ 突增
- GPU 队列持续堆积

## 7. 压测方法

### 压测目标

- 找系统瓶颈
- 验证扩容是否有效
- 验证降级是否生效
- 验证告警能否及时触发

### 压测维度

- 连接数压测
- 音频帧吞吐压测
- 长时会话稳定性压测
- Kafka 积压恢复压测
- 单机故障与实例漂移压测
- 外部模型抖动场景压测

### 压测顺序

1. 单组件基准压测
2. 端到端闭环压测
3. 故障注入压测
4. 扩缩容联动压测

## 8. 最小实现建议

第一阶段建议至少接入：

- `Prometheus`
- `Grafana`
- `OpenTelemetry`
- 集中式日志系统

同时建立三张核心看板：

- 业务体验看板
- 服务健康看板
- 基础资源看板

## 9. 当前仓库观测基线（2026-04-25）

已在以下服务接入统一基线：

- `speech-gateway`
- `session-orchestrator`
- `asr-worker`
- `translation-worker`
- `tts-orchestrator`
- `control-plane`

### 已统一配置

- `management` 端点暴露：`health,info,metrics,prometheus`
- 健康探针：`management.endpoint.health.probes.enabled=true`
- 指标公共标签：`service`、`env`
- tracing 采样：`management.tracing.sampling.probability`
- OTLP 导出：`management.otlp.tracing.endpoint`
- 日志关联键：`traceId`、`spanId`

### 已落地指标（第一版）

- `gateway.ws.messages.total` / `gateway.ws.messages.duration`
- `gateway.client.perceived.total` / `gateway.client.perceived.duration` / `gateway.client.perceived.sessions.tracked`
- `gateway.client.playback.total` / `gateway.client.playback.duration` / `gateway.client.playback.stall.count`
- `orchestrator.session.start.total` / `orchestrator.session.start.duration`
- `orchestrator.session.stop.total` / `orchestrator.session.stop.duration`
- `asr.pipeline.messages.total` / `asr.pipeline.duration`
- `translation.pipeline.messages.total` / `translation.pipeline.duration`
- `tts.pipeline.messages.total` / `tts.pipeline.duration`
- `controlplane.tenant.policy.upsert.total` / `controlplane.tenant.policy.upsert.duration`
- `controlplane.tenant.policy.get.total` / `controlplane.tenant.policy.get.duration`
- `controlplane.auth.decision.total` / `controlplane.auth.decision.duration`
- `controlplane.auth.hybrid.fallback.total`

### 已落地监控资产（2026-04-25）

- `deploy/monitoring/docker-compose.yml`：本地 Prometheus + Grafana 启停
- `deploy/monitoring/prometheus/prometheus.yml`：六服务 `/actuator/prometheus` 抓取
- `deploy/monitoring/prometheus/alerts/kafka-asr-alerts.yml`：错误率、体验级 P95 延迟、客户端播放中断率、Kafka lag、控制面回退与鉴权告警
- `deploy/monitoring/grafana/dashboards/kafka-asr-overview.json`：主链路吞吐/错误/延迟 + downlink + lag + 客户端可感知时延看板
- `deploy/monitoring/alertmanager/alertmanager.yml`：default / warning / critical / escalation 路由基线
- `tools/monitoring-up.sh` / `tools/monitoring-down.sh`：一键启停入口
- `tools/alert-ops-validate.sh`：告警阈值顺序、分级规则覆盖和通知链路完整性校验

### 已落地压测与告警闭环基线（2026-04-24）

- `tools/loadtest-alert-closure.sh`：仓库内多场景（smoke / baseline / stress）压测聚合入口
- `tools/fault-drill-closure.sh`：ASR / Translation / TTS 故障演练收口入口
- `tools/preprod-drill-closure.sh`：预发环境 loadtest / fault-drill / recovery 证据聚合入口
- `build/reports/loadtest/gateway-pipeline-loadtest-aggregate.json`：多场景结构化压测结果
- `build/reports/fault-drill/fault-drill-closure.json`：结构化故障演练结果
- `build/reports/preprod-drill/preprod-drill-closure.json`：结构化预发收口结果
- `docs/runbooks/loadtest-alert-closure.md`：执行频率、门槛和告警升级路径
- `docs/runbooks/control-plane-iam-provider-integration.md`：控制面鉴权预检、演练与回退 runbook
- `deploy/monitoring/prometheus/alerts/kafka-asr-alerts.yml`：按仓库内基线收紧的第一版阈值

仓库内基线结果摘要：

- `loadtest-alert-closure` 已支持 `capacityEvidence`
- `fault-drill-closure` 已支持多场景聚合 `overallPass`
- `preprod-drill-closure` 已支持统一 `sloEvidence`（loadtest / fault / recovery）

说明：

- 上述仓库内收口结果主要来自本地 harness、单测与 simulated/mock 预发演练，不等于生产流量表现。
- 当前告警阈值与闭环脚本已完成“仓库内基线校准”，上线前仍需按预发/生产真实流量再标定。

### 当前仍缺失

- 客户端端侧渲染卡顿细粒度指标（UI 线程/渲染帧级）
- 真实预发/生产环境下的告警阈值再标定与通知链路运营化
- 结构化 JSON 日志
- 真实引擎链路（Kafka + 外部 ASR/翻译/TTS）的压测报告与 SLO 达成证据
- 真实依赖环境下的故障注入、恢复时间和弹性扩缩容证据
