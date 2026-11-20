#!/usr/bin/env bash
# Regenerates internal/infrastructure/product/pb from contracts/ with the pinned protoc and plugins, in the
# same golang container the other scripts use. The machine needs neither protoc nor Go.
#
#   scripts/generate-pb.sh
#
# Pinned to what the committed stubs were generated with: protoc 24.4, protoc-gen-go 1.33.0,
# protoc-gen-go-grpc 1.3.0. `make generate` also rewrites pb/PROTO_SHA256, which TestGeneratedStubsMatchCanonicalProto
# (and `make check-pb`) compare against the canonical .proto files (Cart ADR-015).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
# Git Bash on Windows: /c/... must become //c/... for docker -v.
case "$REPO_ROOT" in
  /[a-zA-Z]/*) REPO_MOUNT="/${REPO_ROOT}" ;;
  *) REPO_MOUNT="$REPO_ROOT" ;;
esac

MSYS_NO_PATHCONV=1 docker run --rm \
  -v "${REPO_MOUNT}://repo" \
  -v "cart-go-mod://go/pkg/mod" \
  -v "cart-go-cache://root/.cache/go-build" \
  -w //repo/go/services/cart-service \
  -e CGO_ENABLED=0 \
  golang:1.25 sh -ec '
    apt-get update -qq >/dev/null
    apt-get install -y -qq unzip curl make >/dev/null
    curl -fsSL -o /tmp/protoc.zip https://github.com/protocolbuffers/protobuf/releases/download/v24.4/protoc-24.4-linux-x86_64.zip
    unzip -q -o /tmp/protoc.zip -d /usr/local
    GOBIN=/usr/local/bin go install google.golang.org/protobuf/cmd/protoc-gen-go@v1.33.0
    GOBIN=/usr/local/bin go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@v1.3.0
    make generate
  '
