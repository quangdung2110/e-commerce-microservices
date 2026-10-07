#!/usr/bin/env bash
# Chạy `next dev` trong container, nối vào mạng của docker-compose để gọi thẳng
# API Gateway (`http://api-gateway:80`). Mở http://localhost:3000.
#
#   docker compose up -d api-gateway auth-service …   # backend cần dùng
#   frontend/scripts/dev.sh
#
# Dùng chung volume node_modules với scripts/node.sh; chạy `scripts/node.sh npm ci`
# trước lần đầu.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
case "$REPO_ROOT" in
  /[a-zA-Z]/*) REPO_MOUNT="/${REPO_ROOT}" ;;
  *) REPO_MOUNT="$REPO_ROOT" ;;
esac

PORT="${PORT:-3000}"

# Bind mount của Docker Desktop trên Windows không phát sự kiện thay đổi file,
# nên hot reload phải poll. Turbopack (mặc định của Next 16) không có chế độ
# poll, nên dev trong container dùng webpack + WATCHPACK_POLLING; `next build`
# vẫn dùng Turbopack.
MSYS_NO_PATHCONV=1 docker run --rm --name frontend-dev \
  --network ecommerce-network \
  -p "${PORT}:3000" \
  -v "${REPO_MOUNT}://repo" \
  -v "frontend-node-modules://repo/frontend/node_modules" \
  -w //repo/frontend \
  -e NEXT_TELEMETRY_DISABLED=1 \
  -e WATCHPACK_POLLING=true \
  -e API_UPSTREAM=http://api-gateway:80 \
  node:20-alpine npx next dev --webpack -H 0.0.0.0 -p 3000
