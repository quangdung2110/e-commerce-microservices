# Inventory Service - Redis Caching & Lua Scripts

**Liên kết nhanh (Related Documents):**
* [Database Schema](inventory_db_design.md)
* [Workflows](inventory_workflow.md)
* [Events Structure](inventory_events.md)
* [Workers](inventory_workers.md)
* [Smart Routing Engine](smart_routing_engine.md)

**Cross-Service References:**
* [Order Temporal Saga](../order/temporal_saga_design.md) — Activities gọi Reserve/Deduct/Release
* [Checkout Workflow](../checkout/checkout_workflow.md) — Preview stock check và submit
* [Inventory Events](inventory_events.md) — Inventory produce `INVENTORY_STOCK_*` events; từ Phase 14 không còn consume Shipping events.

---

Tài liệu này mô tả lớp Redis của Inventory Service: cấu trúc key, nguyên tắc atomic bằng Lua Script, idempotency, và cơ chế Redis Stream Outbox để không mất event khi xử lý bất đồng bộ.

## 1. Mục tiêu thiết kế

1. Reserve/Deduct/Release phải atomic ở cấp `(sku_id, warehouse_id)`.
2. Chống duplicate request và replay bằng idempotency key theo item.
3. Mọi biến động tồn kho nóng phải có event tương ứng được append atomically.
4. Không dùng DB lock trong đường nóng checkout/order.
5. SKU suspension (khi invariant drift) chỉ là cờ cảnh báo, không park event.

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

### 2.2. Idempotency keys (per item)

* Reserve: `inventory:idemp:reserve:{sku_id:warehouse_id}:order:{order_id}`
* Release: `inventory:idemp:release:{sku_id:warehouse_id}:order:{order_id}`
* Deduct: `inventory:idemp:deduct:{sku_id:warehouse_id}:order:{order_id}`

TTL khuyến nghị: 30 ngày.

### 2.3. Redis Stream Outbox keys

* Key pattern: `inventory:stream:{sku_id:warehouse_id}:events`
* Ví dụ: `inventory:stream:{sku-001:5}:events`
* Mỗi Lua script append `XADD` vào stream này khi và chỉ khi mutation được apply mới.
* Stream này là nguồn của event `INVENTORY_STOCK_MUTATION` đi tới Kafka.

### 2.4. SKU Suspension Keys

* Key pattern: `inventory:suspended:{sku_id:warehouse_id}`
* Ví dụ: `inventory:suspended:{sku-001:5}`
* Type: Hash
* Fields:
  * `lock_reason` — lý do suspend (VD: `INVARIANT_DRIFT`)
  * `locked_at` — epoch_ms thời điểm lock
  * `status` — `SUSPENDED` / `UNLOCKED`
* TTL: 24 giờ (auto-cleanup nếu không có human intervention)
* Check: Lua script kiểm tra `EXISTS` đầu script; nếu tồn tại, trả mã lỗi `SKU_SUSPENDED` (mapped sang business reject) và KHÔNG mutate.

---

## 3. Lua Scripts

### Return codes chung

Script Lua trả về **array 2 phần tử** `{code, reason}` để API layer phân biệt được reject reason:

| Code | Reason | Nghĩa |
|:---:|:---|:---|
| `1` | `''` | mutation mới được apply |
| `2` | `''` | idempotent hit (đã apply trước đó, không mutate lại) |
| `0` | `OUT_OF_STOCK` | hết hàng (Reserve) |
| `0` | `INSUFFICIENT_RESERVED` | reservation không đủ cho deduct |
| `0` | `RELEASE_UNDERFLOW` | release vượt quá reserved |
| `0` | `SKU_SUSPENDED` | SKU đang bị khoá do invariant drift |
| `-1` | `INVARIANT_DRIFT` | invariant drift detected (fail-safe) |

> Backend chỉ được phát event mới khi script trả `{1, ''}`. Nếu trả `{2, ''}` phải trả response idempotent và tuyệt đối không phát thêm event.

**Runbook bắt buộc khi nhận `-1`:**
1. Dừng xử lý request hiện tại ngay lập tức.
2. Không retry nóng và không auto-rollback mù quáng trên SKU lỗi.
3. Đặt Redis key `inventory:suspended:{sku_id:warehouse_id}` với TTL 24h.
4. Phát alert vận hành; chỉ mở khoá sau khi đã đối soát Redis/DB và xác nhận invariant khôi phục (qua API `POST /v1/inventory/admin/unlock-sku`).

> **Boundary quyết định:** Toàn bộ mutate nóng diễn ra trên key `inventory:physic:{sku_id:warehouse_id}:stock`. Aggregate per-SKU tính trực tiếp tại query time hoặc tại Ledger Worker emit time bằng `SUM(available_quantity) FROM physical_inventory`.

### Script 1: Reserve Inventory

**Ngữ cảnh:** `ReserveInventoryActivity` (pre-payment).

**Input:**
* `KEYS[1]`: stock hash `inventory:physic:{sku_id:warehouse_id}:stock`
* `KEYS[2]`: idempotency key reserve
* `KEYS[3]`: stream outbox key
* `KEYS[4]`: suspension key `inventory:suspended:{sku_id:warehouse_id}` (optional, kiểm tra nếu có)
* `ARGV[1]`: `quantity`
* `ARGV[2]`: `now_ms`
* `ARGV[3]`: `idemp_ttl_sec`
* `ARGV[4]`: `event_payload_json`

**Luật xử lý:**
1. Nếu `KEYS[4]` tồn tại → trả `0` với `reason = SKU_SUSPENDED` (không mutate).
2. Nếu idempotency key đã tồn tại -> trả `2`.
3. Kiểm tra `available >= quantity`; nếu không đủ -> trả `0` với `reason = OUT_OF_STOCK`.
4. Apply mutation: `available -= qty`, `reserved += qty`.
5. Set idempotency key TTL.
6. `XADD` event vào stream outbox.
7. Trả `1`.

**Pseudo Lua:**

```lua
if redis.call('EXISTS', KEYS[4]) == 1 then
  return {0, 'SKU_SUSPENDED'}
end
if redis.call('SETNX', KEYS[2], '1') == 0 then
  return {2, ''}
end
redis.call('EXPIRE', KEYS[2], tonumber(ARGV[3]))

local available = tonumber(redis.call('HGET', KEYS[1], 'available') or '0')
local qty = tonumber(ARGV[1])
if available < qty then
  redis.call('DEL', KEYS[2])
  return {0, 'OUT_OF_STOCK'}
end

redis.call('HINCRBY', KEYS[1], 'available', -qty)
redis.call('HINCRBY', KEYS[1], 'reserved', qty)
redis.call('HSET', KEYS[1], 'updated_at_ms', ARGV[2])
redis.call('XADD', KEYS[3], '*', 'event_type', 'INVENTORY_STOCK_MUTATION', 'change_type', 'ORDER_RESERVED', 'payload', ARGV[4])
return {1, ''}
```

> **Lưu ý:** Script giờ trả về array `{code, reason}` thay vì integer, để API layer phân biệt được `OUT_OF_STOCK` vs `SKU_SUSPENDED` khi reject.

### Script 2: Release / Rollback Reservation

**Ngữ cảnh:** `RollbackInventoryActivity` hoặc Order Temporal compensation.

**Input:**
* `KEYS[1]`: stock hash
* `KEYS[2]`: idempotency key release
* `KEYS[3]`: stream outbox key
* `KEYS[4]`: suspension key (optional)
* `ARGV[1]`: `quantity`
* `ARGV[2]`: `now_ms`
* `ARGV[3]`: `idemp_ttl_sec`
* `ARGV[4]`: `event_payload_json`
* `ARGV[5]`: `change_type` (`ORDER_CANCELLED` hoặc `RESERVATION_EXPIRED`)

**Luật xử lý:**
1. Nếu `KEYS[4]` tồn tại → trả `0` với `reason = SKU_SUSPENDED`.
2. Nếu idempotency key tồn tại -> trả `2`.
3. Kiểm tra `reserved >= quantity`; nếu không đủ -> trả `0` với `reason = RELEASE_UNDERFLOW`.
4. Apply mutation: `reserved -= qty`, `available += qty`.
5. Set idempotency key TTL.
6. `XADD` event `INVENTORY_STOCK_MUTATION` với `change_type` từ `ARGV[5]`.
7. Trả `1`.

**Pseudo Lua:**

```lua
if redis.call('EXISTS', KEYS[4]) == 1 then
  return {0, 'SKU_SUSPENDED'}
end
if redis.call('SETNX', KEYS[2], '1') == 0 then
  return {2, ''}
end
redis.call('EXPIRE', KEYS[2], tonumber(ARGV[3]))

local reserved = tonumber(redis.call('HGET', KEYS[1], 'reserved') or '0')
local qty = tonumber(ARGV[1])
if reserved < qty then
  redis.call('DEL', KEYS[2])
  return {0, 'RELEASE_UNDERFLOW'}
end

redis.call('HINCRBY', KEYS[1], 'reserved', -qty)
redis.call('HINCRBY', KEYS[1], 'available', qty)
redis.call('HSET', KEYS[1], 'updated_at_ms', ARGV[2])
redis.call('XADD', KEYS[3], '*', 'event_type', 'INVENTORY_STOCK_MUTATION', 'change_type', ARGV[5], 'payload', ARGV[4])
return {1, ''}
```

### Script 3: Deduct Inventory (Commit)

**Ngữ cảnh:** `DeductInventoryActivity` sau payment success.

**Input:**
* `KEYS[1]`: stock hash
* `KEYS[2]`: idempotency key deduct
* `KEYS[3]`: stream outbox key
* `KEYS[4]`: suspension key (optional)
* `ARGV[1]`: `quantity`
* `ARGV[2]`: `now_ms`
* `ARGV[3]`: `idemp_ttl_sec`
* `ARGV[4]`: `event_payload_json`

**Luật xử lý:**
1. Nếu `KEYS[4]` tồn tại → trả `0` với `reason = SKU_SUSPENDED`.
2. Nếu idempotency key tồn tại -> trả `2`.
3. Kiểm tra invariant trước mutate: `available == total - reserved`; nếu sai -> `-1`.
4. Kiểm tra `reserved >= quantity`; nếu không đủ -> `0` với `reason = INSUFFICIENT_RESERVED`.
5. Apply mutation: `reserved -= qty`, `total -= qty`, `available = total - reserved`.
6. Re-check invariant sau mutate; nếu sai -> `-1`.
7. Set idempotency key TTL.
8. `XADD` event `INVENTORY_STOCK_MUTATION` với `change_type = ORDER_DEDUCTED`.
9. Trả `1`.

**Pseudo Lua:**

```lua
if redis.call('EXISTS', KEYS[4]) == 1 then
  return {0, 'SKU_SUSPENDED'}
end
if redis.call('SETNX', KEYS[2], '1') == 0 then
  return {2, ''}
end
redis.call('EXPIRE', KEYS[2], tonumber(ARGV[3]))

local reserved = tonumber(redis.call('HGET', KEYS[1], 'reserved') or '0')
local total = tonumber(redis.call('HGET', KEYS[1], 'total') or '0')
local available = tonumber(redis.call('HGET', KEYS[1], 'available') or '0')
local qty = tonumber(ARGV[1])

if available ~= (total - reserved) then
  redis.call('DEL', KEYS[2])
  return {-1, 'INVARIANT_DRIFT'}
end

if reserved < qty then
  redis.call('DEL', KEYS[2])
  return {0, 'INSUFFICIENT_RESERVED'}
end

local new_reserved = reserved - qty
local new_total = total - qty
local new_available = new_total - new_reserved

if new_available < 0 then
  redis.call('DEL', KEYS[2])
  return {-1, 'INVARIANT_DRIFT'}
end

redis.call('HSET', KEYS[1],
  'reserved', tostring(new_reserved),
  'total', tostring(new_total),
  'available', tostring(new_available),
  'updated_at_ms', ARGV[2])

redis.call('XADD', KEYS[3], '*', 'event_type', 'INVENTORY_STOCK_MUTATION', 'change_type', 'ORDER_DEDUCTED', 'payload', ARGV[4])
return {1, ''}
```

---

## 4. Invariants bắt buộc

1. `available >= 0`
2. `reserved >= 0`
3. `total >= 0`
4. `available + reserved == total`

---

## 5. Lưu ý triển khai Redis Cluster

1. `KEYS[1]`, `KEYS[2]`, `KEYS[3]`, `KEYS[4]` phải cùng hash slot.
2. Áp dụng hash-tag thống nhất theo `{sku_id:warehouse_id}` cho stock/idemp/stream/suspended key.
3. Không đưa key global khác slot vào Lua.

Ví dụ key hợp lệ cùng slot:
* `inventory:physic:{sku-001:5}:stock`
* `inventory:idemp:reserve:{sku-001:5}:order:10001`
* `inventory:stream:{sku-001:5}:events`
* `inventory:suspended:{sku-001:5}`

---

