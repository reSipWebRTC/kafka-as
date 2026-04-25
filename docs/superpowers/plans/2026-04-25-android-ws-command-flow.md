# PR5 Plan: Android WS Command Flow

## Goal

Implement Android client path for SMART_HOME command interaction through `speech-gateway` WebSocket:

- remove local ASR primary path
- use WS uplink (`session.start` / `audio.frame` / `session.stop` / `command.confirm`)
- consume WS downlink (`subtitle.partial` / `subtitle.final` / `command.result` / `tts.ready` / `session.error` / `session.closed`)
- keep `tts.ready` playback as priority and fallback to local TTS

## Scope

- Add tracked Android project directory `sherpa-asr-android/` (source and build scripts only; no model binaries / `.so` / build caches).
- Add WS protocol models and client in Android project.
- Refactor `AsrViewModel` to WS-first flow and command confirm handling.
- Update Compose UI for command confirmation and command result feedback.
- Update settings/config for gateway connection inputs.
- Add/adjust tests for protocol decode and command-confirm flow.

## Out of Scope

- Real cloud deployment and IAM integration for Android.
- Shipping model artifacts in repository.
- Reworking backend contracts (already frozen by PR1/PR2/PR3/PR4).

## Tasks

1. Project import hygiene
   - [ ] add Android `.gitignore` rules to keep repository lightweight
   - [ ] ensure project can be checked in without assets/jni/build outputs
2. WS protocol and client
   - [ ] add uplink/downlink message models aligned with `docs/contracts.md`
   - [ ] implement reconnect-safe WS client with send helpers
3. ViewModel runtime flow
   - [ ] remove local ASR routing (`AudioPipeline`/`ResultRouter` main path)
   - [ ] stream mic frames directly to WS
   - [ ] handle `command.result` + `command.confirm` interaction state
   - [ ] implement `tts.ready` preferred playback and fallback local TTS
4. UI and settings
   - [ ] add command confirmation controls (accept/reject)
   - [ ] show command execution result state
   - [ ] add gateway config inputs (`wsUrl` / `tenantId` / `userId` / token)
5. Verification
   - [ ] Android unit tests for message handling and confirm flow
   - [ ] `tools/verify.sh` full repo check

## Risks

- Missing real backend in local test can hide timing issues for `tts.ready` vs fallback.
- WS disconnection handling may cause duplicated `session.start` if not guarded.
- Legacy local-only classes remain in project; must ensure they are no longer on main path.
