# Docs / Implementation Sync Update Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refresh the Markdown source-of-truth so it accurately reflects the current multi-service implementation, while still preserving the original `docs/html` articles as historical/reference inputs and keeping frozen contracts separate from not-yet-implemented production features.

**Architecture:** Treat `docs/html/*.html` as source material, `docs/*.md` and `api/` as active engineering truth, and service code/tests as the implementation baseline. The update must explicitly separate three layers: historical article guidance, current implemented behavior, and future target capabilities.

**Tech Stack:** Markdown, Mermaid, JSON Schema, Protobuf, Spring Boot service READMEs, existing unit/integration tests.

**Source of truth:**
- `docs/README.md`
- `docs/architecture.md`
- `docs/contracts.md`
- `docs/event-model.md`
- `docs/services.md`
- `docs/observability.md`
- `docs/roadmap.md`
- `docs/html/*.html`
- `api/protobuf/realtime_speech.proto`
- `api/json-schema/*.json`
- `services/*/README.md`
- `services/*/src/main/**`

---

### Task 1: Build a Stable Mapping from HTML Sources to Active Docs

**Files:**
- Modify: `docs/README.md`
- Create: `docs/html/README.md`

- [x] Add a compact index for the 7 original HTML articles and map each one to the Markdown docs that now absorb its design intent.
- [x] State clearly that `docs/html/` is reference/archive material, not the day-to-day maintenance target.
- [x] Capture which Markdown doc owns each theme now: gateway, session lifecycle, Kafka eventing, ASR, translation, TTS/distribution, observability, and roadmap.
- [x] Remove outdated wording that still describes this repository as “not yet an actual code implementation” or as only a资料收敛仓库.

### Task 2: Publish a Current Implementation Snapshot

**Files:**
- Create: `docs/implementation-status.md`
- Modify: `docs/README.md`
- Modify: `docs/services.md`
- Modify: `docs/architecture.md`

- [x] Add one implementation snapshot document that records the currently runnable chain: `speech-gateway -> session-orchestrator/control-plane -> Kafka -> asr-worker -> translation-worker -> tts-orchestrator`.
- [x] Document actual implemented HTTP/WebSocket entrypoints, Kafka topics, and service ports already present in code.
- [x] Pull current scope/out-of-scope statements up from service READMEs so `docs/services.md` reflects real module status instead of only recommended future splits.
- [x] Add a “current implementation vs target architecture” section so readers can tell which parts are already coded and which parts remain design intent.

### Task 3: Separate Frozen Contracts from Planned Extensions

**Files:**
- Modify: `docs/contracts.md`
- Modify: `docs/event-model.md`
- Modify: `docs/architecture.md`
- Modify: `api/protobuf/realtime_speech.proto` (only if a real contract mismatch is confirmed)
- Modify: `api/json-schema/*.json` (only if a real contract mismatch is confirmed)

- [x] Keep the Phase 0 frozen contract as the normative target for external behavior; do not downgrade it just because some downstream behaviors are not implemented yet.
- [x] Mark the currently implemented subset explicitly: `audio.ingress.raw`, `session.control`, `asr.final`, `translation.result`, `tts.request`, plus `session.start` / `session.stop` control APIs.
- [x] Rework `docs/event-model.md` so optional/future topics such as `audio.vad.segmented`, `asr.partial`, `translation.request`, `tts.chunk`, `tts.ready`, and DLQ flows are labeled as planned extensions rather than implied as already live.
- [x] Review contract files against code; no protobuf/schema changes were required, so “not implemented yet” notes were added in status-oriented docs instead.

### Task 4: Refresh Roadmap and Phase Status Against Reality

**Files:**
- Modify: `docs/roadmap.md`
- Modify: `docs/services.md`
- Modify: `docs/README.md`

- [x] Update phase language that still says `api/` and `services/` are future work; the repository already contains contract files, service modules, and passing tests.
- [x] Reframe the roadmap around actual progress: skeleton services exist for all six core modules plus control-plane, but several production capabilities are still missing.
- [x] Preserve the original HTML vision as the target maturity model, while moving completed foundational items into a “done / baseline landed” view.
- [x] Make remaining gaps concrete: subtitle downlink, `asr.partial`, richer translation pipeline, TTS engine/storage/CDN, auth/rate limiting/backpressure, DLQ/retry, load testing, and ops automation.

### Task 5: Bring Observability Docs Up to the Current Baseline

**Files:**
- Modify: `docs/observability.md`
- Modify: `docs/roadmap.md`

- [x] Extend the “current baseline” section to include `tts-orchestrator` and `control-plane`, both of which now expose management, tracing, and service-specific metrics.
- [x] Update the metric inventory to reflect what code already emits today, and distinguish it from recommended future SLI/SLO coverage.
- [x] Call out the missing observability pieces explicitly: client-visible subtitle latency, Kafka lag dashboards, alert rules, load-test evidence, and failure-injection playbooks.
- [x] Keep the original observability article themes, but convert them into a phased maturity ladder rather than a claim that all capabilities already exist.

### Task 6: Verify and Lock the Updated Docs

**Files:**
- Verify only

- [x] Cross-check every changed Markdown claim against actual code paths, application config, and tests before finalizing.
- [x] Run `tools/verify.sh` after doc updates to ensure contract files remain valid and the repository still passes baseline verification.
- [x] Review the final docs set for one rule: future target behavior, current implementation, and HTML historical context must never be mixed without labels.

## Non-goals in This Plan

- No new runtime feature implementation.
- No contract version bump unless a real external behavior change is intentionally approved.
- No attempt to convert all HTML content into Markdown line-by-line.

## Exit Criteria

- The repository has one clear, current Markdown path for understanding the system without opening the raw HTML articles.
- Readers can distinguish implemented behavior from target architecture without guessing.
- Contracts remain authoritative, and roadmap/observability/services docs no longer lag behind the codebase.
