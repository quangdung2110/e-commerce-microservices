# Product Service - Danh sách Background Workers & Jobs

**Liên kết nhanh (Related Documents):**
*   [Workflows (API & Data Flow)](product_workflow.md)
*   [Background Workers & Jobs](product_workers.md)
*   [Caching Schema (Redis)](product_caching_schema.md)
*   [Database Schema (Write Store)](product_db_design.md)
*   [State Machine & Lifecycle](product_state_machine.md)
*   [Events Structure & Kafka Topics](product_events.md)
*   [Architecture Recommendations](product_architecture_recommendations.md)

---
Tại Product Service, dữ liệu là tĩnh (Command Model / Master Data) nên Service không sử dụng Redis làm cơ sở dữ liệu chính như Cart Service. Hầu hết các Workers trong Product Service đóng vai trò phục vụ việc **Đồng bộ Dữ liệu Phân Tán (Saga / CQRS)** để đảm bảo các hệ thống xung quanh như Search Service, Notification, Promotion nhận được tín hiệu cập nhật đúng thời điểm.

Dưới đây là danh sách Worker nội bộ thuộc vòng tay quản lý của cụm Máy Chủ Product Service.

---

## Tổng Hợp Kafka Topics

||| Hướng | Topic | Consumer Group | Mô tả |
||---|---|---|---|
||| **Produce** | `t.product.events` | - | Topic chính chứa mọi event phát đi |
||| **Produce** | `t.category.events` | - | Topic category lifecycle events |
||| **Consume** | `t.product.events` | `product-event-handler-group` | **Consume** | `t.product.events` | `product-event-handler-group` | Self-consume `SKU_CREATED`, `SKU_PRICE_UPDATED`, `SKU_STATUS_CHANGED` để refresh `product:sku:{sku_id}`. `SKU_CREATED` do Admin thêm SKU (endpoint 1.7); `SKU_PRICE_UPDATED` do Markdown Worker; `SKU_STATUS_CHANGED` do Inventory Sync Phase 2. Xem [Event Handler Worker](product_workers.md#2-event-handler-worker) |
| **Consume** | `t.category.events` | `product-event-handler-group` | Self-consume `CATEGORY_DELETED` để xử lý Write Model (chuyển sản phẩm liên quan sang `HIDDEN`) |
||| **Consume** | `t.promotion.markdown.events` | `product-markdown-group` | Nhận đúng allowlist `MARKDOWN_CREATED`, `MARKDOWN_UPDATED`, `MARKDOWN_ENDED`; event lạ phải skip + metric + DLQ |
||| **Consume** | `t.inventory.events` | `product-inventory-fast-sync-group` | Phase 1: Lock-Free Fast Sync (Kafka → Redis). Xem [Two-Phase Architecture](inventory_sync_optimization.md) |

> **Thay đổi:** `product-event-handler-group` subscribe 2 topic: `t.product.events` (riêng `SKU_CREATED`, `SKU_PRICE_UPDATED`, `SKU_STATUS_CHANGED`) và `t.category.events` (riêng `CATEGORY_DELETED`). Các event khác (PRODUCT_CREATED, PRODUCT_STATUS_CHANGED, PRODUCT_DELETED, PRODUCT_UPDATED) đã được xử lý In-band trong API. Xem chi tiết tại [Event Handler Worker](product_workers.md#2-event-handler-worker).

Xem cấu trúc payload chi tiết cho tất cả events tại [Product Events](product_events.md).

---

## 1. Debezium CDC Connector (Thay thế hoàn toàn Polling Worker)

**Loại hình:** Kafka Connect Source Connector (Đọc Real-time dưới tầng Đĩa cứng).

**Sứ mệnh:** Chuyển Event đã sinh ra từ bảng `product_outbox_events` sang Kafka Topic (`t.product.events` và `t.category.events`) với độ trễ < 5ms thông qua luồng bắt Read-Log (Write-Ahead-Log). Thay thế hoàn toàn vòng lặp CPU SELECT tốn kém. Xem [API Endpoints](product_endpoints.md) để biết chi tiết các API sinh outbox.

**Chi tiết luồng hoạt động:**

1. Khi có tạo mới/sửa đổi API, nó đồng thời Insert Record vào bảng Outbox (với `event_id` là Khóa chính (UUID)). DB cập nhật WAL dưới ổ cứng.
2. Debezium Connector gắn qua cổng Logical_Replication_Slot của Postgres tiến hành đọc stream từ đĩa. Hành vi này không chiếm dữ liệu 1 chút tài nguyên CPU/Connection Pool nào của Request người dùng đập vào DB.
3. **[CRITICAL Kafka Routing]:** Debezium được cấu hình để trích xuất trực tiếp giá trị từ cột `partition_key` (đã được lưu sẵn `product_id`) làm Kafka Message Key. Điều này ghim chặt tất cả Event thay đổi của cùng 1 Sản phẩm sẽ rơi vào chung một Kafka Partition, phụ thuộc hoàn hảo nguyên tắc CQRS cho Search Service (không được dùng SMT bóc tách JSON ở cột payload vì rủi ro Crash và giảm hiệu năng).
4. Debezium đọc bản ghi và Connector tự động định dạng thành CloudEvents và phát lên Topic Kafka mong muốn.
5. **[Dead Letter Queue - DLQ]:** Mọi cấu hình DLQ, Retry, Error Handling được thực hiện trực tiếp tại Kafka Connect (Sink/Source Dead Letter Topic). Table outbox được dọn dẹp định kỳ bằng retention job theo cửa sổ thời gian (`created_at`).

## 2. Event Handler Worker (Category Lifecycle & Markdown Cache Refresh)

**Loại hình:** Kafka Consumer lắng nghe Topic của chính nó.

**Sứ mệnh:** Xử lý Category Lifecycle thông qua Worker (Out-of-band). Đây là **cơ chế bắt buộc** vì không có worker nào khác phụ trách luồng này.

**Tại sao chỉ giữ Category Lifecycle?**

Cache refresh cho sản phẩm (PRODUCT_STATUS_CHANGED, PRODUCT_DELETED, PRODUCT_UPDATED) đã được xử lý **In-band** ngay trong API trước khi trả HTTP response, đảm bảo Read-Your-Own-Writes. Worker này giữ lại 4 nhiệm vụ bắt buộc:

1. **CATEGORY_DELETED**: Xử lý Write Model — đổi các sản phẩm liên quan sang `HIDDEN` tại PostgreSQL (không phát thêm `PRODUCT_STATUS_CHANGED`).
2. **SKU_CREATED**: Refresh `product:sku:{sku_id}` vì endpoint 1.7 đã ghi cache In-band nhưng Event Handler vẫn cần handle trường hợp cache miss hoặc self-heal.
3. **SKU_PRICE_UPDATED (từ Markdown)**: Refresh `product:sku:{sku_id}` vì Markdown Worker không ghi trực tiếp key này (tránh double-write).
4. **SKU_STATUS_CHANGED (từ Inventory Phase 2)**: Refresh `product:sku:{sku_id}` sau khi Inventory Sync Phase 2 commit DB thành công, đồng bộ `sku_status` mới vào Redis cache.

**Chi tiết luồng hoạt động:**

1. Worker Kafka (`groupId="product-event-handler-group"`) lắng nghe Topic `t.category.events` và `t.product.events`.
2. Mỗi vòng `poll()` lấy một `poll-batch` records, chuẩn hóa CloudEvents trước khi xử lý.
3. Xử lý theo `event_type`:

### 2.1. CATEGORY_DELETED (Bắt buộc — Write Model)

```
Payload nhận được:
{
  "event_type": "CATEGORY_DELETED",
  "payload": {
    "category_id": "cat_001",
    "descendant_category_ids": ["cat_001_a", "cat_001_b"]
  }
}

Luồng xử lý:
1. Validate category_id tồn tại
2. Xác định phạm vi: xử lý cả subtree nếu có descendant_category_ids,
   tối thiểu xử lý category_id gốc
3. Query PostgreSQL: SELECT product_id FROM products WHERE category_id IN (...)
   AND is_deleted = false AND status = 'ACTIVE'
4. Batch UPDATE: status = 'HIDDEN' (batch nhỏ 100 product/batch,
   transaction-per-batch)
5. COMMIT → Search Service tự nhận CATEGORY_DELETED từ Kafka
   và update cả category metadata lẫn product_status trên Elasticsearch
```

- Mỗi batch là 1 transaction riêng để tránh giữ lock quá lâu.
- Trước khi UPDATE, bắt buộc lock product cha `FOR NO KEY UPDATE`
  để tránh deadlock với Admin API.
- Worker **CHỈ ĐƯỢC PHÉP** thao tác read từ Database và write vào Database.
  TUYỆT ĐỐI KHÔNG gọi Redis trong luồng CATEGORY_DELETED.

### 2.2. SKU_CREATED (Cache Refresh)

```
Payload nhận được:
{
  "event_type": "SKU_CREATED",
  "payload": {
    "product_id": "prod-001",
    "sku_id": "sku-003",
    "sku_code": "IP15PM-256-WHT",
    "original_price": 34990000,
    "promotional_price": null,
    "best_price": 34990000,
    "has_promotion": false,
    "sku_status": "ACTIVE",
    "product_status": "DRAFT",
    "variant_attributes_json": {"color": "Trắng", "storage": "256GB"},
    "created_at": "2026-06-10T21:00:00Z",
    "updated_at": "2026-06-10T21:00:00Z"
  }
}

Luồng xử lý:
1. Parse payload: lấy sku_id, product_id từ payload
2. Query DB: SELECT * FROM product_skus ps JOIN products p
   ON ps.product_id = p.product_id WHERE ps.sku_id = ?
   để lấy snapshot đầy đủ (name, image, variant_attributes, inventory...)
3. Serialize: build JSON object product:sku:{sku_id}
4. Redis JSON.SET với TTL 3600s
5. Dedupe theo product_id trong poll-batch để giảm write amplification
```

- **Staleness guard bắt buộc:** so sánh `event.created_at` với `cached.created_at` — event bằng hoặc cũ hơn phải skip. Field `created_at` được lưu trong cache key `product:sku:{sku_id}` để phân biệt với các timestamp khác.
- **Ownership:** Endpoint 1.7 đã ghi cache In-band, nhưng Event Handler vẫn xử lý trường hợp cache miss (cache bị eviction trước khi Search nhận event) hoặc self-heal scenario.

### 2.3. SKU_PRICE_UPDATED (từ Markdown — Cache Refresh)

```
Payload nhận được:
{
  "event_type": "SKU_PRICE_UPDATED",
  "payload": {
    "product_id": "prod-001",
    "sku_id": "sku-001",
    "original_price": 34990000,
    "promotional_price": 28891500,
    "best_price": 28891500,
    "has_promotion": true,
    "updated_at": "2026-02-28T06:00:00Z",
  }
}

Luồng xử lý:
1. Parse payload: lấy sku_id, product_id, giá từ payload
2. Query DB: SELECT * FROM product_skus WHERE sku_id = ? để lấy
   snapshot đầy đủ (name, image, variant_attributes, inventory...)
3. Serialize: build JSON object product:sku:{sku_id}
4. Redis JSON.SET với TTL 1 tiếng
5. Dedupe theo product_id trong poll-batch (nếu nhiều SKU cùng product)
   để giảm write amplification
```

- **Staleness guard bắt buộc:** so sánh `event.updated_at` với `cached.price_updated_at` — event bằng hoặc cũ hơn phải skip. Field `price_updated_at` được lưu trong cache key `product:sku:{sku_id}` để phân biệt rõ ràng với inventory sync timestamp. Nếu Redis GET trả null (cache miss), vẫn query DB và nạp cache bình thường.

**Quy tắc ownership:** Với luồng giá Markdown, `SKU_PRICE_UPDATED` là tín hiệu chuẩn để Event Handler ghi đè `product:sku:{sku_id}`. Markdown Worker không được ghi đè key này trực tiếp.

### 2.4. SKU_STATUS_CHANGED (từ Inventory Phase 2 — Cache Refresh)

```
Payload nhận được:
{
  "event_type": "SKU_STATUS_CHANGED",
  "payload": {
    "product_id": "prod-001",
    "sku_id": "sku-001",
    "sku_ids": ["sku-001", "sku-002"],
    "new_sku_status": "OUT_OF_STOCK",
    "product_status_changed": true,
    "new_product_status": "HIDDEN",
    "updated_at": "2026-02-28T09:00:00Z"
  }
}

Luồng xử lý:
1. Parse payload: lấy sku_id/product_id, sku_status từ payload
2. Query DB: SELECT * FROM product_skus WHERE sku_id = ? để lấy
   snapshot đầy đủ (name, image, variant_attributes, inventory...)
3. Serialize: build JSON object product:sku:{sku_id} với sku_status mới
4. Redis JSON.SET với TTL 1 tiếng
5. Dedupe theo product_id trong poll-batch để giảm write amplification
```

- **Staleness guard bắt buộc:** so sánh `event.updated_at` với `cached.last_inventory_sync_at` — event bằng hoặc cũ hơn phải skip. Nếu Redis GET trả null (cache miss), vẫn query DB và nạp cache bình thường.
- **Quy tắc ownership:** Phase 2 Inventory Worker phát `SKU_STATUS_CHANGED` sau DB commit. Event Handler refresh cache từ event này. Phase 1 Lua KHÔNG phát event, chỉ ghi Redis cache.

### 2.5. Execution Contract (Offset / Retry)

- **Offset commit**: chỉ commit offset sau khi record đã xử lý xong:
  (a) ghi cache / ghi DB thành công, hoặc (b) đã đưa sang DLQ theo nhánh non-retryable.
- **Retry transient**: lỗi Redis timeout, lỗi DB tạm thời, lỗi network tạm thời
  -> không commit offset, retry với exponential backoff + jitter (tối đa 5 lần).

**Cảnh báo lỗi Circular Dependency (Loop Vô Tận):**

Worker này **CHỈ ĐƯỢC PHÉP** thao tác:
- CATEGORY_DELETED: read từ Database + write vào Database.
- SKU_PRICE_UPDATED: read từ Database + write vào Redis.
- SKU_STATUS_CHANGED: read từ Database + write vào Redis (cache refresh).

TUYỆT ĐỐI KHÔNG gọi bất kỳ hàm Business Logic hay Entity Save (Write Database)
nào trong luồng SKU_PRICE_UPDATED hoặc SKU_STATUS_CHANGED. Nếu gọi nhầm, nó sẽ lưu Database
-> sinh ra Outbox Event -> ra Kafka Message -> tự Worker nhận lại -> Vòng lặp hủy diệt.

## 3. Outbox Retention Trimmer (Time-Window Cleanup Cronjob)

**Loại hình:** Job dọn dẹp chạy lúc ít người dùng nhất (Ví dụ 3:00 Sáng Chủ Nhật).

**Sứ mệnh:** Dữ liệu trong bảng `product_outbox_events` là log append-only phục vụ Debezium CDC. Cần dọn dữ liệu cụ thể theo retention window để tránh phình dung lượng đĩa.

**Chi tiết luồng hoạt động (Time-Window Cleanup):**

1. Hệ thống Wake-up.
2. Dọn dẹp theo lô nhỏ theo `created_at` để hạn chế lock dài. Không dùng lệnh `DELETE FROM ...` toàn bảng.

```sql
WITH to_delete AS (
  SELECT event_id
  FROM product_outbox_events
  WHERE created_at < NOW() - INTERVAL '30 days'
  ORDER BY created_at ASC
  LIMIT 10000
)
DELETE FROM product_outbox_events poe
USING to_delete
WHERE poe.event_id = to_delete.event_id;
```

3. Lưu ý: Vì Product dùng Debezium CDC từ WAL (không dùng outbox polling trạng thái `FAILED/DEAD`), retention được quản trị theo age của record và trạng thái Kafka Connect offset.

## 4. Markdown Price Calculator Worker

**Loại hình:** Kafka Consumer lắng nghe Topic `t.promotion.markdown.events`.

**Sứ mệnh:** Nhận event Markdown (Giảm giá trực tiếp) từ Promotion Service.

**Chi tiết luồng hoạt động:**

1. Worker (`groupId="product-markdown-group"`) nhận event từ `t.promotion.markdown.events`.
2. Phân loại event_type (`MARKDOWN_CREATED`, `MARKDOWN_UPDATED`, `MARKDOWN_ENDED`).
   - Truy xuất danh sách SKU bị ảnh hưởng dưới dạng danh sách phẳng có trộn nhiều `product_id`.
   - **Group tại tầng Application:** Worker phải gom theo `product_id` (map trong RAM hoặc iterator grouping) để tạo các cụm xử lý độc lập theo Product. Với tập dữ liệu lớn, ưu tiên streaming/chunk theo `ORDER BY product_id ASC, sku_id ASC`, tránh giữ toàn bộ map quá lớn trên RAM.
3. **Transaction per Product + Batch DB Write:**
   - **CHỐNG DEADLOCK HÀNG LOẠT (BATCH DEADLOCK):** BẮT BUỘC chỉ được mở `@Transactional` cho **TỪNG `product_id`** một (Transaction Size = 1 Product). CẤM mở 1 Transaction bọc cả ngàn sản phẩm.
   - Bên trong mỗi Transaction con (trên từng product):
   - **CHỐNG VA CHẠM GIỮA NHIỀU INSTANCE WORKER:** Bước đầu tiên trong transaction phải lock Product cha bằng `FOR NO KEY UPDATE` với `lock_timeout` ngắn. Nếu lock timeout, worker `nack/requeue` theo policy retry để tránh xung đột âm thầm.
   - Bước 1 - Lock Cha: `SELECT 1 FROM products WHERE product_id = :product_id FOR NO KEY UPDATE;`
   - Bước 2 - Lock Con: `SELECT sku_id, original_price FROM product_skus WHERE sku_id IN (...) AND product_id = :product_id FOR UPDATE;`. Khi nhận `MARKDOWN_UPDATED`, biến `original_price` dùng cho phép tính `%` giảm giá BẮT BUỘC phải lấy từ DB hiện tại (kết quả query này), không được lấy từ `original_price` cũ.
   - Bước 2.1 - Công thức giá chuẩn (bắt buộc):
     - `PERCENTAGE`: `discount_amount = MIN(original_price * discount_value / 100, max_discount_amount)` (nếu có cap), `promotional_price = MAX(0, ROUND(original_price - discount_amount, 2))`.
     - `FIXED_AMOUNT`: `promotional_price = MAX(0, ROUND(original_price - discount_value, 2))`.
   - Bước 3 - Batch update `product_skus` (giá markdown), batch upsert `product_markdown_applied`, batch insert `product_outbox_events` ngay trong cùng transaction.
   - Bước 4 - Insert outbox [`SKU_PRICE_UPDATED`](product_events.md#33-SKU_PRICE_UPDATED) cho các SKU bị ảnh hưởng.
4. **Cache Update sau Commit (Redis Pipeline - Ownership rõ ràng):**
   - Sau mỗi lần COMMIT của từng Product, Markdown Worker KHÔNG ghi đè trực tiếp `product:sku:{sku_id}` để tránh trùng lặp với Event Handler.
   - Làm mới `product:sku:{sku_id}` được giao cho Event Handler khi self-consume `SKU_PRICE_UPDATED` từ `t.product.events`.
   - Markdown Worker chỉ quản lý key phụ `product:markdown:active:{sku_id}` bằng pipeline theo batch để phản ánh rule markdown đang active.
   - Nếu nhận `MARKDOWN_ENDED` do Admin tắt thủ công (`reason = ADMIN_DISABLED`), phải `DEL product:markdown:active:{sku_id}` cho SKU bị ảnh hưởng, và nên đưa các lệnh `DEL` này vào cùng pipeline batch.

> **No-op bắt buộc cho `MARKDOWN_ENDED`:** nếu query theo `rule_id` không trả về SKU nào, worker phải ACK và kết thúc (không coi là lỗi, không phát outbox rỗng).

## 5. Inventory Sync Worker (Two-Phase Lock-Free Architecture)

> **Kiến trúc mới:** Worker này đã được tối ưu hóa với **Two-Phase Lock-Free Architecture** thay thế Pessimistic Locking. Xem chi tiết tại [Inventory Sync Optimization](inventory_sync_optimization.md).

**Loại hình:** Gồm 2 Worker chạy song song:
1. **Phase 1** — Kafka Consumer (Lock-Free Fast Path)
2. **Phase 2** — Scheduled Worker (Async DB Sync)

**Vấn đề với thiết kế Pessimistic Locking cũ:**
- Mỗi event = 1 transaction = 2 locks (Product + SKU) = hot SKU contention cực cao
- Với SKU bán chạy nhận hàng trăm event/giây → latency tăng phi tuyến tính
- Deadlock AB-BA với Admin API khi cùng lock product theo thứ tự ngược nhau
- Lock hold time lâu vì auto-hide/reactivate logic trong cùng transaction

**Giải pháp Two-Phase:**

```
Phase 1 (Fast Path): Kafka Event → Lua CAS (Redis) → Update Cache + ZADD pending
  Latency: < 5ms | Zero DB lock | Immediate cache warm

Phase 2 (Slow Path): Drain ZSET → Batch UPDATE → FSM triggers → Outbox
  Latency: 500ms-2s | Batch Optimistic Locking | Eventual DB sync
```

### 5.1. Phase 1: Inventory Fast-Sync Consumer (Kafka → Redis)

**Sứ mệnh:** Nhận event từ Kafka, cập nhật Redis cache ngay lập tức mà KHÔNG đụng đến PostgreSQL.

**Consumer Group:** `product-inventory-fast-sync-group`

**Chi tiết luồng hoạt động:**

1. **Kafka Consumer** nhận `INVENTORY_QUANTITY_CHANGED` event.
2. **Parse payload** bắt buộc: `product_id`, `sku_id`, `available_quantity`, `event_timestamp`.
3. **Lua CAS Atomic Update** trên Redis:
   - Thực thi Lua script để atomic compare-and-swap trên key `product:sku:{sku_id}`.
   - **Staleness guard**: chỉ apply nếu `event_timestamp > cached.last_inventory_sync_at`.
   - Atomic write: cập nhật `display_quantity`, `sku_status`, `last_inventory_sync_at`, `is_available`.
   - **Preserve existing fields**: giữ nguyên các trường không thuộc inventory (price, name, image...).
4. **Redis Pipelining**: ghi cache + ZADD pending sync trong cùng 1 round-trip.
5. **Kafka ACK** offset ngay lập tức sau khi Redis write thành công.

**Lua Script Core Logic:**

```lua
-- inventory_fast_sync.lua
-- KEYS[1] = product:sku:{sku_id}
-- KEYS[2] = product:inventory:pending  (Global ZSET)
-- KEYS[3] = member "{sku_id}:{event_timestamp}"
-- ARGV[1] = event_timestamp (epoch millis)
-- ARGV[2] = available_quantity
-- ARGV[3] = product_id
-- ARGV[4] = sku_status
-- Returns: 1 = applied, 0 = stale skipped, -1 = upsert (new key)

local cacheKey = KEYS[1]
local pendingZSet = KEYS[2]
local pendingMember = KEYS[3]
local eventTimestamp = tonumber(ARGV[1])
local availableQty = tonumber(ARGV[2])
local productId = ARGV[3]
local skuStatus = ARGV[4]

local existingJson = redis.call('JSON.GET', cacheKey)
local cachedTs = 0
if existingJson then
    local d = cjson.decode(existingJson)
    cachedTs = tonumber(d.last_inventory_sync_at) or 0
end

-- Out-of-order guard
if cachedTs > 0 and eventTimestamp <= cachedTs then
    return 0  -- stale event, skip
end

-- Determine SKU status based on quantity
local newSkuStatus = skuStatus
if availableQty == 0 then
    newSkuStatus = 'OUT_OF_STOCK'
elseif availableQty > 0 then
    -- Chỉ auto-reactivate nếu SKU đang OUT_OF_STOCK. INACTIVE do admin set không tự động nhảy.
    if newSkuStatus == 'OUT_OF_STOCK' then
        newSkuStatus = 'ACTIVE'
    end
    -- Nếu là INACTIVE (admin chủ động deactivate), giữ nguyên INACTIVE.
end

-- Build new cache (preserve non-inventory fields)
-- CRITICAL: product_status is PRESERVED from existing cache, NOT from event payload.
-- product_status can only change via PRODUCT_STATUS_CHANGED (API/FSM) or SKU_STATUS_CHANGED (Phase 2 FSM trigger),
-- which triggers Event Handler / Phase 2 outbox to update downstream. Phase 1 does NOT update product_status
-- to avoid overwriting a newer state change with a stale cached value.
-- The cached product_status is therefore potentially stale by up to Phase 2 delay
-- (500ms-2s). Downstream consumers (Cart, Checkout) should be aware of this eventual
-- consistency window. Event Handler will reconcile the cache on next PRODUCT_STATUS_CHANGED.
local newData = {
    sku_id = existingJson and cjson.decode(existingJson).sku_id
               or string.match(cacheKey, "product:sku:(.+)"),
    product_id = productId,
    sku_code = existingJson and cjson.decode(existingJson).sku_code or "",
    product_name = existingJson and cjson.decode(existingJson).product_name or "",
    main_image_url = existingJson and cjson.decode(existingJson).main_image_url or "",
    original_price = existingJson and cjson.decode(existingJson).original_price or 0,
    promotional_price = existingJson and cjson.decode(existingJson).promotional_price,
    best_price = existingJson and cjson.decode(existingJson).best_price or 0,
    has_promotion = existingJson and cjson.decode(existingJson).has_promotion or false,
    display_quantity = availableQty,
    last_inventory_sync_at = eventTimestamp,
    sku_status = newSkuStatus,
    product_status = existingJson and cjson.decode(existingJson).product_status or "",
    is_available = availableQty > 0,
    is_deleted = existingJson and cjson.decode(existingJson).is_deleted or false,
    variant_attributes = existingJson and cjson.decode(existingJson).variant_attributes or {},
    -- price_updated_at is only set by Event Handler when refreshing from SKU_PRICE_UPDATED.
    -- It is NOT set here by Phase 1 to avoid overwriting a newer price change.
    price_updated_at = existingJson and cjson.decode(existingJson).price_updated_at or nil
}

-- Atomic write + add to pending sync ZSET
redis.call('JSON.SET', cacheKey, '$', cjson.encode(newData))
redis.call('EXPIRE', cacheKey, 3600)
redis.call('ZADD', pendingZSet, eventTimestamp, pendingMember)
redis.call('EXPIRE', pendingZSet, 86400)

return existingJson and 1 or -1
```

**Idempotency:** Lua script đảm bảo:
- Stale event (timestamp cũ) không ghi đè cache
- Upsert: nếu cache key chưa tồn tại, vẫn ghi được (preserve non-inventory fields = empty)

**Concurrency:** 8-16 consumer threads trong cùng consumer group để parallelize.

**Tại sao không cần DB lock ở Phase 1:**
- Event payload chứa `available_quantity` — không cần đọc giá trị cũ từ DB
- `event_timestamp` (epoch millis) là monotonic increasing — dùng làm out-of-order guard
- Cache-first: downstream (Cart, Checkout) đọc từ Redis, không cần DB

### 5.2. Phase 2: Inventory Async DB Sync Worker (Redis → PostgreSQL)

**Sứ mệnh:** Drain Redis ZSET pending sync, batch update PostgreSQL với Optimistic Locking và trigger FSM.

**Loại hình:** Scheduled Worker (chạy mỗi 500ms - 1s).

**Chi tiết luồng hoạt động:**

1. **Drain Redis ZSET**: `ZRANGE product:inventory:pending 0 499 BYSCORE` với pagination (100-500 items mỗi run).
2. **Group by product_id** trong RAM (iterator grouping để không phình memory).
3. **Transaction per Product** với Optimistic Locking:
   - Lock Product cha (1 row lock cho cả batch SKU thuộc product đó).
   - Query **tất cả SKU** thuộc product từ DB (không chỉ SKU trong batch drain) để đánh giá FSM toàn diện.
   - Batch update **tất cả SKU** (cả SKU trong drain batch và SKU không có event nhưng đang OUT_OF_STOCK trong DB).
   - FSM auto-hide/reactivate đánh giá trên toàn bộ SKU pool của product.
   - Batch insert `product_outbox_events` (nếu có SKU status change: phát `SKU_STATUS_CHANGED` với payload chứa `sku_ids[]`, `product_status_changed`, `new_product_status` nếu FSM trigger đồng thời đổi product status).
4. **Ack pending** từ Redis ZSET sau khi DB commit thành công.
5. **Optimistic lock fail**: nếu conflict, requeue vào ZSET với exponential backoff.

**Cache Update (Redis Pipeline - Ownership rõ ràng):**
- Phase 2 KHÔNG ghi đè trực tiếp `product:sku:{sku_id}`.
- Việc refresh cache được giao cho **Event Handler** (`product-event-handler-group`) khi self-consume `PRODUCT_STATUS_CHANGED` từ `t.product.events`.
- Phase 2 chỉ quản lý việc ack pending sync từ ZSET.

**Tại sao dùng Optimistic Locking ở Phase 2:**
- Mỗi product xử lý batch SKU (1-N SKU cùng product trong 1 transaction), không còn hot contention.
- Lock Product 1 lần cho cả batch → giảm lock operations 10-100x so với 1 event = 1 lock.

### 5.3. Đảm Bảo Consistency & Self-Healing

| Cơ chế | Mô tả |
|---|---|
| **Eventual Consistency Model** | Cache: immediate (Phase 1) → DB: 500ms-2s (Phase 2) → Search: 2-5s (Debezium CDC) |
| **Multi-layer Staleness Guard** | Layer 1: Lua CAS timestamp check; Layer 2: Phase 2 DB check; Layer 3: Outbox event guard |
| **No Event Loss** | Kafka at-least-once: event được ghi vào ZSET trước khi ACK. Nếu crash sau ZADD trước ACK, Kafka redelivers. |
| **Idempotent Processing** | Lua CAS + DB staleness guard đảm bảo event cũ không bị apply lại. |
| **Pending Ack** | Chỉ xóa khỏi ZSET SAU khi DB commit. Crash giữa chừng → worker restart → đọc lại ZSET. |
| **Self-Healing** | Nếu Phase 1 tạm dừng → Phase 2 vẫn drain pending queue → DB eventually consistent. |

### 5.4. Trade-offs Chấp Nhận Được

| Trade-off | Chi tiết | Mitigation |
|---|---|---|
| **Auto-hide/reactivate delay** | FSM triggers có thể chậm 500ms-2s (thay vì real-time) | Chấp nhận được. SKU status `OUT_OF_STOCK` đã được update ngay trên Redis. Product Detail hiển thị "hết hàng" ngay. |
| **Cache/DB drift ngắn** | Cache hiển thị quantity chưa sync xuống DB (vài trăm ms) | Chấp nhận được. `display_quantity` chỉ dùng hiển thị, không dùng reserve stock. |
| **Complexity tăng** | 2 workers thay vì 1 | Mỗi worker logic đơn giản, dễ test. Đánh đổi xứng đáng với hiệu suất. |
| **Redis as intermediate** | Thêm Redis như tầng trung gian | Redis vốn đã là tầng cache. Không thêm dependency mới. |


## 6. Markdown Expiry Cronjob (Self-Healing)

**Loại hình:** Cron Job chạy định kỳ (**1 lần/ngày** - lúc ít người dùng nhất, ví dụ 3:00 Sáng).

**Sứ mệnh:** Dọn dẹp/Backup các trường hợp Product bị kẹt giá khuyến mãi do rớt mạng hoặc lỗi Kafka Event `MARKDOWN_ENDED` từ Promotion Service. Đảm bảo Product không bao giờ lưu giá cũ vĩnh viễn (Self-Healing).

**Chi tiết luồng hoạt động:**

> **CẢNH BÁO BẮT BUỘC (Tránh Deadlock):** CronJob quét rất nhiều dòng cùng lúc. Khi tiến hành cập nhật bảng con `product_skus`, worker PHẢI tuân thủ nguyên tắc Lock Hierarchy: Lock bảng cha `products` trước để tránh lỗi Deadlock vòng tròn `AB-BA` với luồng API Sửa sản phẩm của admin (API này đi từ Products -> SKUs).

0. **Distributed lock bắt buộc:** trước khi quét, cron phải acquire lock phân tán `product:jobs:markdown-expiry:lock` (SET NX PX, TTL khuyến nghị 60s và gia hạn khi job chạy lâu). Nếu không acquire được lock thì instance hiện tại phải skip vòng chạy để tránh nhiều pod cùng dọn một tập dữ liệu.

1. Quét DB tìm các quy tắc đã quá hạn (Bắt buộc ORDER BY để chống Deadlock và dùng LIMIT để chia Batch):

```sql
SELECT product_id, sku_id, rule_id
FROM product_markdown_applied
WHERE expires_at < NOW()
ORDER BY product_id ASC
LIMIT 100; -- Chunking theo Batch để tránh giữ Lock quá lâu
```

2. Nếu có dữ liệu mớ rỗng (Xử lý Batch theo từng `product_id`):
   - **BẮT BUỘC CHUNKING**: Cronjob có thể lấy ra hàng chục nghìn records. Tuyệt đối không được nối tất cả vào chung một Transaction. Phải xử lý xong một khối 100 records và **COMMIT Giao Diện** ngay lập tức, nhằm xả Lock cho Connection Pool và tránh Block API của admin.
   - **BẮT BUỘC LOCK THEO THỨ TỰ**: Bên trong mỗi Transaction khối, thao tác trên từng `product_id` phải duyệt theo thứ tự TĂNG DẦN (ASC) để ngăn Deadlock chéo. Lock bảng CHA `products` trước: `SELECT 1 FROM products WHERE product_id = :product_id FOR NO KEY UPDATE;`
   - Reset `promotional_price = NULL` ở `product_skus`.
   - Delete dòng mapping ở bảng `product_markdown_applied`.
   - Ban Outbox trigger Event `SKU_PRICE_UPDATED`.
   - Xóa Cache liên quan.
