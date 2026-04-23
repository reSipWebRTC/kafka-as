# Plan: TTS CDN Governance

## Goal

在现有 `tts.ready.playbackUrl` 产出链路上补齐分发治理能力：

1. CDN 区域路由（按租户映射到区域 CDN）
2. 区域路由缺失时回源回退（origin URL）
3. 缓存命中优化（可配置 cache scope + shard 路径）

## Tasks

### Task 1: 配置与实现

- [x] 扩展 `TtsStorageProperties`（区域路由、回源回退、cache scope/shard）
- [x] 扩展 `S3TtsObjectStorageUploader` URL 选择逻辑（regional CDN -> fallback -> origin）
- [x] 扩展 object key 生成逻辑（tenant/global scope、可选 shard）
- [x] 更新 `services/tts-orchestrator/src/main/resources/application.yml` 配置项

### Task 2: 测试

- [x] 扩展 `S3TtsObjectStorageUploaderTests` 覆盖区域路由
- [x] 扩展 `S3TtsObjectStorageUploaderTests` 覆盖回源回退
- [x] 扩展 `S3TtsObjectStorageUploaderTests` 覆盖 global scope + shard key

### Task 3: 文档

- [x] 更新 `docs/implementation-status.md`
- [x] 更新 `docs/services.md`
- [x] 更新 `docs/contracts.md` 的实现注记（`tts.ready.playbackUrl` 路由语义）

## Verification

- [x] `./gradlew :services:tts-orchestrator:test --no-daemon`
- [x] `tools/verify.sh`
