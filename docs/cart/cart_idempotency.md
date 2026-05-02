# Cart Service - Idempotency Pattern (Consumer Worker)

**Liên kết nhanh (Related Documents):**
*   [Workflows (API & Data Flow)](cart_workflow.md)
*   [Background Workers & Jobs](cart_workers.md)
*   [Caching Schema (Redis-First)](cart_caching_schema.md)
*   [Database Schema (Cold Store)](cart_db_schema.md)
*   [Outbox Pattern (Redis Streams)](cart_outbox_pattern.md)
*   [Idempotency Pattern](cart_idempotency.md)
*   [Events Structure](cart_events.md)

---
Tài liệu này giải thích cách kiểm soát trùng lặp Message (Idempotency) dành cho Kafka Consumer ([Postgres Sync Worker](cart_workers.md#2-postgres-sync-consumer-worker)) của Cart Service khi thực hiện ghi dữ liệu xuống PostgreSQL (Cold Store).

## 1. Bản Chất của Vấn Đề (Problem Statement)

Không có hệ thống Message Queue phân tán nào (kể cả Kafka) có thể đảm bảo **Exactly-Once delivery** một cách dễ dàng và rẻ tiền. Chúng đa số hoạt động ở mức **At-least-once delivery** (Gửi ít nhất một lần).

Điều này có nghĩa là, Worker có thể nhận được sự kiện `CART_ITEM_ADDED` cho sản phẩm A của User B **hai lần hoặc nhiều hơn** (Do retry từ producer, do consumer crash trước khi kịp commit offset, v.v.).

Hơn nữa, các sự kiện có nguy cơ bị đảo lộn thứ tự (Out-of-order events). Ví dụ User nhấn "Tăng số lượng lên 2", rồi nhanh tay bấm "Tăng số lượng lên 3". Event "lên 2" bị lag mạng và đến sau event "lên 3". Nếu Worker nhắm mắt ghi đè, Database sẽ lưu kết quả sai là "2".

> **Phạm vi áp dụng:** Tài liệu này bao trùm cả event **ADD/UPDATE** lẫn **DELETE**. Mỗi loại event có chiến lược idempotency khác nhau do bản chất thao tác khác nhau.

## 2. Giải Pháp: Idempotency bằng Stream ID (Primary) + Timestamp (Fallback)

May mắn thay, mô hình dữ liệu của PostgreSQL trong Cart Service (`cart_items`) được thiết kế là **Dumb Store (Kho lưu trữ thụ động)**. Nhiệm vụ của nó chỉ là phản chiếu (Mirroring) trạng thái cuối cùng của Redis.

Thay vì dùng một bảng `idempotency_keys` phức tạp (như Order Service), Cart Service áp dụng chiến lược **Idempotency dựa trên Redis Stream ID & Upsert**. Timestamp `updated_at` vẫn được giữ để tương thích dữ liệu cũ và phục vụ business timeline.

### 2.1. Yêu cầu đầu vào từ Producer (Redis/Outbox)
Cấu trúc JSON payload event ([xem Events Structure](cart_events.md)) bắt buộc phải có trường `updated_at` (Milliseconds Epoch) để phục vụ business timeline và fallback guard. Đồng thời, event Kafka bắt buộc có thêm `stream_id` (lấy từ Redis `msg.ID`), `stream_timestamp_ms`, `event_sequence` do Relay Worker bổ sung. Payload bắt buộc chứa `cart_id`, `sku_id`, và **`user_id`**. `user_id` được lấy trực tiếp từ event payload — Worker **tuyệt đối không** tách chuỗi từ `cart_id` để lấy `user_id`.

> **Contract đồng bộ:** `current_price` không tham gia payload event và không dùng cho idempotency. Đây là field runtime ở API đọc giỏ.

> **Lưu ý quan trọng:** JSON dưới đây là **định dạng Redis Stream (Outbox)** — tức là nội dung được Lua Script đẩy vào `XADD` với hai field: `event_type` và `payload`. Các header Kafka (`event_id`, `aggregate_type`, `aggregate_id`, `correlation_id`, `timestamp`, `source`, `stream_id`, `stream_timestamp_ms`, `event_sequence`) được [Outbox Relay Worker](cart_workers.md#1-outbox-relay-producer-worker) **bổ sung** khi forward sang Kafka. Worker thực tế ([Postgres Sync Worker](cart_workers.md#2-postgres-sync-consumer-worker)) đọc từ Kafka và dùng `stream_id` làm idempotency key chính.

**Định dạng Redis Stream (sau XADD):**
```json
{
  "event_type": "CART_ITEM_UPDATED",
    "payload": "{\"cart_id\":\"{cart:user:12345}\",\"sku_id\":\"sku-1001\",\"user_id\":\"12345\",\"product_id\":\"prod-uuid\",\"platform_id\":\"admin-uuid\",\"platform_name\":\"platform Tech Việt\",\"quantity\":3,\"unit_price_at_add\":150000.00,\"product_name\":\"Áo Thun Nam\",\"variant_info\":\"Màu Đen, Size L\",\"image_url\":\"https://cdn.domain.com/abc.jpg\",\"is_available\":true,\"added_at\":\"1708530000123\",\"updated_at\":\"1708616400456\"}"
}
```

**Định dạng Kafka (sau Relay Worker bổ sung header):**
```json
{
  "event_id": "018e7b4a-1234-7def-8abc-123456789abc",
  "event_type": "CART_ITEM_UPDATED",
  "aggregate_type": "Cart",
  "aggregate_id": "{cart:user:12345}",
  "correlation_id": "req-124",
  "timestamp": "2026-02-28T10:05:00Z",
  "source": "cart-service",
    "stream_id": "1708616400456-0",
    "stream_timestamp_ms": "1708616400456",
    "event_sequence": "0",
  "payload": {
    "cart_id": "{cart:user:12345}",
    "sku_id": "sku-1001",
    "user_id": "12345",
    "product_id": "prod-uuid",
            "quantity": 3,
    "unit_price_at_add": 150000.00,
    "product_name": "Áo Thun Nam",
    "variant_info": "Màu Đen, Size L",
    "image_url": "https://cdn.domain.com/abc.jpg",
    "is_available": true,
    "added_at": "1708530000123",
    "updated_at": "1708616400456"
  }
}
```

> **Nhận xét:** `payload` của Kafka event là **flat fields** (phiếu phẳng) — không có trường `product_snapshot` riêng. Worker phải **tự dựng lại** JSONB `product_snapshot` từ các flat field này trước khi INSERT vào `cart_items`. Xem chi tiết tại [Mục 2.3](#23-xử-lý-sai-thứ-tự-out-of-order).

> **Tie-breaker policy:** Nếu `event.stream_id == db.redis_stream_id`, Worker xem đây là duplicate và bỏ qua (không update). `updated_at` chỉ dùng fallback khi dữ liệu cũ chưa có `redis_stream_id`.

### 2.2. Xử lý Trùng lặp (Duplicate)
Vì Worker dùng cú pháp `ON CONFLICT DO UPDATE` (Upsert).
Nếu nhận 2 event có *nội dung y hệt nhau*, lệnh SQL Update `SET quantity = X` lần 2 sẽ đè lên lần 1 cùng một giá trị X. Kết quả cuối cùng DB vẫn đúng. Dữ liệu mang tính luỹ đẳng (Idempotent) tự nhiên.

### 2.3. Xử lý Sai Thứ Tự (Out-of-order)
Để giải quyết việc event cũ đến muộn đè lên event mới vừa được ghi, câu lệnh Upsert của PostgreSQL được tuỳ chỉnh mệnh đề `WHERE` theo thứ tự ưu tiên:
1. So sánh `event.stream_id` với `cart_items.redis_stream_id` (primary).
2. Nếu bản ghi cũ chưa có `redis_stream_id`, fallback sang so sánh `updated_at` trong `product_snapshot` (legacy compatibility).

**Bước 1 — Worker dựng `product_snapshot` JSONB từ flat fields:**

Kafka event payload chỉ có flat fields, không có column `product_snapshot` sẵn. Worker phải lắp ráp JSONB này:
```go
// Trong Go Worker: map flat payload fields -> product_snapshot JSONB
productSnapshot := map[string]interface{}{
    "product_name":       payload.ProductName,
    "variant_info":       payload.VariantInfo,
    "image_url":          payload.ImageURL,
    "is_available":       payload.IsAvailable,
    "added_at":           payload.AddedAt,      // chuỗi epoch ms
    "updated_at":         payload.UpdatedAt,    // chuỗi epoch ms — fallback guard cho dữ liệu legacy
}
productSnapshotJSON, _ := json.Marshal(productSnapshot)
// LƯU Ý: unit_price_at_add KHÔNG đưa vào product_snapshot — lưu ở cột riêng cart_items.unit_price_at_add
```

**Bước 2 — Thực hiện Upsert với `stream_id` primary + `updated_at` fallback:**

Cú pháp SQL logic tương đương:
```sql
INSERT INTO cart_items (
    item_id, cart_id, product_id, sku_id, platform_id, platform_name,
    quantity, unit_price_at_add, product_snapshot, redis_stream_id, created_at, updated_at
)
VALUES (
    '{cart:user:12345}:sku-1001',  -- item_id = cart_id + ':' + sku_id
    '{cart:user:12345}',           -- cart_id          (payload.cart_id)
    'prod-uuid',                   -- product_id        (payload.product_id)
    'sku-1001',                    -- sku_id            (payload.sku_id)
    'admin-uuid',                 -- platform_id         (payload.platform_id)
    'platform Tech Việt',              -- platform_name       (payload.platform_name)
    2,                             -- quantity          (payload.quantity)
    150000.00,                     -- unit_price_at_add (payload.unit_price_at_add — cột riêng)
    -- Worker dựng JSONB từ các flat fields của payload (KHÔNG có unit_price_at_add):
    '{"product_name":"Áo Thun Nam","variant_info":"Màu Đen, Size L",
      "image_url":"https://cdn.domain.com/abc.jpg",
      "is_available":true,
            "added_at":"1708530000123","updated_at":"1708530000123"}',  -- product_snapshot
    '1708530000123-0',             -- redis_stream_id từ Kafka header
    NOW(),                         -- created_at
    NOW()                          -- updated_at (row-level, cho CronJob ABANDONED check)
)
ON CONFLICT (item_id)
DO UPDATE SET
    quantity         = EXCLUDED.quantity,
    platform_name      = EXCLUDED.platform_name,
    product_snapshot = EXCLUDED.product_snapshot,
    redis_stream_id  = EXCLUDED.redis_stream_id,
    is_deleted       = false,
    updated_at       = NOW()
WHERE
    -- Primary guard: stream_id mới hơn
    (
        (cart_items.redis_stream_id IS NOT NULL AND cart_items.redis_stream_id < $1)
        OR (
            cart_items.redis_stream_id IS NULL
            AND ((cart_items.product_snapshot->>'updated_at')::bigint < $2)
        )
    )
    -- Ngăn chặn cập nhật nếu đã bị đánh dấu Tombstone (Ghost item handler)
    AND (cart_items.is_deleted = false OR cart_items.tombstone_at < $2);
    -- Ví dụ: $1 = '1708530000123-0', $2 = 1708530000123
```

> **Tại sao không update `unit_price_at_add`?**
> Trường này đại diện cho "giá tại thời điểm khách thêm vào giỏ". Ngay cả khi thông tin sản phẩm cập nhật (đổi tên, đổi giá hiện tại), chúng ta vẫn giữ lại mốc giá lịch sử này để hiển thị chênh lệch (Price Alert). Giá realtime (`current_price`) được lấy ở read path từ Product Service, không lưu vào `product_snapshot`.

> **Ghi chú:** Trong cùng Transaction, Worker phải cập nhật bảng master `carts` theo [DB Schema — Workflow Đồng Bộ](cart_db_schema.md#3-workflow-đồng-bộ-postgresql-là-rdbms-phụ-thuộc). `user_id` lấy trực tiếp từ `payload.user_id` — không parse `cart_id`.

### 2.4. Xử lý Sai Thứ Tự cho DELETE Event

Khi nhận event `CART_ITEM_DELETED` ([xem Events](cart_events.md#23-cart_item_deleted)), cần xử lý các edge case:

**Trường hợp 1: DELETE event đến sau ADD/UPDATE (Hành vi bình thường)**
```sql
UPDATE cart_items
SET is_deleted = true,
    tombstone_at = 1708616400899,   -- Milliseconds Epoch từ deleted_at event
    redis_stream_id = '1708616400899-0',
    updated_at = NOW()
WHERE item_id = '{cart:user:12345}:sku-1001'
  AND (
      (redis_stream_id IS NOT NULL AND redis_stream_id < '1708616400899-0')
      OR (
          redis_stream_id IS NULL
          AND (product_snapshot->>'updated_at')::bigint < 1708616400899
      )
  );
```
→ **Tuyệt đối KHÔNG hard delete** (lệnh `DELETE FROM`). Luôn sử dụng Soft Delete: cập nhật `is_deleted = true`, gán `tombstone_at = deleted_at` và `updated_at = NOW()`. Điều này giúp lưu lại vết (Tombstone) để khi bản tin ADD đến trễ, nó có cơ sở để so sánh timestamp và bị Rejection hợp lý. Nếu DB đã có phiên bản mới hơn (user vừa re-add item), câu lệnh không match → không xóa nhầm.

**Trường hợp 2: DELETE event đến TRƯỚC ADD event (Lỗi Ghost Item - Áp dụng Tombstone)**
*Vấn đề:* Nếu DELETE đến trước, lệnh `DELETE` ở trên sẽ match 0 rows. Một lúc sau lệnh `ADD` bị delay mới đáp tới DB, nó sẽ vô tình `INSERT` một "Ghost Item" - thứ mà lẽ ra đã phải chết theo lệnh DELETE rồi.
*Giải pháp Tombstone:* Worker sẽ bắt phản hồi của lệnh `DELETE` ban đầu. Nếu `RowsAffected == 0` (nghĩa là Item chưa từng tồn tại), Worker sẽ thực hiện một lệnh tạo **Tombstone** giả vào DB:
```sql
INSERT INTO cart_items (
    item_id, cart_id, sku_id, is_deleted, tombstone_at,
    quantity, unit_price_at_add, product_snapshot
)
VALUES (
    '{cart:user:12345}:sku-1001',
    '{cart:user:12345}',
    'sku-1001',
    true,
    1708616400899,                           -- tombstone_at (ms epoch từ deleted_at event)
    0,                                       -- quantity: default 0 cho tombstone
    0.00,                                    -- unit_price_at_add: default 0.00 cho tombstone
    '{"updated_at": "1708616400899"}'        -- product_snapshot tối thiểu với updated_at
)
ON CONFLICT (item_id) DO NOTHING;
```
Lúc sau, khi lệnh `ADD/UPDATE` cũ đến muộn. Câu lệnh Upsert của [Mục 2.3](#23-xử-lý-sai-thứ-tự-out-of-order) kiểm tra điều kiện `WHERE`:
```sql
WHERE
        ((cart_items.redis_stream_id IS NOT NULL AND cart_items.redis_stream_id < $1)
            OR (cart_items.redis_stream_id IS NULL AND (cart_items.product_snapshot->>'updated_at')::bigint < $2))
        AND (cart_items.is_deleted = false OR cart_items.tombstone_at < $2);
```
Nhờ rào chắn này, event `ADD` lỡ mướt mồ hôi tới nơi thấy cái cờ Tombstone mới cứng > timestamp của event gốc thì sẽ tự ngã ngựa, không tạo ra Ghost Item.

**Trường hợp 3: DELETE event trùng lặp (Duplicate)**
→ Lần DELETE đầu xóa item (hoặc update is_deleted = true). Lần DELETE thứ hai dựa theo Timestamp sẽ không có tác dụng. An toàn.

### 2.5. Idempotency cho trạng thái bảng `carts` (Re-activate + Guard Transition)

Vì `cart_id` là định danh ổn định theo user, một cart có thể đi qua nhiều vòng đời `ACTIVE -> DELETED -> ACTIVE`.
Do đó cần quy tắc rõ ràng để tránh out-of-order làm sai trạng thái:

1. Với `CART_ITEM_ADDED` / `CART_ITEM_UPDATED`:
    - Chỉ set `carts.status = 'ACTIVE'` nếu data mới hơn theo `last_event_stream_id < event.stream_id`.
    - Cập nhật `carts.last_event_stream_id` theo stream mới nhất và đồng thời cập nhật `last_event_time_ms` để phục vụ analytics.
     - `user_id` lấy trực tiếp từ `payload.user_id`.
2. Với `CART_CLEARED` / `CART_CHECKED_OUT`:
    - Chỉ đổi trạng thái nếu `stream_id` mới hơn (primary), fallback event time cho bản ghi legacy chưa có `last_event_stream_id`.
     - Nếu event cũ hơn thì bỏ qua.

Ví dụ SQL an toàn cho `CART_CLEARED`:
```sql
UPDATE carts
SET status = 'DELETED',
        last_event_stream_id = $3,
    last_event_time_ms = $2,  -- Milliseconds Epoch
    updated_at = NOW()
WHERE cart_id = $1
    AND (
            (last_event_stream_id IS NOT NULL AND last_event_stream_id < $3)
            OR (last_event_stream_id IS NULL AND last_event_time_ms < $2)
    );
```

Ví dụ SQL an toàn cho `CART_CHECKED_OUT`:
```sql
UPDATE carts
SET status = 'CHECKED_OUT',
        last_event_stream_id = $3,
    last_event_time_ms = $2,  -- Milliseconds Epoch
    updated_at = NOW()
WHERE cart_id = $1
    AND (
            (last_event_stream_id IS NOT NULL AND last_event_stream_id < $3)
            OR (last_event_stream_id IS NULL AND last_event_time_ms < $2)
    );
```

Ví dụ upsert/re-activate khi `CART_ITEM_UPDATED`:
```sql
INSERT INTO carts (cart_id, user_id, status, last_event_stream_id, last_event_time_ms, created_at, updated_at)
VALUES ($1, $2, 'ACTIVE', $4, $3, NOW(), NOW())
-- $1 = payload.cart_id, $2 = payload.user_id (lấy trực tiếp từ event, không parse chuỗi)
-- $3 = payload.updated_at (Milliseconds Epoch)
-- $4 = event.stream_id
ON CONFLICT (cart_id)
DO UPDATE SET
    status = 'ACTIVE',
    last_event_stream_id = EXCLUDED.last_event_stream_id,
    last_event_time_ms = EXCLUDED.last_event_time_ms,
    updated_at = NOW()
WHERE (carts.last_event_stream_id IS NOT NULL AND carts.last_event_stream_id < $4)
   OR (carts.last_event_stream_id IS NULL AND carts.last_event_time_ms < $3);
```

> **Lưu ý triển khai:** Tránh pattern `COALESCE(last_event_stream_id, '') ... OR last_event_stream_id IS NULL ...` vì nhánh `COALESCE` đã nuốt luôn trường hợp NULL, làm fallback branch mất ý nghĩa. Luôn tách nhánh `IS NOT NULL`/`IS NULL` như ví dụ trên.

### Chú Ý Về Mặt Lập Trình (GORM):
Sử dụng GORM, bạn có thể implement logic ADD/UPDATE idempotency bằng cách custom `clause.OnConflict`:

```go
// Trước khi Upsert: Worker dựng product_snapshot từ flat payload fields
productSnapshot := CartItemSnapshot{
    ProductName:      event.Payload.ProductName,
    VariantInfo:      event.Payload.VariantInfo,
    ImageURL:         event.Payload.ImageURL,
    IsAvailable:      event.Payload.IsAvailable,
    AddedAt:          event.Payload.AddedAt,           // String ms epoch
    UpdatedAt:        event.Payload.UpdatedAt,          // String ms epoch — fallback guard cho dữ liệu legacy
    // unit_price_at_add KHÔNG nằm trong snapshot — lưu ở cột riêng
}
productSnapshotJSON, _ := json.Marshal(productSnapshot)

item := CartItem{
    ItemID:          event.Payload.CartID + ":" + event.Payload.SkuID,
    CartID:          event.Payload.CartID,
    ProductID:       event.Payload.ProductID,
    SkuID:           event.Payload.SkuID,
    adminID:        event.Payload.adminID,
    adminName:      event.Payload.adminName,
    Quantity:        event.Payload.Quantity,
    UnitPriceAtAdd:  event.Payload.UnitPriceAtAdd,  // Cột riêng
    ProductSnapshot: productSnapshotJSON,            // JSONB đã được dựng bởi Worker
}

// Upsert với stream_id primary + updated_at fallback
db.Clauses(clause.OnConflict{
    Columns:   []clause.Column{{Name: "item_id"}},
    DoUpdates: clause.Assignments(map[string]interface{}{
        "quantity":         gorm.Expr("EXCLUDED.quantity"),
        "platform_name":      gorm.Expr("EXCLUDED.platform_name"),
        "product_snapshot": gorm.Expr("EXCLUDED.product_snapshot"),
        "redis_stream_id":  gorm.Expr("EXCLUDED.redis_stream_id"),
        "is_deleted":       false,
        "updated_at":       gorm.Expr("NOW()"),
    }),
    Where: clause.Where{
        Exprs: []clause.Expression{
            clause.Expr{
                SQL:  "((cart_items.redis_stream_id IS NOT NULL AND cart_items.redis_stream_id < ?) OR (cart_items.redis_stream_id IS NULL AND (cart_items.product_snapshot->>'updated_at')::bigint < ?)) AND (cart_items.is_deleted = false OR cart_items.tombstone_at < ?)",
                Vars: []interface{}{event.StreamID, event.Payload.UpdatedAt, event.Payload.UpdatedAt},
            },
        },
    },
}).Create(&item)

// Sau khi upsert cart_items, upsert bảng carts với user_id từ payload:
db.Exec(`
    INSERT INTO carts (cart_id, user_id, status, last_event_stream_id, last_event_time_ms, created_at, updated_at)
    VALUES (?, ?, 'ACTIVE', ?, ?, NOW(), NOW())
    ON CONFLICT (cart_id) DO UPDATE SET
        status = 'ACTIVE',
        last_event_stream_id = EXCLUDED.last_event_stream_id,
        last_event_time_ms = EXCLUDED.last_event_time_ms,
        updated_at = NOW()
     WHERE (carts.last_event_stream_id IS NOT NULL AND carts.last_event_stream_id < ?)
         OR (carts.last_event_stream_id IS NULL AND carts.last_event_time_ms < ?)
`, event.Payload.CartID, event.Payload.UserID, event.StreamID, event.Payload.UpdatedAt, event.StreamID, event.Payload.UpdatedAt)
```

Và xử lý DELETE event:
```go
result := db.Model(&CartItem{}).
    Where("item_id = ? AND ((redis_stream_id IS NOT NULL AND redis_stream_id < ?) OR (redis_stream_id IS NULL AND (product_snapshot->>'updated_at')::bigint < ?))",
          itemID, event.StreamID, event.Payload.DeletedAt).
    Updates(map[string]interface{}{
        "is_deleted":   true,
        "tombstone_at": event.Payload.DeletedAt,  // Lưu ms epoch
        "redis_stream_id": event.StreamID,
        "updated_at":   gorm.Expr("NOW()"),
    })

// result.RowsAffected == 0 -> event cũ hoặc item đã được xóa logic -> Tombstone
if result.RowsAffected == 0 {
    db.Exec(`
        INSERT INTO cart_items (item_id, cart_id, sku_id, is_deleted, tombstone_at,
                                redis_stream_id, quantity, unit_price_at_add, product_snapshot)
        VALUES (?, ?, ?, true, ?, ?, 0, 0.00, ?)
        ON CONFLICT (item_id) DO UPDATE SET
            is_deleted = true,
            tombstone_at = EXCLUDED.tombstone_at,
            redis_stream_id = EXCLUDED.redis_stream_id,
            updated_at = NOW()
        WHERE (cart_items.redis_stream_id IS NOT NULL AND cart_items.redis_stream_id < EXCLUDED.redis_stream_id)
           OR (cart_items.redis_stream_id IS NULL AND (cart_items.product_snapshot->>'updated_at')::bigint < ?)
    `, itemID, event.Payload.CartID, event.Payload.SkuID,
       event.Payload.DeletedAt, event.StreamID,
       fmt.Sprintf(`{"updated_at": "%d"}`, event.Payload.DeletedAt),
       event.Payload.DeletedAt)
}
```

Nhờ thiết kế này, worker an toàn trước cả **Tin nhắn trùng** lẫn **Tin nhắn lạc đường**, giữ DB luôn ở trạng thái Eventually Consistent 100% với Primary Store Redis.

> **Tham khảo thêm:**
> - [Events Structure](cart_events.md) — Cấu trúc payload cho từng event type
> - [Postgres Sync Worker](cart_workers.md#2-postgres-sync-consumer-worker) — Worker áp dụng logic idempotency này
> - [Database Schema](cart_db_schema.md) — Cấu trúc bảng `cart_items` và `product_snapshot` JSONB
> - [Outbox Pattern](cart_outbox_pattern.md) — Cách event được sinh ra atomic trong Lua Script


### 4. Chuẩn hóa Timezone
- **Primary ordering key:** `stream_id` từ Redis Stream/Kafka header là khóa so sánh chính cho idempotency và out-of-order guard.
- **UTC Milliseconds:** `updated_at` vẫn phải dùng UTC milliseconds để phục vụ analytics, timeline và fallback cho dữ liệu cũ chưa có `stream_id`.
- **Bắt buộc khi parse ISO-8601:** Khi convert từ chuỗi ISO-8601 sang epoch ms trong các luồng cần timestamp guard, Worker **phải** dùng `time.Parse(time.RFC3339, str)` rồi `.UTC().UnixMilli()`. Tuyệt đối không dùng `time.Local()`.
- **Deterministic Event Header:** `event_id` ở Kafka header được Relay Worker sinh theo cách deterministic từ `Redis Stream ID + cart_id` để phục vụ trace/dedup downstream, nhưng không dùng làm khóa so sánh trong `product_snapshot` của `cart_items`.
