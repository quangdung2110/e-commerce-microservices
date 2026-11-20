---
doc_id: cart-development
service: cart
scope: current
status: implemented
owners:
  - Cart Service team
evidence:
  - go/services/cart-service/Makefile
  - go/services/cart-service/scripts/go.sh
verified_against_commit: null
---

# Cart Service — Development Guide

## 1. Prerequisites

Go, Redis 7+ (Cluster-compatible testing), Kafka và Product Service/mock gRPC. Cart không cần PostgreSQL.

## 2. Local dependencies

| Dependency | Dùng cho |
|---|---|
| Redis | State, metadata, ZSET, idempotency và Streams |
| Kafka | Relay và contract test Notification event |
| Product mock | SKU validation và GET enrichment |

## 3. Local configuration

Root stack: `docker compose up -d --build cart-service` từ gốc repo. Cart không publish
cổng host; frontend gọi `/api/v1/carts/mine` qua Gateway `localhost:8088` với phiên Auth.
Host `8085` dành cho Promotion; internal Cart giữ `cart-service:8080`. Standalone
Compose trong thư mục service là stack riêng.

Không commit secret. Local Redis vẫn nên bật AOF nếu muốn test restart behavior. Dùng `noeviction` để test không vô tình che memory-pressure failure.

## 4. Lua scripts

Source chuẩn là [`scripts/`](./scripts):

- `upsert_item.lua`
- `remove_items.lua`
- `mark_abandoned.lua`
- `purge_cart.lua`

Service nhúng bản sao trong `internal/infrastructure/cartredis/scripts/`; `make sync-lua` copy từ thư mục
này sang service và `make check-lua` fail khi hai bên lệch. Chỉnh script ở đây, không chỉnh bản sao.

Stub Go của Product gRPC (`internal/infrastructure/product/pb`) sinh từ proto chuẩn trong `contracts/`: sửa proto
rồi chạy `scripts/generate-pb.sh` (Docker, protoc 24.4). `pb/PROTO_SHA256` ghi hash của proto nguồn;
`make check-pb` và `TestGeneratedStubsMatchCanonicalProto` fail khi proto đổi mà stub chưa sinh lại
([ADR-015](./decisions/ADR-015-proto-stub-drift-detection.md)).

Mọi `KEYS[]` của từng script phải có cùng `{bNN}`. Test bắt buộc gồm Redis Cluster cross-slot, duplicate HTTP idempotency, stale Product validation, marker deadline stale, write/marker race, redelivery `com.ecommerce.order.paid.v1` idempotent và purge `USER_DELETED` idempotent.

## 5. Build, test và contracts

Máy dev không có Go toolchain; chạy qua container bằng `bash scripts/go.sh` từ
`go/services/cart-service`:

```bash
bash scripts/go.sh vet ./...
bash scripts/go.sh test ./...
```

Test script chạy trên miniredis (gopher-lua) theo mặc định. Vì miniredis không phải Lua/cjson của Redis thật,
chạy lại suite trên Redis thật khi sửa script: đặt `CART_TEST_REDIS_ADDR=host:6379` (Redis dùng chung mạng
với container test; suite tự `FLUSHDB`). Test relay chứng minh Kafka failure không dẫn tới `XACK`. Chưa có
test tự động validate `CART_ABANDONED` với JSON Schema hay response HTTP với OpenAPI; đó là điều kiện còn thiếu
để chuyển contract từ `implemented` sang `verified` ([ADOPTION](../../contracts/ADOPTION.md)).

## 6. Debugging

| Symptom | Kiểm tra |
|---|---|
| Cart biến mất | TTL `items`/`meta`, eviction policy, AOF/replica health |
| Không có notification | ZSET score, marker result, stream pending và relay lag |
| Event bị lặp | CloudEvent ID phải bằng `outbox_key + stream_id`; kiểm tra Notification dedupe |
| Product bị gọi quá nhiều | Validation expiry, LRU hit rate và singleflight key |
| Redis `CROSSSLOT` | Toàn bộ script key phải dùng cùng `{bNN}` |
| Giỏ của account đã xóa còn đó | Consumer `cart-auth-consumer` có chạy không (`worker.user_deleted_consumer.enabled`), lag trên `t.auth.events`, metric `cart_user_deleted_outcomes_total` |
| Biến môi trường không có tác dụng | Khóa của phần `cart:` dùng `CART_CART_*` (xem [operations.md](./operations.md) §2) |

## 7. Definition of Done

- Không có đường code/PostgreSQL config hoặc rehydrate trong Cart.
- Script state transition có test nguyên tử.
- 64-stream round-robin relay/trimmer và marker có metric/alert.
- AsyncAPI chỉ công bố `CART_ABANDONED` và contract test pass.
