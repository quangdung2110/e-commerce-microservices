#!/usr/bin/env bash
# Chạy một lệnh Node/npm cho frontend bên trong container node:20-alpine — cùng
# phiên bản với Dockerfile. Máy phát triển không cài Node nên mọi lệnh npm đi qua
# đây.
#
#   scripts/node.sh npm ci
#   scripts/node.sh npm run lint
#   scripts/node.sh npm run typecheck
#   scripts/node.sh npm test
#   scripts/node.sh npm run build
#   scripts/node.sh npm run gen:api
#
# Cả repository được mount vào /repo để `gen:api` đọc được `contracts/`.
# `node_modules` nằm trong named volume: npm ci trên bind mount của Windows rất
# chậm, và giữ volume giúp các lần chạy sau không phải cài lại. Hệ quả là thư mục
# `frontend/node_modules` không xuất hiện trên máy host.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# Git Bash trên Windows: /c/... phải thành //c/... khi truyền cho docker -v.
case "$REPO_ROOT" in
  /[a-zA-Z]/*) REPO_MOUNT="/${REPO_ROOT}" ;;
  *) REPO_MOUNT="$REPO_ROOT" ;;
esac

TTY=()
if [ -t 0 ] && [ -t 1 ]; then TTY=(-it); fi

MSYS_NO_PATHCONV=1 docker run --rm "${TTY[@]}" \
  -v "${REPO_MOUNT}://repo" \
  -v "frontend-node-modules://repo/frontend/node_modules" \
  -v "frontend-npm-cache://root/.npm" \
  -w //repo/frontend \
  -e NEXT_TELEMETRY_DISABLED=1 \
  node:20-alpine "$@"
