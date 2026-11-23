#!/usr/bin/env bash
# Runs contracts/tools/verify_providers.py against the running compose stack (contracts ADR-009).
#
#   scripts/ci/verify-providers.sh [verify_providers.py arguments...]
#
# The probes use service names (cart-service, user-service...) and the internal ports, so the tool runs inside a
# throwaway container attached to the compose network. NETWORK defaults to ecommerce-network.
set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel)"
NETWORK="${NETWORK:-ecommerce-network}"
IMAGE="${PYTHON_IMAGE:-python:3.12-slim}"
export MSYS_NO_PATHCONV=1
host_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

docker run --rm --network "$NETWORK" -e AUTH_INTERNAL_SIGNING_KEY \
  -v "$(host_path "$REPO_ROOT"):/repo:ro" -w /repo "$IMAGE" sh -c \
  'pip install --quiet --disable-pip-version-check pyyaml==6.0.2 "jsonschema>=4.18" >/dev/null && python contracts/tools/verify_providers.py "$@"' \
  verify "$@"
