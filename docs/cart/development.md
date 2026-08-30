---
doc_id: cart-development
service: cart
scope: current
status: proposed
owners:
  - Cart Service team
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

Không commit secret. Local Redis vẫn nên bật AOF nếu muốn test restart behavior. Dùng `noeviction` để test không vô tình che memory-pressure failure.

## 4. Lua scripts

Source chuẩn là [`scripts/`](./scripts):

- `upsert_item.lua`
- `remove_items.lua`
- `mark_abandoned.lua`

Mọi `KEYS[]` của từng script phải có cùng `{bNN}`. Test bắt buộc gồm Redis Cluster cross-slot, duplicate HTTP idempotency, stale Product validation, marker deadline stale, write/marker race và redelivery `ORDER_PAID` idempotent.

## 5. Build, test và contracts

Chạy formatter/linter/test của Go service theo Makefile khi implementation tồn tại. Contract test xác nhận `CART_ABANDONED` khớp AsyncAPI/JSON Schema và Notification dedupe CloudEvent ID. Test relay phải chứng minh Kafka failure không dẫn tới `XACK`.

## 6. Debugging

| Symptom | Kiểm tra |
|---|---|
| Cart biến mất | TTL `items`/`meta`, eviction policy, AOF/replica health |
| Không có notification | ZSET score, marker result, stream pending và relay lag |
| Event bị lặp | CloudEvent ID phải bằng `outbox_key + stream_id`; kiểm tra Notification dedupe |
| Product bị gọi quá nhiều | Validation expiry, LRU hit rate và singleflight key |
| Redis `CROSSSLOT` | Toàn bộ script key phải dùng cùng `{bNN}` |

## 7. Definition of Done

- Không có đường code/PostgreSQL config hoặc rehydrate trong Cart.
- Script state transition có test nguyên tử.
- 64-stream round-robin relay/trimmer và marker có metric/alert.
- AsyncAPI chỉ công bố `CART_ABANDONED` và contract test pass.
