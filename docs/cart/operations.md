---
doc_id: cart-operations
service: cart
scope: current
status: proposed
owners:
  - Cart Service team
verified_against_commit: null
---

# Cart Service — Operations

## 1. SLO và health

| SLI | Mục tiêu / alert |
|---|---|
| API availability | TBD; Redis lỗi là lỗi Cart toàn phần |
| API write latency | Theo dõi Redis Lua latency riêng |
| Relay lag | Age entry cũ nhất, pending count và Kafka produce failures |
| Marker lag | Deadline ZSET quá hạn chưa xử lý |
| Redis durability | AOF health, replication lag, memory headroom |

`/health/live` chỉ xác nhận process; `/health/ready` kiểm tra Redis, Kafka và Product dependency theo policy. Không có PostgreSQL health check.

## 2. Required configuration

| Config | Giá trị khởi đầu |
|---|---|
| `CART_BUCKET_COUNT` | `64`, bất biến trong namespace `v2` |
| `CART_ABANDONED_AFTER` | `30d` |
| `CART_ACTIVE_TTL` | `37d` |
| `CART_ABANDONED_TTL` | `7d` |
| `CART_PRODUCT_VALIDATION_TTL` | `30–60s` |
| `CART_IDEMPOTENCY_TTL` | `24h` |
| Redis eviction | `noeviction` |
| Redis persistence | AOF + replica |

## 3. Failure modes

| Failure | Hành vi |
|---|---|
| Redis unavailable | API read/write lỗi; marker/relay dừng; không fallback |
| Redis data loss | Cart bị mất vĩnh viễn; API sau đó trả rỗng |
| Product lỗi lúc write | Từ chối SKU mới/refresh stale, cart không đổi |
| Product lỗi lúc GET | `200` degraded với snapshot |
| Kafka lỗi | Stream entry không ACK, relay retry |
| Relay crash | `XAUTOCLAIM` lấy pending entry khi khởi động lại |
| Marker chậm | TTL buffer 7 ngày; entry stale được dọn không phát event |

## 4. Workers và scheduled jobs

### Abandoned marker loop

Một loop quét tuần tự 64 ZSET; batch nhỏ và gọi `mark_abandoned.lua` từng cart. Không dùng distributed lease; deployment hiện tại chạy một active marker owner. Alert khi overdue deadline age vượt ngưỡng.

### Relay loop

Một loop round-robin 64 stream và chỉ dùng Redis call một stream mỗi lượt. Stream rỗng được bỏ qua; stream được tạo lazy. Relay `XAUTOCLAIM` trước rồi đọc mới, produce xong mới ACK. Khi scale, chia bucket ownership tĩnh hoặc qua scheduler.

### Stream trimmer loop

Một loop round-robin 64 stream. Chỉ trim khi group đã tồn tại, mốc delivered tiến và pending entry cũ nhất được giữ lại. Không trim theo `MAXLEN` tại `XADD`.

## 5. Observability

Log/traces mang request ID, cart ID dạng privacy-safe và outbox bucket. Không đưa user ID làm metric label. Metric cần có: script result/error, cache-validation hit/miss, marker marked/stale, stream length, pending age, relay retry, Kafka error và trim count.

## 6. Deployment và rollback

Deploy script mới trước application gọi nó. Namespace `v2` không tương thích key layout cũ; rollout cần migration/dual-read plan riêng nếu có dữ liệu `v1` thật. Rollback không được đổi số bucket hoặc xóa stream pending. Không còn database migration cho Cart.
