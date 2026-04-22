# Observability Baseline Plan

## Goal

Establish a first production-shaped observability baseline across existing services:

- `speech-gateway`
- `session-orchestrator`
- `asr-worker`
- `translation-worker`

This phase focuses on consistent metrics/tracing wiring and low-cardinality business counters/timers.

## Task 1: Add Runtime Observability Dependencies

- [x] Add Prometheus registry runtime dependency in all four service modules.
- [x] Add Micrometer OTel tracing bridge dependency in all four service modules.
- [x] Add OTLP exporter runtime dependency in all four service modules.

## Task 2: Standardize Management + Tracing Config

- [x] Expose `health,info,metrics,prometheus` via management endpoints in all four services.
- [x] Enable health probes consistently.
- [x] Add common metric tags (`service`, `env`) for baseline slicing.
- [x] Add tracing sampling and OTLP endpoint configuration.
- [x] Add trace/span-aware log pattern for correlation.

## Task 3: Add Baseline Business Metrics

- [x] Add message routing counters/timers in `speech-gateway` (`GatewayMessageRouter`).
- [x] Add start/stop counters/timers in `session-orchestrator` (`SessionLifecycleService`).
- [x] Add consume/process/publish counters/timers in `asr-worker` (`AudioIngressConsumer`).
- [x] Add consume/process/publish counters/timers in `translation-worker` (`AsrFinalConsumer`).
- [x] Keep tags low-cardinality (`result`, `code`, `type`) only.

## Task 4: Update Tests and Verify

- [x] Update unit tests for constructor changes that now require `MeterRegistry`.
- [x] Run focused module tests for changed services.
- [x] Run repository verification script.
