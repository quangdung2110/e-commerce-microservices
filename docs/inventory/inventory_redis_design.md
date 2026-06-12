# Inventory Service - Redis Caching & Lua Scripts

**Liên kết nhanh (Related Documents):**
* [Database & Cache Schema](inventory_db_design.md)
* [Workflows](inventory_workflow.md)
* [Events Structure](inventory_events.md)
* [Background Workers](inventory_workers.md)
* [Hybrid Routing Strategy](hybrid_routing_strategy.md)

---

Tài liệu này mô tả lớp Redis của Inventory Service: cấu trúc key, nguyên tắc atomic bằng Lua Script, idempotency, và cơ chế Redis Stream Outbox để không mất event khi xử lý bất đồng bộ.

## 1. Mục tiêu thiết kế

1. Reserve/Deduct/Release phải atomic ở cấp `(sku_id, warehouse_id)`.
2. Chống duplicate request và replay bằng idempotency key theo item.
3. Mọi biến động tồn kho nóng phải có event tương ứng được append atomically.
4. Không dùng DB lock trong đường nóng checkout/order.
5. Poison handling (SKU suspension + failed event parking) được xử lý hoàn toàn trên Redis.

## 2. Cấu trúc key Redis

### 2.1. Key tồn kho vật lý theo kho (nguồn commit nóng)

* Key pattern: `inventory:physic:{sku_id:warehouse_id}:stock`
* Ví dụ: `inventory:physic:{sku-001:5}:stock`
* Type: Hash
* Fields:
  * `total`
  * `reserved`
  * `available`
  * `updated_at_ms`

### 2.2. Key tồn kho logic (aggregate read model)

* Key pattern: `inventory:logic:{sku_id}:stock`
* Ví dụ: `inventory:logic:{sku-001}:stock`
* Type: Hash
* Fields:
  * `total`
  * `reserved`
  * `available`
  * `updated_at_ms`

> `inventory:logic:*` là read model phục vụ query/preview, không phải commit source cho Reserve/Deduct/Release.

### 2.3. Idempotency keys (per item)

* Reserve: `inventory:idemp:reserve:{sku_id:warehouse_id}:order:{order_id}`
* Release: `inventory:idemp:release:{sku_id:warehouse_id}:order:{order_id}`
* Deduct: `inventory:idemp:deduct:{sku_id:warehouse_id}:order:{order_id}`

TTL khuyến nghị: 30 ngày.

### 2.4. Redis Stream Outbox keys

* Key pattern: `inventory:stream:{sku_id:warehouse_id}:events`
* Ví dụ: `inventory:stream:{sku-001:5}:events`
* Mỗi Lua script append `XADD` vào stream này khi và chỉ khi mutation được apply mới.

### 2.5. SKU Suspension Keys (thay thế `suspended_sku_locks`)

* Key pattern: `inventory:suspended:{sku_id:warehouse_id}`
* Ví dụ: `inventory:suspended:{sku-001:5}`
* Type: Hash
* Fields:
  * `lock_reason` — lý do suspend (VD: `INVARIANT_DRIFT`)
  * `locked_at` — epoch_ms thời điểm lock
  * `status` — `SUSPENDED` / `UNLOCKED`
* TTL: 24 giờ (auto-cleanup nếu không có human intervention)
* Check: Lua script kiểm tra `EXISTS` trước khi mutate

### 2.6. Failed Events Stream (thay thế `dlq_events` + `suspended_events`)

* Key pattern: `inventory:failed:{sku_id:warehouse_id}:events`
* Ví dụ: `inventory:failed:{sku-001:5}:events`
* Type: Redis Stream
* Mỗi entry chứa:
  * `event_type` — loại event gốc
  * `event_payload` — JSON payload
  * `failure_reason` — `DLQ` | `SKU_SUSPENDED`
  * `id` — Kafka message ID gốc
  * `parked_at` — epoch_ms
* TTL: 7 ngày (auto-cleanup)
* Recovery: Suspended Events Recovery Worker đọc stream này và replay

> **Một Redis Stream duy nhất** thay thế 2 bảng PostgreSQL (`dlq_events`, `suspended_events`). `failure_reason` phân biệt event thuộc DLQ hay chỉ bị park مؤقتí vì SKU đang suspend.

---

## 3. Lua Scripts

### Return codes chung

* `1`: mutation mới được apply.
* `2`: idempotent hit (đã apply trước đó, không mutate lại).
* `0`: business reject (`OUT_OF_STOCK`, `INSUFFICIENT_RESERVED`, `RELEASE_UNDERFLOW`).
* `-1`: invariant drift detected (fail-safe, không tự heal im lặng).

> Backend chỉ được phát event mới khi script trả `1`. Nếu trả `2` phải trả response idempotent và tuyệt đối không phát thêm event.

**Runbook bắt buộc khi nhận `-1`:**
1. Dừng xử lý request hiện tại ngay lập tức.
2. Không retry nóng và không auto-rollback mù quáng trên SKU lỗi.
3. Đặt Redis key `inventory:suspended:{sku_id:warehouse_id}` với TTL 24h.
4. `XADD` event lỗi vào Redis Stream `inventory:failed:{sku_id:warehouse_id}:events` với `failure_reason=DLQ`.
5. Phát alert vận hành; chỉ mở khóa sau khi đã đối soát Redis/DB và xác nhận invariant khôi phục.

> Boundary quyết định: mutate nóng chỉ diễn ra trên key `inventory:physic:{sku_id:warehouse_id}:stock`. Key `inventory:logic:{sku_id}:stock` là read model tổng hợp và phải được cập nhật qua worker/reconciliation, không dùng làm commit source cho Reserve/Deduct/Release.

### Script 1: Reserve Inventory

**Ngữ cảnh:** `ReserveInventoryActivity` (pre-payment).

**Input:**
* `KEYS[1]`: stock hash `inventory:physic:{sku_id:warehouse_id}:stock`
* `KEYS[2]`: idempotency key reserve
* `KEYS[3]`: stream outbox key
* `ARGV[1]`: `quantity`
* `ARGV[2]`: `now_ms`
* `ARGV[3]`: `idemp_ttl_sec`
* `ARGV[4]`: `event_payload_json`

**Luật xử lý:**
1. Nếu idempotency key đã tồn tại -> trả `2`.
2. Kiểm tra `available >= quantity`; nếu không đủ -> trả `0`.
3. Apply mutation: `available -= qty`, `reserved += qty`.
4. Set idempotency key TTL.
5. `XADD` event vào stream outbox.
6. Trả `1`.

**Pseudo Lua:**

```lua
if redis.call('SETNX', KEYS[2], '1') == 0 then
  return 2
end
redis.call('EXPIRE', KEYS[2], tonumber(ARGV[3]))

local available = tonumber(redis.call('HGET', KEYS[1], 'available') or '0')
local qty = tonumber(ARGV[1])
if available < qty then
  redis.call('DEL', KEYS[2])
  return 0
end

redis.call('HINCRBY', KEYS[1], 'available', -qty)
redis.call('HINCRBY', KEYS[1], 'reserved', qty)
redis.call('HSET', KEYS[1], 'updated_at_ms', ARGV[2])
redis.call('XADD', KEYS[3], '*', 'event_type', 'INVENTORY_RESERVED', 'payload', ARGV[4])
return 1
```

### Script 2: Release / Rollback Reservation

**Ngữ cảnh:** `RollbackInventoryActivity` hoặc Reservation Expiry Scanner.

**Input:**
* `KEYS[1]`: stock hash
* `KEYS[2]`: idempotency key release
* `KEYS[3]`: stream outbox key
* `ARGV[1]`: `quantity`
* `ARGV[2]`: `now_ms`
* `ARGV[3]`: `idemp_ttl_sec`
* `ARGV[4]`: `event_payload_json`

**Luật xử lý:**
1. Nếu idempotency key tồn tại -> trả `2`.
2. Kiểm tra `reserved >= quantity`; nếu không đủ -> trả `0`.
3. Apply mutation: `reserved -= qty`, `available += qty`.
4. Set idempotency key TTL.
5. `XADD` event `INVENTORY_RELEASED`.
6. Trả `1`.

**Pseudo Lua:**

```lua
if redis.call('SETNX', KEYS[2], '1') == 0 then
  return 2
end
redis.call('EXPIRE', KEYS[2], tonumber(ARGV[3]))

local reserved = tonumber(redis.call('HGET', KEYS[1], 'reserved') or '0')
local qty = tonumber(ARGV[1])
if reserved < qty then
  redis.call('DEL', KEYS[2])
  return 0
end

redis.call('HINCRBY', KEYS[1], 'reserved', -qty)
redis.call('HINCRBY', KEYS[1], 'available', qty)
redis.call('HSET', KEYS[1], 'updated_at_ms', ARGV[2])
redis.call('XADD', KEYS[3], '*', 'event_type', 'INVENTORY_RELEASED', 'payload', ARGV[4])
return 1
```

### Script 3: Deduct Inventory (Commit)

**Ngữ cảnh:** `DeductInventoryActivity` sau payment success.

**Input:**
* `KEYS[1]`: stock hash
* `KEYS[2]`: idempotency key deduct
* `KEYS[3]`: stream outbox key
* `ARGV[1]`: `quantity`
* `ARGV[2]`: `now_ms`
* `ARGV[3]`: `idemp_ttl_sec`
* `ARGV[4]`: `event_payload_json`

**Luật xử lý:**
1. Nếu idempotency key tồn tại -> trả `2`.
2. Kiểm tra invariant trước mutate: `available == total - reserved`; nếu sai -> `-1`.
3. Kiểm tra `reserved >= quantity`; nếu không đủ -> `0`.
4. Apply mutation: `reserved -= qty`, `total -= qty`, `available = total - reserved`.
5. Re-check invariant sau mutate; nếu sai -> `-1`.
6. Set idempotency key TTL.
7. `XADD` event `INVENTORY_DEDUCTED`.
8. Trả `1`.

**Pseudo Lua:**

```lua
if redis.call('SETNX', KEYS[2], '1') == 0 then
  return 2
end
redis.call('EXPIRE', KEYS[2], tonumber(ARGV[3]))

local reserved = tonumber(redis.call('HGET', KEYS[1], 'reserved') or '0')
local total = tonumber(redis.call('HGET', KEYS[1], 'total') or '0')
local available = tonumber(redis.call('HGET', KEYS[1], 'available') or '0')
local qty = tonumber(ARGV[1])

if available ~= (total - reserved) then
  redis.call('DEL', KEYS[2])
  return -1
end

if reserved < qty then
  redis.call('DEL', KEYS[2])
  return 0
end

local new_reserved = reserved - qty
local new_total = total - qty
local new_available = new_total - new_reserved

if new_available < 0 then
  redis.call('DEL', KEYS[2])
  return -1
end

redis.call('HSET', KEYS[1],
  'reserved', tostring(new_reserved),
  'total', tostring(new_total),
  'available', tostring(new_available),
  'updated_at_ms', ARGV[2])

redis.call('XADD', KEYS[3], '*', 'event_type', 'INVENTORY_DEDUCTED', 'payload', ARGV[4])
return 1
```

---

## 4. Invariants bắt buộc

1. `available >= 0`
2. `reserved >= 0`
3. `total >= 0`
4. `available + reserved == total`

---

## 5. Reconciliation & self-healing

1. Job định kỳ (5-15 phút) so sánh Redis với PostgreSQL snapshot cho cả logical và physical.
2. Nếu lệch vượt ngưỡng -> ưu tiên PostgreSQL làm chuẩn và rebuild Redis.
3. Mọi lần rebuild phải ghi metric và audit log.
4. **Recovery Worker** kiểm tra Redis Stream `inventory:failed:*` theo `failure_reason`:
   - `DLQ`: cần human review trước khi replay
   - `SKU_SUSPENDED`: replay tự động khi Redis suspension key hết hạn

---

## 6. Observability

* `inventory_reserve_lua_applied_total`
* `inventory_reserve_lua_idempotent_hit_total`
* `inventory_release_lua_applied_total`
* `inventory_deduct_lua_applied_total`
* `inventory_lua_reject_total{reason}`
* `inventory_lua_invariant_drift_total`
* `inventory_stream_outbox_pending_total`
* `inventory_stream_relay_lag_seconds`
* `inventory_suspended_skus_total` (đếm Redis keys `inventory:suspended:*`)
* `inventory_failed_events_parked_total` (đếm entries trong `inventory:failed:*`)

---

## 7. Lưu ý triển khai Redis Cluster

1. `KEYS[1]`, `KEYS[2]`, `KEYS[3]` phải cùng hash slot.
2. Áp dụng hash-tag thống nhất theo `{sku_id:warehouse_id}` cho stock/idemp/stream/suspended/failed key.
3. Không đưa key global khác slot vào Lua.

Ví dụ key hợp lệ cùng slot:
* `inventory:physic:{sku-001:5}:stock`
* `inventory:idemp:reserve:{sku-001:5}:order:10001`
* `inventory:stream:{sku-001:5}:events`
* `inventory:suspended:{sku-001:5}`
* `inventory:failed:{sku-001:5}:events`
