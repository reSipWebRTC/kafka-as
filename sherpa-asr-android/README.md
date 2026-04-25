# SherpaAsrApp (Android WS Command Flow)

Android 客户端已切换为 **网关 WebSocket 命令流** 主链路：

- 上行：`session.start` / `audio.frame` / `session.stop` / `command.confirm` / `playback.metric`
- 下行：`subtitle.partial` / `subtitle.final` / `command.result` / `tts.ready` / `session.error` / `session.closed`

本地 ASR 主链路已移除；本地 TTS 仅用于 `tts.ready` 失败或超时时的回退播报。

## 关键行为

1. 点击麦克风后，客户端建立会话并持续发送 `audio.frame`。
2. 服务端返回字幕与命令执行回执；当回执包含 `confirmToken` 时，界面展示“确认执行/拒绝执行”按钮，触发 `command.confirm`。
3. `tts.ready` 到达时优先远端播放；若未到达或播放失败，按配置延迟回退到本地 TTS。
4. 远端播放启动/卡顿/完成/本地回退会通过 `playback.metric` 回传到网关指标。

## 配置项（设置页）

- `serverUrl`：`speech-gateway` HTTP 地址（会自动推导 WS 地址）
- `tenantId` / `userId` / `sourceLang` / `targetLang`
- `accessToken`（可选，网关开启鉴权时使用）
- `TTS 回退等待(ms)`

默认开发配置：

- `serverUrl = http://127.0.0.1:8080`
- `tenantId = tenant-home`
- `userId = android-user-1`

## 构建

```bash
./gradlew assembleDebug
```

## 仓库约束

为控制仓库体积，本目录不提交以下内容：

- `app/src/main/assets/**`（模型）
- `app/src/main/jniLibs/**`（`.so`）
- `app/libs/**`（本地二进制依赖）
- 构建缓存和 IDE 文件
