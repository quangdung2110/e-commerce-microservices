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

## 2. Giải Pháp: Idempotency bằng Stream ID

May mắn thay, mô hình dữ liệu của PostgreSQL trong Cart Service (`cart_items`) được thiết kế là **Dumb Store (Kho lưu trữ thụ động)**. Nhiệm vụ của nó chỉ là phản chiếu (Mirroring) trạng thái cuối cùng của Redis.

Cart Service áp dụng chiến lược **Idempotency dựa trên Redis Stream ID**. `stream_id` là khóa ordering duy nhất — đơn giản, mạnh mẽ, không phụ thuộc clock.

### 2.1. Yêu cầu đầu vào từ Producer (Redis/Outbox)

Cấu trúc JSON payload event ([xem Events Structure](cart_events.md)) bắt buộc phải có trường `updated_at` (Milliseconds Epoch) để phục vụ business timeline. Event Kafka bắt buộc có thêm `stream_id` (lấy từ Redis `msg.ID`), `stream_timestamp_ms`, `event_sequence` do Relay Worker bổ sung. Payload bắt buộc chứa `cart_id`, `sku_id`, và **`user_id`**.

> **Contract đồng bộ:** `current_price` không tham gia payload event và không dùng cho idempotency. Đây là field runtime ở API đọc giỏ.

> **Lưu ý quan trọng về định dạng Redis Stream Outbox:**
> - Tại tầng Lua Script, message được `XADD` với 2 fields: `event_type` và `payload`.
> - **TRƯỚC KHI XADD**, Lua Script bổ sung thêm 3 trường vào payload JSON: `cart_id`, `sku_id`, `user_id` (để downstream Consumer biết ghi vào DB mà không cần parse key/field name).
> - Các header Kafka (`event_id`, `aggregate_type`, `aggregate_id`, `correlation_id`, `timestamp`, `source`, `stream_id`, `stream_timestamp_ms`, `event_sequence`) được [Outbox Relay Worker](cart_workers.md#1-outbox-relay-producer-worker) **bổ sung** khi forward sang Kafka.

**Định dạng Kafka (sau Relay Worker bổ sung header):**
```json
{
  "event_id": "018e7b4a-1234-7def-8abc-123456789abc",
  "event_type": "CART_ITEM_UPDATED",
  "aggregate_type": "Cart",
  "aggregate_id": "{cart}:550e8400-e29b-41d4-a716-446655440000",
  "correlation_id": "req-124",
  "timestamp": "2026-02-28T10:05:00Z",
  "source": "cart-service",
  "stream_id": "1708616400456-0",
  "stream_timestamp_ms": "1708616400456",
  "event_sequence": "0",
  "payload": {
    "cart_id": "{cart}:550e8400-e29b-41d4-a716-446655440000",
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

> **Tie-breaker policy:** Nếu `event.stream_id == db.redis_stream_id`, Worker xem đây là duplicate và bỏ qua (không update).

### 2.2. Xử lý Trùng lặp (Duplicate)

Vì Worker dùng cú pháp `ON CONFLICT DO UPDATE` (Upsert).
Nếu nhận 2 event có *nội dung y hệt nhau*, lệnh SQL Update `SET quantity = X` lần 2 sẽ đè lên lần 1 cùng một giá trị X. Kết quả cuối cùng DB vẫn đúng. Dữ liệu mang tính luỹ đẳng (Idempotent) tự nhiên.

### 2.3. Xử lý Out-of-order

Để giải quyết việc event cũ đến muộn đè lên event mới vừa được ghi, câu lệnh Upsert của PostgreSQL được tuỳ chỉnh mệnh đề `WHERE` theo `stream_id`.

**Worker dựng `product_snapshot` JSONB từ flat fields:**
```go
// Lưu ý: product_snapshot JSONB chỉ lưu các field hiển thị tĩnh.
// KHÔNG lưu vào snapshot:
// - unit_price_at_add  -> lưu ở cột riêng (dùng để hiển thị price alert)
// - current_price      -> field runtime, chỉ xuất hiện ở API response
// - cart_id, sku_id, user_id -> đã implicit trong Key/Field/Payload
productSnapshot := map[string]interface{}{
    "product_id":    payload.ProductID,    // ID của sản phẩm gốc
    "product_name":  payload.ProductName,
    "variant_info":  payload.VariantInfo,
    "image_url":     payload.ImageURL,     // URL ảnh sản phẩm
    "is_available": payload.IsAvailable,
    "added_at":     payload.AddedAt,      // Milliseconds epoch dạng string
    "updated_at":    payload.UpdatedAt,   // Milliseconds epoch dạng string
}
productSnapshotJSON, _ := json.Marshal(productSnapshot)
```

> **Tại sao `unit_price_at_add` không nằm trong `product_snapshot`?**
> Trường này đại diện cho "giá tại thời điểm khách thêm vào giỏ" và được lưu ở **cột riêng** (`unit_price_at_add`) để:
> 1. Phục vụ Analytics query theo giá lịch sử.
> 2. Phục vụ so sánh Price Alert (hiển thị chênh lệch giá).
> 3. Dùng cho idempotency guard (so sánh quantity theo timestamp).
> Ngay cả khi thông tin sản phẩm cập nhật, chúng ta vẫn giữ lại mốc giá lịch sử này.

**Upsert với `stream_id` guard:**
```sql
INSERT INTO cart_items (
    item_id, cart_id, product_id, sku_id,
    quantity, unit_price_at_add, product_snapshot, redis_stream_id, created_at, updated_at
)
VALUES (
    '{cart}:550e8400-e29b-41d4-a716-446655440000:sku-1001',
    '{cart}:550e8400-e29b-41d4-a716-446655440000',
    'prod-uuid',
    'sku-1001',
    2,
    150000.00,
    '{"product_name":"Áo Thun Nam","variant_info":"Màu Đen, Size L",
      "image_url":"https://cdn.domain.com/abc.jpg",
      "is_available":true,
      "added_at":"1708530000123","updated_at":"1708530000123"}',
    '1708530000123-0',
    NOW(),
    NOW()
)
ON CONFLICT (item_id)
DO UPDATE SET
    quantity         = EXCLUDED.quantity,
    product_snapshot = EXCLUDED.product_snapshot,
    redis_stream_id  = EXCLUDED.redis_stream_id,
    updated_at       = NOW()
WHERE cart_items.redis_stream_id < EXCLUDED.redis_stream_id;
```

### 2.4. Xử lý CART_ITEM_DELETED Event

Khi nhận event `CART_ITEM_DELETED` ([xem Events](cart_events.md#23-cart_item_deleted)), Worker thực hiện **Hard Delete** (xóa hẳn dòng khỏi DB).

```sql
DELETE FROM cart_items
WHERE item_id = '{cart}:550e8400-e29b-41d4-a716-446655440000:sku-1001'
  AND redis_stream_id < '1708616400899-0';
```

> **Đơn giản hóa:** Vì Redis là Source of Truth, nếu item đã bị xóa khỏi Redis thì trong DB cũng không cần giữ bản ghi. Nếu event DELETE đến trước ADD do network lag, dòng ADD sau sẽ tự INSERT lại bình thường.

**Trường hợp DELETE trùng lặp:**
- Lần DELETE đầu xóa item thành công.
- Lần DELETE thứ hai không match dòng nào → 0 rows affected → an toàn.

### 2.5. Idempotency cho trạng thái bảng `carts`

Vì `cart_id` là định danh ổn định theo user, một cart có thể đi qua nhiều vòng đời `ACTIVE -> DELETED -> ACTIVE`.
Do đó cần quy tắc rõ ràng để tránh out-of-order làm sai trạng thái:

1. Với `CART_ITEM_ADDED` / `CART_ITEM_UPDATED`:
   - Chỉ set `carts.status = 'ACTIVE'` nếu `last_event_stream_id < event.stream_id`.
   - Cập nhật `carts.last_event_stream_id` theo stream mới nhất.
   - `user_id` lấy trực tiếp từ `payload.user_id`.

2. Với `CART_CLEARED` / `CART_CHECKED_OUT`:
   - Chỉ đổi trạng thái nếu `last_event_stream_id < event.stream_id`.
   - Nếu event cũ hơn thì bỏ qua.

**SQL cho `CART_CLEARED`:**
```sql
UPDATE carts
SET status = 'DELETED',
    last_event_stream_id = $2,
    updated_at = NOW()
WHERE cart_id = $1
  AND last_event_stream_id < $2;
```

**SQL cho `CART_CHECKED_OUT`:**
```sql
UPDATE carts
SET status = 'CHECKED_OUT',
    last_event_stream_id = $2,
    updated_at = NOW()
WHERE cart_id = $1
  AND last_event_stream_id < $2;
```

**SQL cho `CART_ITEM_ADDED/UPDATED` (Upsert cart):**
```sql
INSERT INTO carts (cart_id, user_id, status, last_event_stream_id, created_at, updated_at)
VALUES ($1, $2, 'ACTIVE', $3, NOW(), NOW())
ON CONFLICT (cart_id)
DO UPDATE SET
    status = 'ACTIVE',
    last_event_stream_id = EXCLUDED.last_event_stream_id,
    updated_at = NOW()
WHERE carts.last_event_stream_id < EXCLUDED.last_event_stream_id;
```

### 2.6. Code mẫu (GORM)

```go
// Upsert cart_items với stream_id guard
db.Clauses(clause.OnConflict{
    Columns:   []clause.Column{{Name: "item_id"}},
    DoUpdates: clause.Assignments(map[string]interface{}{
        "quantity":         gorm.Expr("EXCLUDED.quantity"),
        "product_snapshot": gorm.Expr("EXCLUDED.product_snapshot"),
        "redis_stream_id":  gorm.Expr("EXCLUDED.redis_stream_id"),
        "updated_at":       gorm.Expr("NOW()"),
    }),
    Where: clause.Where{
        Exprs: []clause.Expression{
            clause.Expr{
                SQL:  "cart_items.redis_stream_id < ?",
                Vars: []interface{}{event.StreamID},
            },
        },
    },
}).Create(&item)

// Upsert bảng carts
db.Exec(`
    INSERT INTO carts (cart_id, user_id, status, last_event_stream_id, created_at, updated_at)
    VALUES (?, ?, 'ACTIVE', ?, NOW(), NOW())
    ON CONFLICT (cart_id) DO UPDATE SET
        status = 'ACTIVE',
        last_event_stream_id = EXCLUDED.last_event_stream_id,
        updated_at = NOW()
    WHERE carts.last_event_stream_id < EXCLUDED.last_event_stream_id
`, event.Payload.CartID, event.Payload.UserID, event.StreamID)

// DELETE cart_items
db.Exec(`
    DELETE FROM cart_items
    WHERE item_id = ? AND redis_stream_id < ?
`, itemID, event.StreamID)
```

## 3. Chuẩn hóa Timezone

- **Ordering key:** `stream_id` từ Redis Stream/Kafka header là khóa so sánh duy nhất cho idempotency và out-of-order guard.
- **UTC Milliseconds:** `updated_at` dùng UTC milliseconds để phục vụ analytics và timeline.
- **Bắt buộc khi parse ISO-8601:** Khi convert từ chuỗi ISO-8601 sang epoch ms, Worker **phải** dùng `time.Parse(time.RFC3339, str)` rồi `.UTC().UnixMilli()`. Tuyệt đối không dùng `time.Local()`.
- **Deterministic Event Header:** `event_id` ở Kafka header được Relay Worker sinh theo cách deterministic từ `Redis Stream ID + cart_id` để phục vụ trace/dedup downstream.
