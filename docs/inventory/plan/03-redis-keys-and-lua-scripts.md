# Module 03: Redis Keys & Lua Scripts

> **Depends on:** M01 (Project Foundation)
> **Depended by:** M06 (Redis Atomic Ops), M09 (Relay Worker), M10 (Routing Engine)

---

## 1. Goal

Định nghĩa toàn bộ Redis schema (key patterns, types, fields, TTL) và 3 Lua scripts (Reserve / Release / Deduct) đảm bảo:

- Atomic mutation per `(sku_id, warehouse_id)`
- Idempotency theo `{op}:{sku_id}:{wh}:{order_id}`
- Invariant `available + reserved == total` được check trước và sau mutate
- SKU suspension qua hash flag
- Stream outbox `XADD` atomic với mutation
- Cluster-safe hash-tag thống nhất

> Tham chiếu: `inventory_redis_design.md` §2, §3.

---

## 2. Resource Files

```
src/main/resources/lua/
├── reserve_inventory.lua
├── release_inventory.lua
└── deduct_inventory.lua
```

> Mỗi script được load qua `LuaScriptLoader` (M06) 1 lần lúc startup, cache SHA1 qua `DefaultRedisScript`.

---

## 3. Key Patterns

### 3.1 `inventory:physic:{sku_id:warehouse_id}:stock`

| Aspect | Value |
|:---|:---|
| Type | Hash |
| Purpose | Hot-path commit source cho `(sku, warehouse)` |
| Hash-tag | `{sku_id:warehouse_id}` |
| Fields | `total`, `reserved`, `available`, `updated_at_ms` |
| TTL | None (persistent — fallback về DB nếu mất) |

Ví dụ: `inventory:physic:{sku-001:5}:stock`

```yaml
HGETALL inventory:physic:{sku-001:5}:stock
1) "total"
2) "1000"
3) "reserved"
4) "2"
5) "available"
6) "998"
7) "updated_at_ms"
8) "1717987200000"
```

### 3.2 `inventory:idemp:{op}:{sku_id:warehouse_id}:order:{order_id}`

| Aspect | Value |
|:---|:---|
| Type | String (value `"1"`) |
| Purpose | Idempotency key cho từng op |
| Hash-tag | `{sku_id:warehouse_id}` |
| TTL | 30 ngày (2_592_000s) |

3 variants:

- `inventory:idemp:reserve:{sku:wh}:order:{order_id}`
- `inventory:idemp:release:{sku:wh}:order:{order_id}`
- `inventory:idemp:deduct:{sku:wh}:order:{order_id}`

> `{op}` ∈ `{reserve, release, deduct}`. Đây là **key hot path** trong Redis (TTL 30 ngày, xem M06) — primary mechanism chống duplicate op. Cold path ở DB dùng partial UNIQUE `inventory_reservations(order_id, sku_id, warehouse_id) WHERE status='RESERVED'` (xem M02) cho reservation row và `inventory_ledger.event_id` UNIQUE cho ledger row.

### 3.3 `inventory:stream:{sku_id:warehouse_id}:events`

| Aspect | Value |
|:---|:---|
| Type | Stream (Redis 5+) |
| Purpose | Stream outbox cho event atomicity |
| Hash-tag | `{sku_id:warehouse_id}` |
| TTL | None (MAXLEN trimming tại Relay Worker — xem M09) |

Ví dụ: `inventory:stream:{sku-001:5}:events`

Mỗi entry format:

```yaml
XADD inventory:stream:{sku-001:5}:events * event_type INVENTORY_STOCK_MUTATION change_type ORDER_RESERVED payload "{...json...}"
```

### 3.4 `inventory:suspended:{sku_id:warehouse_id}`

| Aspect | Value |
|:---|:---|
| Type | Hash |
| Purpose | Cờ suspend SKU do invariant drift |
| Hash-tag | `{sku_id:warehouse_id}` |
| Fields | `lock_reason`, `locked_at`, `status` |
| TTL | 24 giờ (86_400s) |

Ví dụ: `inventory:suspended:{sku-001:5}`

```yaml
HGETALL inventory:suspended:{sku-001:5}
1) "lock_reason"
2) "INVARIANT_DRIFT"
3) "locked_at"
4) "1717987200000"
5) "status"
6) "SUSPENDED"
```

### 3.5 `route_plan:{route_plan_id}`

| Aspect | Value |
|:---|:---|
| Type | String (JSON serialized) |
| Purpose | Cache route plan fulfillment cho checkout |
| Hash-tag | KHÔNG (global key) |
| TTL | `expires_at - now` (mặc định 20 phút = 1200s) |

> Không dùng hash-tag vì route plan lookup không có yêu cầu co-location.

Ví dụ: `route_plan:7b7dddad-6f6c-4f63-a728-df0fe96b9f31`

---

## 4. Invariants

Mọi Lua script phải maintain:

```
1. available >= 0
2. reserved >= 0
3. total    >= 0
4. available + reserved == total
```

> Script Deduct check invariant **trước và sau** mutate (double-check pattern). Nếu drift → trả `{-1, 'INVARIANT_DRIFT'}`.

---

## 5. Common Return Code

Tất cả 3 script trả về **array 2 phần tử** `{code, reason}`:

| Code | Reason | Nghĩa |
|:---:|:---|:---|
| `1` | `''` | Mutation mới được apply |
| `2` | `''` | Idempotent hit (đã apply trước đó, không mutate lại) |
| `0` | `OUT_OF_STOCK` | Reserve không đủ stock |
| `0` | `INSUFFICIENT_RESERVED` | Deduct không đủ reserved |
| `0` | `RELEASE_UNDERFLOW` | Release vượt quá reserved |
| `0` | `SKU_SUSPENDED` | SKU đang bị khoá do drift |
| `-1` | `INVARIANT_DRIFT` | Invariant drift detected (fail-safe) |

> Backend chỉ phát event mới khi script trả `{1, ''}`. Khi `{2, ''}` → idempotent response, KHÔNG phát event.

---

## 6. Script 1: reserve_inventory.lua

### 6.1 Context

`ReserveInventoryActivity` (pre-payment) — giữ chỗ tồn kho, tăng `reserved`, giảm `available`, đồng thời `XADD` event `ORDER_RESERVED`.

### 6.2 Input Contract

| Slot | Key/Value | Mô tả |
|:---|:---|:---|
| `KEYS[1]` | `inventory:physic:{sku:wh}:stock` | Stock hash |
| `KEYS[2]` | `inventory:idemp:reserve:{sku:wh}:order:{order_id}` | Idempotency key |
| `KEYS[3]` | `inventory:stream:{sku:wh}:events` | Stream outbox |
| `KEYS[4]` | `inventory:suspended:{sku:wh}` | Suspension flag (optional check) |
| `ARGV[1]` | `quantity` | Số lượng reserve (int) |
| `ARGV[2]` | `now_ms` | Epoch ms hiện tại |
| `ARGV[3]` | `idemp_ttl_sec` | TTL cho idempotency key (mặc định 2592000 = 30 ngày) |
| `ARGV[4]` | `event_payload_json` | JSON payload cho event `INVENTORY_STOCK_MUTATION` |

> 4 KEYS **bắt buộc cùng hash slot** — hash-tag `{sku:wh}` thống nhất.

### 6.3 Logic

```lua
-- reserve_inventory.lua

-- 1. Check suspension flag
if redis.call('EXISTS', KEYS[4]) == 1 then
  return {0, 'SKU_SUSPENDED'}
end

-- 2. Idempotency check (atomic SETNX)
if redis.call('SETNX', KEYS[2], '1') == 0 then
  return {2, ''}
end
redis.call('EXPIRE', KEYS[2], tonumber(ARGV[3]))

-- 3. Read current available stock
local available = tonumber(redis.call('HGET', KEYS[1], 'available') or '0')
local qty = tonumber(ARGV[1])

-- 4. Stock sufficiency check
if available < qty then
  redis.call('DEL', KEYS[2])  -- rollback idempotency key
  return {0, 'OUT_OF_STOCK'}
end

-- 5. Apply mutation
redis.call('HINCRBY', KEYS[1], 'available', -qty)
redis.call('HINCRBY', KEYS[1], 'reserved',  qty)
redis.call('HSET',     KEYS[1], 'updated_at_ms', ARGV[2])

-- 6. Emit event to stream outbox (atomic with mutation)
redis.call('XADD', KEYS[3], '*',
  'event_type',  'INVENTORY_STOCK_MUTATION',
  'change_type', 'ORDER_RESERVED',
  'payload',     ARGV[4]
)

return {1, ''}
```

### 6.4 Notes

- `SETNX` + `EXPIRE` atomicity: nếu SETNX thất bại (key tồn tại) → return idempotent, KHÔNG `EXPIRE`. Nếu SETNX thành công → `EXPIRE` luôn trong cùng script execution.
- Nếu fail ở bước 4 (stock không đủ), `DEL KEYS[2]` để idempotency key không block retry của caller. Caller vẫn nhận `OUT_OF_STOCK` và tự quyết định retry.
- Event payload được build **trước khi** gọi Lua tại Java layer, dưới dạng JSON string. Format xem M07.

---

## 7. Script 2: release_inventory.lua

### 7.1 Context

`RollbackInventoryActivity` (Temporal compensation) — giải phóng reservation, giảm `reserved`, tăng `available`, đồng thời `XADD` event với `change_type` ∈ `{ORDER_CANCELLED, RESERVATION_EXPIRED}`.

### 7.2 Input Contract

| Slot | Key/Value | Mô tả |
|:---|:---|:---|
| `KEYS[1]` | `inventory:physic:{sku:wh}:stock` | Stock hash |
| `KEYS[2]` | `inventory:idemp:release:{sku:wh}:order:{order_id}` | Idempotency key |
| `KEYS[3]` | `inventory:stream:{sku:wh}:events` | Stream outbox |
| `KEYS[4]` | `inventory:suspended:{sku:wh}` | Suspension flag (optional) |
| `ARGV[1]` | `quantity` | Số lượng release (int) |
| `ARGV[2]` | `now_ms` | Epoch ms hiện tại |
| `ARGV[3]` | `idemp_ttl_sec` | TTL idempotency key |
| `ARGV[4]` | `event_payload_json` | JSON payload |
| `ARGV[5]` | `change_type` | `ORDER_CANCELLED` hoặc `RESERVATION_EXPIRED` |

### 7.3 Logic

```lua
-- release_inventory.lua

-- 1. Check suspension flag
if redis.call('EXISTS', KEYS[4]) == 1 then
  return {0, 'SKU_SUSPENDED'}
end

-- 2. Idempotency check
if redis.call('SETNX', KEYS[2], '1') == 0 then
  return {2, ''}
end
redis.call('EXPIRE', KEYS[2], tonumber(ARGV[3]))

-- 3. Read current reserved
local reserved = tonumber(redis.call('HGET', KEYS[1], 'reserved') or '0')
local qty = tonumber(ARGV[1])

-- 4. Underflow check
if reserved < qty then
  redis.call('DEL', KEYS[2])
  return {0, 'RELEASE_UNDERFLOW'}
end

-- 5. Apply mutation (reserved -= qty, available += qty)
redis.call('HINCRBY', KEYS[1], 'reserved',  -qty)
redis.call('HINCRBY', KEYS[1], 'available',  qty)
redis.call('HSET',     KEYS[1], 'updated_at_ms', ARGV[2])

-- 6. Emit event with specified change_type
redis.call('XADD', KEYS[3], '*',
  'event_type',  'INVENTORY_STOCK_MUTATION',
  'change_type', ARGV[5],
  'payload',     ARGV[4]
)

return {1, ''}
```

### 7.4 Notes

- `change_type` được truyền qua `ARGV[5]` để cùng script phục vụ cả `ORDER_CANCELLED` (buyer cancel) và `RESERVATION_EXPIRED` (Temporal timer fire).
- KHÔNG check invariant `available + reserved == total` trước/sau mutate — release chỉ là inverse của reserve, nếu reserve đúng thì release đúng. Drift chỉ xảy ra nếu Redis cluster mất dữ liệu → lúc đó admin phải manually reconcile.

---

## 8. Script 3: deduct_inventory.lua

### 8.1 Context

`DeductInventoryActivity` (post-payment) — xác nhận bán đứt, giảm `reserved`, giảm `total`, recompute `available`. **Double-check invariant** do đây là mutation trừ `total` (chứ không chỉ tách available/reserved).

### 8.2 Input Contract

| Slot | Key/Value | Mô tả |
|:---|:---|:---|
| `KEYS[1]` | `inventory:physic:{sku:wh}:stock` | Stock hash |
| `KEYS[2]` | `inventory:idemp:deduct:{sku:wh}:order:{order_id}` | Idempotency key |
| `KEYS[3]` | `inventory:stream:{sku:wh}:events` | Stream outbox |
| `KEYS[4]` | `inventory:suspended:{sku:wh}` | Suspension flag (optional) |
| `ARGV[1]` | `quantity` | Số lượng deduct (int) |
| `ARGV[2]` | `now_ms` | Epoch ms hiện tại |
| `ARGV[3]` | `idemp_ttl_sec` | TTL idempotency key |
| `ARGV[4]` | `event_payload_json` | JSON payload |

### 8.3 Logic

```lua
-- deduct_inventory.lua

-- 1. Check suspension flag
if redis.call('EXISTS', KEYS[4]) == 1 then
  return {0, 'SKU_SUSPENDED'}
end

-- 2. Idempotency check
if redis.call('SETNX', KEYS[2], '1') == 0 then
  return {2, ''}
end
redis.call('EXPIRE', KEYS[2], tonumber(ARGV[3]))

-- 3. Read current state
local reserved  = tonumber(redis.call('HGET', KEYS[1], 'reserved') or '0')
local total     = tonumber(redis.call('HGET', KEYS[1], 'total')    or '0')
local available = tonumber(redis.call('HGET', KEYS[1], 'available') or '0')
local qty = tonumber(ARGV[1])

-- 4. PRE-MUTATE INVARIANT CHECK
if available ~= (total - reserved) then
  redis.call('DEL', KEYS[2])
  return {-1, 'INVARIANT_DRIFT'}
end

-- 5. Reserved sufficiency check
if reserved < qty then
  redis.call('DEL', KEYS[2])
  return {0, 'INSUFFICIENT_RESERVED'}
end

-- 6. Compute new state
local new_reserved  = reserved - qty
local new_total     = total - qty
local new_available = new_total - new_reserved

-- 7. POST-MUTATE INVARIANT CHECK (new_available cannot be negative)
if new_available < 0 then
  redis.call('DEL', KEYS[2])
  return {-1, 'INVARIANT_DRIFT'}
end

-- 8. Apply mutation (full HSET for atomicity)
redis.call('HSET', KEYS[1],
  'reserved',      tostring(new_reserved),
  'total',         tostring(new_total),
  'available',     tostring(new_available),
  'updated_at_ms', ARGV[2]
)

-- 9. Emit event
redis.call('XADD', KEYS[3], '*',
  'event_type',  'INVENTORY_STOCK_MUTATION',
  'change_type', 'ORDER_DEDUCTED',
  'payload',     ARGV[4]
)

return {1, ''}
```

### 8.4 Notes

- **Double invariant check** là cốt lõi: nếu Redis cluster bị split-brain hoặc có manual edit sai → script sẽ phát hiện và fail-safe.
- `HSET` thay vì `HINCRBY` từng field để đảm bảo 3 fields được set cùng lúc.
- Drift response `{-1, 'INVARIANT_DRIFT'}` → Java layer (M11) set suspend flag và fail activity. Admin phải manually reconcile qua `POST /v1/inventory/admin/unlock-sku` (M12, M13).

---

## 9. Cluster Hash-Tag Invariant

Tất cả 4 KEYS trong mỗi Lua execution **phải cùng hash slot** để Redis Cluster cho phép gọi. Hash-tag thống nhất:

```
{sku_id:warehouse_id}
```

### 9.1 Ví dụ keys cùng slot

```
inventory:physic:{sku-001:5}:stock
inventory:idemp:reserve:{sku-001:5}:order:10001
inventory:idemp:release:{sku-001:5}:order:10001
inventory:idemp:deduct:{sku-001:5}:order:10001
inventory:stream:{sku-001:5}:events
inventory:suspended:{sku-001:5}
```

→ Tất cả hash về slot hash(`{sku-001:5}`) → cùng node → cluster OK.

### 9.2 Build key tập trung

Mọi key construction đi qua `RedisKeyBuilder` (M06) để đảm bảo hash-tag đúng format. Java code **không** tự `String.format(...)` key.

### 9.3 Anti-pattern (KHÔNG được làm)

```java
// ❌ Sai — không có hash-tag → có thể rơi vào khác slot
String key = "inventory:physic:" + sku + ":" + warehouse + ":stock";

// ❌ Sai — hash-tag khác nhau giữa các key trong cùng Lua
String stockKey = "inventory:physic:{" + sku + ":" + warehouse + "}:stock";
String idempKey = "inventory:idemp:reserve:{" + sku + "}:" + warehouse + ":order:" + orderId;
//                         ^^^^^ hash-tag chỉ chứa sku, không chứa warehouse → khác slot
```

---

## 10. Event Payload Format (ARGV[4])

JSON payload truyền vào `XADD`. Cấu trúc chung:

```json
{
  "event_id":      "uuid",
  "sku_id":        "string",
  "product_id":    "string",
  "warehouse_id":  "long",
  "change_type":   "ORDER_RESERVED | ORDER_DEDUCTED | ORDER_CANCELLED | RESERVATION_EXPIRED",
  "quantity":      "int",
  "available_after":  "int",   // per-warehouse, AFTER mutation
  "reserved_after":   "int",   // per-warehouse, AFTER mutation
  "order_id":      "long",
  "order_item_id": "long",
  "correlation_id":"string"
}
```

> Payload build tại Java layer (M07) **trước khi** gọi Lua. Lua chỉ nhận serialized JSON string và forward vào stream.

---

## 11. Runbook cho `INVARIANT_DRIFT`

Khi nhận `{-1, 'INVARIANT_DRIFT'}`:

1. **Dừng** request hiện tại ngay lập tức (Java layer throw exception + fail Temporal Activity).
2. **KHÔNG** retry nóng và **KHÔNG** auto-rollback mù quáng.
3. **Set suspend flag**: `HSET inventory:suspended:{sku:wh} lock_reason "INVARIANT_DRIFT" locked_at <now_ms> status "SUSPENDED"` + `EXPIRE 86400`.
4. **Phát alert** (qua Micrometer counter + log ERROR).
5. **Manual unlock** chỉ qua `POST /v1/inventory/admin/unlock-sku` sau khi admin đối soát Redis/DB.

> Implementation chi tiết tại M11 (Temporal Activity) và M12 (Admin UnlockSkuService).

---

## 12. Verification

| Check | Cách verify |
|:---|:---|
| Lua scripts parse OK | Redis CLI: `SCRIPT LOAD` → trả SHA1 không lỗi |
| Hash tag cùng slot | `CLUSTER KEYSLOT inventory:physic:{sku-001:5}:stock` == `CLUSTER KEYSLOT inventory:stream:{sku-001:5}:events` |
| Return code đúng | Chạy script qua redis-cli với input đầy đủ, assert `{1, ''}` / `{0, 'OUT_OF_STOCK'}` etc. |
| Idempotency | Gọi script 2 lần liên tiếp cùng KEYS[2] → lần 2 trả `{2, ''}` |
| Invariant | Set stock hash manually sai invariant → gọi deduct → trả `{-1, 'INVARIANT_DRIFT'}` |
| Suspension | Set suspend flag → gọi script bất kỳ → trả `{0, 'SKU_SUSPENDED'}` |

---

## 13. Files Created

```
src/main/resources/lua/
├── reserve_inventory.lua
├── release_inventory.lua
└── deduct_inventory.lua
```