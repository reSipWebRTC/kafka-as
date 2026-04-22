# HTML Source Notes

`docs/html/` 下的文件是仓库最早的一批原始长文资料。

这些 HTML 文件现在的定位只有两个：

- 作为设计背景和历史输入
- 为 `docs/*.md` 提供可追溯的来源

它们不是当前工程的主维护对象。日常更新请优先修改 `docs/` 和 `api/`。

如果 HTML 与当前 Markdown / 契约 / 已实现代码冲突，优先级如下：

1. `docs/contracts.md` 与 `api/`
2. `docs/architecture.md`、`docs/services.md`、`docs/event-model.md`
3. `docs/implementation-status.md`
4. `services/*` 中已有测试覆盖的实现
5. `docs/html/*.html`

## 主题映射

| HTML 原文 | 主题 | 当前主文档 |
| --- | --- | --- |
| `FunASR 流式语音识别生产级实战：从单机推理到百万并发的架构演进之路.html` | 流式 ASR、chunk、推理链路 | `architecture.md` `services.md` `event-model.md` `roadmap.md` |
| `TTS 缓存、回放与音频分发体系：从可用 Demo 到生产级高并发架构全解.html` | TTS 缓存、对象存储、CDN、回放 | `architecture.md` `services.md` `observability.md` `roadmap.md` |
| `百万级长连接音频网关：Java WebFlux 在分布式系统中的工程化实践.html` | WebFlux 长连接网关、背压、接入层边界 | `architecture.md` `contracts.md` `services.md` |
| `从零到百万并发：基于 FunASR + Kafka + K8s 的实时语音翻译机器人架构全解.html` | 总体架构、组件拆分、事件流 | `architecture.md` `services.md` `roadmap.md` |
| `从零到生产级：构建高可用的 Spring AI 实时语音翻译机器人.html` | 领域建模、状态机、工程结构、部署治理 | `architecture.md` `services.md` `roadmap.md` |
| `实时语音翻译系统的可观测性与压测方法论.html` | SLI/SLO、指标、压测、告警 | `observability.md` `roadmap.md` |
| `亿级实时语音事件流：基于 Kafka 的分布式架构设计与实践.html` | Topic、分区、顺序、重试、消费模型 | `event-model.md` `contracts.md` `architecture.md` |

## 使用规则

- 不要把 HTML 内容逐段搬运到 Markdown。
- 先抽取设计结论，再落到 `docs/*.md` 或 `api/`。
- 对外行为变更必须先更新 `docs/contracts.md` 与 `api/`，不能只改参考文章。
