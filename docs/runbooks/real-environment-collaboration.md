# Real Environment Collaboration Runbook

## 1. 目标

把“需要真实环境时怎么配合”固定成一套可执行方式，避免每次推进以下事项时都重新对齐：

1. 真实 ASR / Translation / TTS 引擎联调与容量演练。
2. `control-plane` 对接外部 IAM / RBAC。
3. 预发 / 生产级 loadtest、fault-drill、Alertmanager 与恢复证据收口。

## 2. 协作原则

默认顺序固定如下：

1. 先在仓库内完成本地 / simulated 验证。
2. 再进入真实环境联调。
3. 真实凭据只放在非版本化位置，例如 `.secrets/`、CI secret 或外部密钥系统。
4. 仓库里只保存模板、命令、报告格式和去敏后的证据。

如果真实环境暂时没有到位，继续走 simulated 路径，不阻塞接口、脚本和 runbook 的建设。

## 3. 责任边界

我负责：

1. 把仓库侧能力补齐到“可执行”状态：
   - 环境变量映射
   - `.env` 模板
   - 预检 / 演练 / 收口命令
   - 报告格式与通过标准
2. 根据你提供的真实参数，输出可以直接执行的命令或模板。
3. 解读执行结果，判断是否通过，并给出缺口和回退建议。

你负责：

1. 提供真实环境地址、网络可达性和执行窗口。
2. 提供真实凭据、测试租户、测试 token、样本数据和配额限制。
3. 在有权限的环境执行命令，或把执行结果 / 日志 / 报告回传给我分析。
4. 在涉及预发 / 生产时提供回退责任人和值班链路。

## 4. 场景一：真实引擎生产闭环

对应 `docs/roadmap.md` 的 Phase 1。

### 4.1 你需要提供什么

#### ASR（FunASR 或兼容 HTTP ASR）

至少提供一组可用配置：

- 启用模式：
  - `ASR_INFERENCE_MODE=funasr`
  - 或 `ASR_INFERENCE_MODE=http`
- 地址与认证：
  - `ASR_FUNASR_ENDPOINT` / `ASR_INFERENCE_HTTP_ENDPOINT`
  - `ASR_FUNASR_AUTH_TOKEN` / `ASR_INFERENCE_HTTP_AUTH_TOKEN`
- 模型与协议：
  - `ASR_FUNASR_MODEL`
  - `ASR_FUNASR_LANGUAGE`
  - `ASR_FUNASR_PATH`
  - `ASR_FUNASR_AUDIO_FORMAT`
- 运行约束：
  - 提供方限流 / 并发上限
  - 是否允许开启 health 探测
  - 预期错误码或节流返回样式

#### Translation（OpenAI 或兼容 HTTP Translation）

至少提供一组可用配置：

- 启用模式：
  - `TRANSLATION_ENGINE_MODE=openai`
  - 或 `TRANSLATION_ENGINE_MODE=http`
- 地址与认证：
  - `TRANSLATION_ENGINE_OPENAI_ENDPOINT` / `TRANSLATION_ENGINE_HTTP_ENDPOINT`
  - `TRANSLATION_ENGINE_OPENAI_API_KEY` / `TRANSLATION_ENGINE_HTTP_AUTH_TOKEN`
- 模型与限制：
  - `TRANSLATION_ENGINE_OPENAI_MODEL`
  - `TRANSLATION_ENGINE_OPENAI_MAX_TOKENS`
  - 提供方 RPM / TPM / 并发限制
- 测试集：
  - 目标租户
  - 语言对
  - 代表性样本文本

#### TTS（真实 synthesis + 存储 / CDN）

至少提供：

- 启用模式：
  - `TTS_SYNTHESIS_MODE=http`
- synthesis 地址与认证：
  - `TTS_SYNTHESIS_HTTP_ENDPOINT`
  - `TTS_SYNTHESIS_HTTP_AUTH_TOKEN`
- 语音与并发：
  - 默认 voice 或租户 voice 规则
  - `TTS_SYNTHESIS_HTTP_MAX_CONCURRENT_REQUESTS`
- 如果要验证分发闭环，还要提供：
  - `TTS_STORAGE_ENABLED=true`
  - `TTS_STORAGE_PROVIDER`
  - `TTS_STORAGE_BUCKET`
  - `TTS_STORAGE_ENDPOINT`
  - `TTS_STORAGE_ACCESS_KEY`
  - `TTS_STORAGE_SECRET_KEY`
  - `TTS_STORAGE_PUBLIC_BASE_URL`
  - `TTS_STORAGE_CDN_REGION_BASE_URLS`（如启用 CDN 区域路由）

#### 通用补充信息

- 测试租户 ID
- 目标语言对
- 允许演练的时间窗口
- 失败后的回退联系人
- 是否允许压测、故障注入、降级或限流演练

### 4.2 我会怎么配合

1. 根据你提供的真实参数生成一份去敏模板，建议放到：

```bash
.secrets/real-engine.preprod.env
```

2. 给出本轮要执行的命令组合，通常包括：

```bash
tools/fault-drill-closure.sh
tools/preprod-drill-closure.sh
```

3. 如果需要，我会先把单服务启动参数整理成按服务导出的 `export` 列表。
4. 你执行后，把以下产物给我，我来判断是否通过：
   - `build/reports/fault-drill/fault-drill-closure.json`
   - `build/reports/preprod-drill/preprod-drill-closure.json`
   - 相关服务日志

## 5. 场景二：控制面外部 IAM / RBAC

对应 `docs/roadmap.md` 的 Phase 3。

这部分已有专门 runbook：

- [control-plane-iam-provider-integration.md](control-plane-iam-provider-integration.md)

### 5.1 你需要提供什么

必须项：

1. `CONTROL_AUTH_EXTERNAL_ISSUER`
2. `CONTROL_AUTH_EXTERNAL_AUDIENCE`
3. `CONTROL_AUTH_EXTERNAL_JWKS_URI`
4. permission claim 名和 tenant claim 名
5. 读权限名和写权限名
6. 至少两类 token：
   - 只读 token
   - 读写 token
7. 可选：
   - 跨租户验证 token
   - `hybrid` 模式的 static fallback token

联调地址：

1. `CONTROL_AUTH_DRILL_BASE_URL`
2. `CONTROL_AUTH_DRILL_TENANT_ID`
3. 可选 `CONTROL_AUTH_DRILL_CROSS_TENANT_ID`

### 5.2 我会怎么配合

1. 先让你复制模板：

```bash
cp deploy/env/control-plane-iam.env.template .secrets/control-plane-iam.env
```

2. 再给你预检和演练命令：

```bash
tools/control-plane-iam-precheck.sh --env-file .secrets/control-plane-iam.env
tools/control-plane-auth-drill.sh
tools/preprod-drill-closure.sh
```

3. 如果真实 IAM 还没完全就绪，我会先安排：
   - `tools/control-plane-jwks-jwt-drill.sh`
   - `tools/control-plane-auth-failure-drill.sh`
4. 你把报告产物给我，我来判断是否满足切到 `hybrid` 或 `external-iam`。

## 6. 场景三：预发 / 生产收口与告警运营化

对应 `docs/roadmap.md` 的 Phase 5。

### 6.1 你需要提供什么

预发 / 生产环境基础信息：

1. `PREPROD_TARGET`
2. `PREPROD_ALERTMANAGER_URL`
3. 实际 loadtest 命令：
   - `PREPROD_LOADTEST_COMMAND`
4. 实际 fault-drill 命令：
   - `PREPROD_FAULT_DRILL_COMMAND`
5. 如果控制面鉴权要纳入收口：
   - `PREPROD_AUTH_DRILL_REQUIRED=1`
   - `PREPROD_AUTH_DRILL_COMMAND`

如果需要校验通知链路或本地 monitoring 资产，还需要：

1. Alertmanager webhook 或值班路由信息
2. Prometheus / Grafana / Alertmanager 的访问方式
3. 是否允许抓取演练前后告警快照

### 6.2 我会怎么配合

1. 我会把这轮预发命令整理成一段可直接执行的脚本化命令。
2. 默认收口入口是：

```bash
tools/preprod-drill-closure.sh
```

3. 你执行后，把以下产物给我：
   - `build/reports/preprod-drill/preprod-drill-closure.json`
   - `build/reports/preprod-drill/preprod-drill-closure-summary.md`
   - `build/reports/preprod-drill/alertmanager-*.json`（如果启用告警抓取）
4. 我会输出：
   - 是否达到 `overallPass=true`
   - 哪一项 evidence 缺失
   - 是否需要调阈值、补 drill 或收紧回退策略

## 7. 推荐协作消息模板

当你准备进入真实环境时，直接按下面格式把信息发给我最快：

```text
目标阶段：Phase 1 / Phase 3 / Phase 5
环境类型：preprod / prod-like / vendor sandbox
可达地址：
- ASR:
- Translation:
- TTS:
- Control-plane:
- Alertmanager:
凭据提供方式：.secrets / CI secret / 手工导出
测试租户：
语言对 / voice：
执行窗口：
是否允许压测 / 故障演练：
回退联系人：
```

## 8. 通过标准

这份 runbook 本身的目标不是要求你一次性给全量环境，而是把配合信息标准化。

进入真实环境任务时，至少满足以下条件才算“可开始执行”：

1. 目标阶段已经明确。
2. 至少一组真实地址和认证方式已提供。
3. 测试租户 / 样本 / 执行窗口已明确。
4. 我已经把对应命令、模板和产物路径整理出来。

## 9. 本地准真实收口基线（simulated）

最近一次本地准真实收口（`2026-04-26`）使用如下命令：

```bash
source tools/local-functional-test-env.sh
tools/loadtest-alert-closure.sh
tools/fault-drill-closure.sh
PREPROD_AUTH_DRILL_REQUIRED=1 \
PREPROD_AUTH_DRILL_COMMAND="tools/control-plane-jwks-jwt-drill.sh" \
tools/preprod-drill-closure.sh
```

说明：

1. 该基线是 simulated/mock 证据，不依赖外部 IAM 与真实 Alertmanager。
2. 默认使用 `PREPROD_SKIP_ALERT_CAPTURE=1`，恢复阶段标记为 `recoveryPass=true (skipped)`。
3. 验收报告路径固定在：
   - `build/reports/loadtest/gateway-pipeline-loadtest-aggregate.json`
   - `build/reports/fault-drill/fault-drill-closure.json`
   - `build/reports/preprod-drill/preprod-drill-closure.json`
