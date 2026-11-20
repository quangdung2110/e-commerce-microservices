#!/usr/bin/env bash
# Runs the k6 load tests under loadtest/k6 against the compose stack (docs/performance/load-test.md).
#
#   scripts/ci/loadtest.sh [script.js]            default: storefront-reads.js
#   VUS=50 DURATION=2m scripts/ci/loadtest.sh      heavier run
#
# k6 runs in a container on the compose network and reaches the gateway at http://api-gateway:80. The stack must already be up
# and healthy, built from the code you want to measure. Read-only: nothing here writes data.
set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
SCRIPT="${1:-storefront-reads.js}"
NETWORK="${NETWORK:-ecommerce-network}"
IMAGE="${K6_IMAGE:-grafana/k6:0.54.0}"
export MSYS_NO_PATHCONV=1
host_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

docker run --rm --network "$NETWORK" \
  -e BASE_URL="${BASE_URL:-http://api-gateway:80}" -e PDP_URL="${PDP_URL:-}" -e SEARCH_URL="${SEARCH_URL:-}" -e VUS="${VUS:-20}" -e DURATION="${DURATION:-60s}" -e SLUGS="${SLUGS:-}" \
  -v "$(host_path "$REPO_ROOT/loadtest/k6"):/scripts:ro" \
  "$IMAGE" run "/scripts/$SCRIPT"
