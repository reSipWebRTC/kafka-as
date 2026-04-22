# Architecture

## 1. 目标与边界

本方案面向以下场景：

- 实时语音识别
- 实时语音翻译
- 实时字幕下发
- 流式 TTS 合成与回放
- 大规模长连接会话管理

本方案不追求：

- 所有链路都做全局强一致
- 由单一服务同时承担接入、编排、推理和分发
- 直接把 Demo 推理服务暴露给业务侧

## 2. 架构原则

- 事件驱动优先，避免同步级联调用放大延迟与故障
- 会话内有序优先，所有事件围绕 `sessionId` 和 `seq` 设计
- 数据面与控制面分离
- 状态外置，网关实例可水平扩容与迁移
- 推理服务、翻译服务、TTS 服务独立伸缩
- 可观测性默认内建，而不是上线前临时补充

## 3. 当前仓库实现基线（2026-04-22）

当前仓库已经落地的实际模块链路如下：

```mermaid
flowchart LR
    C["Client"] --> G["speech-gateway<br/>/ws/audio"]
    G -. "start / stop" .-> O["session-orchestrator"]
    O -. "tenant policy" .-> CP["control-plane"]
    O --> R["Redis"]
    G --> K["Kafka"]
    O --> K
    K --> A["asr-worker"]
    A --> K
    K --> T["translation-worker"]
    T --> K
    K --> TS["tts-orchestrator"]
    TS --> K
```

当前已经实现：

- `speech-gateway` 直写 `audio.ingress.raw`
- `speech-gateway -> session-orchestrator` 的低频会话控制
- `speech-gateway` 的 `session.ping` 处理
- `speech-gateway` 基于 Kafka 的 `subtitle.partial` / `subtitle.final` / `session.closed` 下行回推
- `session-orchestrator -> control-plane` 的租户策略查询
- `session-orchestrator` 的 Redis 会话状态与 `session.control` 发布
- `asr-worker -> translation-worker -> tts-orchestrator` 的占位事件链路

当前尚未实现：

- TTS 引擎、对象存储、CDN
- 生产级限流、背压、DLQ、补偿

## 4. 目标总体分层

```mermaid
flowchart TB
    subgraph DataPlane["Data Plane"]
        C["Client / SDK"]
        G["Speech Gateway"]
        O["Session Orchestrator"]
        K["Kafka"]
        A["ASR Worker"]
        T["Translation Worker"]
        TS["TTS Orchestrator"]
        TE["TTS Engine"]
        ST["Object Storage / CDN"]
    end

    subgraph StateInfra["State / Infra"]
        R["Redis"]
        DB["Config / Metadata DB"]
    end

    subgraph ControlPlane["Control Plane"]
        CP["Tenant / Policy / Routing / Config / Release"]
    end

    subgraph Ops["Observability & Ops"]
        M["Metrics / Logs / Traces / Alerts"]
    end

    C --> G
    G --> K
    G -. "control api" .-> O
    O --> K
    O --> G
    K --> O
    K --> A
    A --> K
    K --> T
    T --> K
    K --> TS --> TE
    TS --> ST

    G --> R
    O --> R
    O --> DB
    CP --> G
    CP --> O
    CP --> A
    CP --> T
    CP --> TS

    G --> M
    O --> M
    A --> M
    T --> M
    TS --> M
```

## 4.1 主数据路径约束（Phase 0 冻结）

- 高频音频主链路固定为 `client -> speech-gateway -> Kafka`
- `session-orchestrator` 只消费/发布事件做编排，不中转高频音频帧
- `speech-gateway <-> session-orchestrator` 只承载低频控制交互

## 5. 关键组件职责

### Speech Gateway

目标职责：

- WebSocket / HTTP 接入
- 鉴权、限流、协议校验
- 高频音频直写 Kafka
- 字幕 / 错误 / 关闭消息回推

当前基线：

- 已实现 `/ws/audio`、`session.ping`、音频直写 Kafka、start/stop 调用 orchestrator、错误下行、基于 Kafka 的字幕与会话关闭回推
- 未实现鉴权、限流、背压和更细粒度下行编排

### Session Orchestrator

目标职责：

- 会话状态机
- 生命周期编排
- 幂等、重试、超时、降级
- 汇聚 ASR / Translation / TTS 结果

当前基线：

- 已实现 start/stop、Redis 状态、租户策略校验、`session.control`
- 未实现结果聚合、timeout scheduler 和补偿流

### Kafka Event Bus

目标职责：

- 承接核心异步事件
- 保证会话维度顺序
- 提供缓冲、削峰和可重放能力

当前基线：

- 已承接 `audio.ingress.raw`、`session.control`、`asr.partial`、`asr.final`、`translation.result`、`tts.request`
- 暂未落地 DLQ、重放流程和 Lag 治理文档化闭环

### ASR Worker

目标职责：

- 流式 ASR 推理
- 管理模型上下文
- 产出 partial / final 结果

当前基线：

- 已实现 placeholder `audio.ingress.raw -> asr.partial / asr.final`
- 未实现 FunASR、VAD 分段

### Translation Worker

目标职责：

- 文本翻译、术语替换、上下文增强
- 下发字幕结果和 TTS 请求

当前基线：

- 已实现 placeholder `asr.final -> translation.result`
- 未实现真实模型、术语增强和字幕回推

### TTS Orchestrator

目标职责：

- 文本归一化、缓存键生成、重复请求合并
- TTS 引擎调度
- 分片或回放地址输出

当前基线：

- 已实现 `translation.result -> tts.request`
- 未实现真实引擎、对象存储、CDN 和回放分发

### Control Plane

目标职责：

- 租户、语言对、模型版本、配额管理
- 路由、灰度和熔断配置

当前基线：

- 已实现租户策略 GET / PUT 与 Redis 存储
- 未实现 auth、数据库与动态策略分发

## 6. 数据面与控制面分离

### 数据面

数据面承载高频实时链路：

- 客户端连接
- 音频帧流
- 识别结果流
- 翻译结果流
- TTS 音频流

要求：

- 低延迟
- 可扩缩
- 尽量无状态
- 容错后可快速恢复

### 控制面

控制面承载低频但高价值的治理能力：

- 配置中心
- 模型版本管理
- 租户策略
- 路由规则
- 灰度与熔断开关
- 运营审计

要求：

- 配置变更可追踪
- 政策发布可回滚
- 变更影响范围可控

## 7. 关键架构决策

### 7.1 为什么不是同步串联调用

如果网关直接同步调用 ASR、翻译、TTS，会出现：

- 上游延迟直接叠加
- 某个服务抖动时整条链路阻塞
- 子链路无法独立扩缩容
- 重试语义混乱

因此主链路统一走事件驱动，只保留少量低频同步控制接口。

### 7.2 为什么状态必须外置

如果会话状态只在网关内存中，实例漂移、重启或扩容时会导致：

- 会话丢失
- 有序性失效
- 重复消费与重发难以判断

因此关键状态必须外置到 `Redis + 持久化元数据存储`。

### 7.3 为什么单独拆分 TTS 编排层

TTS 在生产里关心的不只是“合成”：

- 缓存命中率
- 重复请求合并
- 回放与分发
- 对象存储回源
- CDN 边缘命中

所以 `tts-orchestrator` 必须是一个独立编排层，而不是直接暴露模型调用。

## 8. 容量与弹性建议

- Gateway 按连接数、出入带宽、CPU、事件循环延迟扩缩容
- ASR 按 GPU 利用率、推理队列长度、单路时延扩缩容
- Translation Worker 按吞吐、延迟、第三方模型配额扩缩容
- TTS 按缓存未命中率、引擎队列、分发带宽扩缩容
- Kafka 按分区利用率、消费延迟、磁盘与网络利用率规划容量

## 9. 当前落地路径判断

面向生产的第一条闭环仍应以：

- `speech-gateway`
- `session-orchestrator`
- `Kafka`
- `asr-worker`
- `translation-worker`
- `Redis`

作为 Phase 1 的强约束基线。

仓库目前已经额外包含：

- `tts-orchestrator`
- `control-plane`

但这两个模块当前更多是“已落地骨架”，而不是“生产化完成”。
