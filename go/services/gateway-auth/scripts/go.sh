#!/usr/bin/env bash
# Runs a Go command for gateway-auth inside the golang:1.25 container. The
# machine has no Go toolchain; module and build caches live in named volumes.
#
#   scripts/go.sh mod tidy
#   scripts/go.sh vet ./...
#   scripts/go.sh test ./...
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
# Git Bash on Windows: /c/... must become //c/... for docker -v.
case "$REPO_ROOT" in
  /[a-zA-Z]/*) REPO_MOUNT="/${REPO_ROOT}" ;;
  *) REPO_MOUNT="$REPO_ROOT" ;;
esac

MSYS_NO_PATHCONV=1 docker run --rm \
  -v "${REPO_MOUNT}://repo" \
  -v "gateway-auth-go-mod://go/pkg/mod" \
  -v "gateway-auth-go-cache://root/.cache/go-build" \
  -w //repo/go/services/gateway-auth \
  -e CGO_ENABLED=0 \
  golang:1.25 go "$@"
