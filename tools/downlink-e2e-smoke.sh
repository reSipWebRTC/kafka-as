#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

echo "Running downlink E2E stability smoke checks..."
./gradlew \
  :services:speech-gateway:test \
  --tests "com.kafkaasr.gateway.ws.downlink.DownlinkE2EStabilityTests" \
  --tests "com.kafkaasr.gateway.ws.GatewayDownlinkPublisherTests" \
  --tests "com.kafkaasr.gateway.ws.downlink.AsrPartialDownlinkConsumerTests" \
  --tests "com.kafkaasr.gateway.ws.downlink.TranslationResultDownlinkConsumerTests" \
  --tests "com.kafkaasr.gateway.ws.downlink.SessionControlDownlinkConsumerTests"

echo "Downlink E2E stability smoke checks passed."
