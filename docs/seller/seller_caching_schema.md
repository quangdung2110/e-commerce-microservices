# Seller Service - Caching Schema (Caffeine L1 + Redis L2)

**Liên kết nhanh (Related Documents):**
* [Workflows (API & Data Flow)](seller_workflow.md)
* [Background Workers & Jobs](seller_workers.md)
* [Caching Schema (Redis + L1)](seller_caching_schema.md)
* [Database Schema (Write Store)](seller_db_design.md)
* [State Machine & Lifecycle](seller_state_machine.md)
* [Events Structure & Kafka Topics](seller_events.md)
* [Architecture Recommendations](seller_architecture_recommendations.md)

---

Tài liệu này mô tả chiến lược cache hai tầng của Seller Service: L1 Caffeine và L2 Redis.

## 1. Mục tiêu thiết kế cache

1. Giảm độ trễ cho profile/status/commission lookups.
2. Bảo vệ PostgreSQL khỏi read spikes từ Product, Checkout, Order.
3. Đảm bảo consistency cao cho các key critical như status và commission rules.

## 2. Kiến trúc 2 tầng cache

## 2.1 L1 Cache (Caffeine)

1. Nằm trong process, độ trễ thấp nhất.
2. TTL ngắn để giảm rủi ro stale khi miss invalidation.
3. Dùng cho key hot và tần suất gọi cao.

## 2.2 L2 Cache (Redis)

1. Dùng chung giữa các pod.
2. Là nguồn warmup và self-heal.
3. Dùng pipeline cho batch update/read.

## 3. Danh mục key và metadata

### Key `seller:profile:{seller_id}`

| Thuộc tính | Giá trị |
|---|---|
| Loại | String JSON |
| TTL L1/L2 | 60s / 30m |
| Mục đích | Profile point-read cho Product/Search/Order |

### Key `seller:status:{seller_id}`

| Thuộc tính | Giá trị |
|---|---|
| Loại | String JSON |
| TTL L1/L2 | 30s / 15m |
| Mục đích | Validate seller status ở luồng checkout/listing |

### Key `seller:config:{seller_id}`

| Thuộc tính | Giá trị |
|---|---|
| Loại | String JSON |
| TTL L1/L2 | 60s / 20m |
| Mục đích | Cấu hình vận hành seller |

### Key `seller:commission_rule:{seller_id}`

| Thuộc tính | Giá trị |
|---|---|
| Loại | String JSON |
| TTL L1/L2 | 30s / 10m |
| Mục đích | Lookup commission rate ở worker tính commission |

### Key `seller:performance:{seller_id}`

| Thuộc tính | Giá trị |
|---|---|
| Loại | String JSON |
| TTL L1/L2 | 60s / 5m |
| Mục đích | Dashboard và policy checks |

### Key `seller:payout_summary:{seller_id}:{period}`

| Thuộc tính | Giá trị |
|---|---|
| Loại | String JSON |
| TTL L1/L2 | 120s / 10m |
| Mục đích | Tóm tắt tài chính theo kỳ |

### Key `seller:warehouse:primary:{seller_id}`

| Thuộc tính | Giá trị |
|---|---|
| Loại | String JSON |
| TTL L1/L2 | 60s / 15m |
| Mục đích | Warehouse chính cho fulfillment routing |

### Key vận hành

- `seller:idempotency:{key}`.
- `seller:lock:refresh:{seller_id}`.

## 4. Invalidation strategy

1. In-band: sau commit write API, overwrite L2 key và evict/refresh L1 key.
2. Out-of-band: self-consume seller events để self-heal cache.
3. Soft invalidation là mặc định; hard delete chỉ dùng khi key không còn hợp lệ nghiệp vụ.
4. Pub/Sub cho L1 evict cross-pod:
   - `channel:seller:l1-evict`.

**Schema message đề xuất:**

```json
{
  "type": "SELLER_STATUS_CHANGED",
  "seller_id": "s-001",
  "status_version": 17,
  "updated_at": "2026-03-31T10:00:00Z"
}
```

## 5. Stampede, stale, và consistency protections

1. Local singleflight theo `seller_id`.
2. Distributed lock `SETNX seller:lock:refresh:{seller_id} EX 2`.
3. TTL jitter cho key nhóm hot.
4. CAS theo version/timestamp trước khi overwrite cache.
5. Key critical (`seller:status:*`, `seller:commission_rule:*`) có thể bật cơ chế read-after-write verify nếu cần.

## 6. Batch access patterns

1. `BatchGetSellerProfiles` dùng MGET/pipeline.
2. Chi query DB cho keys miss.
3. Pipeline set cho nhóm miss để giảm network round-trips.

## 7. Redis key inventory

| Key Pattern | Loại | TTL | Invalidation | Mục đích |
|---|---|---|---|---|
| `seller:profile:{seller_id}` | String JSON | 30m | Overwrite theo profile events | Profile lookup |
| `seller:status:{seller_id}` | String JSON | 15m | Overwrite theo status events | Status validation |
| `seller:config:{seller_id}` | String JSON | 20m | Overwrite theo config events | Config lookup |
| `seller:commission_rule:{seller_id}` | String JSON | 10m | Overwrite theo config change | Commission rate lookup |
| `seller:performance:{seller_id}` | String JSON | 5m | Worker updates | Performance dashboard |
| `seller:payout_summary:{seller_id}:{period}` | String JSON | 10m | Payout worker refresh | Finance summary |
| `seller:warehouse:primary:{seller_id}` | String JSON | 15m | Overwrite theo warehouse events | Fulfillment routing |

## 8. SLO và cảnh báo

1. Cache hit ratio profile/status > 90%.
2. Redis p99 latency < 12ms.
3. Lock contention tăng đột biến phải cảnh báo.
4. Cảnh báo divergence khi status_version trong cache tụt hậu kéo dài.
