#!/usr/bin/env bash
# Runs a Go command for notification-service inside the golang:1.25 container.
# The machine has no Go toolchain; module and build caches are kept in named
# Docker volumes so repeated runs are fast.
#
#   scripts/go.sh build ./...
#   scripts/go.sh vet ./...
#   scripts/go.sh test ./internal/...
#   scripts/go.sh test -tags integration ./test/integration/...   (needs docker socket, see below)
#
# Integration tests use testcontainers from inside the container: the socket is
# mounted, TESTCONTAINERS_HOST_OVERRIDE points at the host and Ryuk is disabled
# (see the project memory on Docker API version pinning).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
# Git Bash on Windows: /c/... must become //c/... for docker -v.
case "$REPO_ROOT" in
  /[a-zA-Z]/*) REPO_MOUNT="/${REPO_ROOT}" ;;
  *) REPO_MOUNT="$REPO_ROOT" ;;
esac

EXTRA=()
if [[ "${*}" == *"integration"* ]]; then
  EXTRA+=( -v /var/run/docker.sock:/var/run/docker.sock
           --add-host=host.docker.internal:host-gateway
           -e DOCKER_HOST=unix:///var/run/docker.sock
           -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal
           -e TESTCONTAINERS_RYUK_DISABLED=true
           -e DOCKER_API_VERSION=1.44 )
fi

MSYS_NO_PATHCONV=1 docker run --rm \
  -v "${REPO_MOUNT}://repo" \
  -v "notification-go-mod://go/pkg/mod" \
  -v "notification-go-cache://root/.cache/go-build" \
  -w //repo/go/services/notification-service \
  -e CGO_ENABLED=0 \
  "${EXTRA[@]}" \
  golang:1.25 go "$@"
