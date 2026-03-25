# Cart Service - Định nghĩa Các Sự Kiện (Events Structure)

**Liên kết nhanh (Related Documents):**
*   [Workflows (API & Data Flow)](cart_workflow.md)
*   [Background Workers & Jobs](cart_workers.md)
*   [Caching Schema (Redis-First)](cart_caching_schema.md)
*   [Database Schema (Cold Store)](cart_db_schema.md)
*   [Outbox Pattern (Redis Streams)](cart_outbox_pattern.md)
*   [Idempotency Pattern](cart_idempotency.md)
*   [Events Structure](cart_events.md)

---

Tài liệu này định nghĩa cấu trúc tiêu chuẩn của tất cả các luồng sự kiện (Events) được phát đi (Emitted) từ Cart Service thông qua Redis Streams Outbox và Kafka.

Kiến trúc sự kiện này đảm nhận nhiệm vụ đồng bộ bất đồng bộ từ [Primary Store (Redis)](cart_caching_schema.md) xuống [Cold Store (Postgres)](cart_db_schema.md) để tránh thất thoát dữ liệu, cũng như phục vụ các hệ thống downstream (Promotion, Analytics, Notification...) subscribe event để phản ứng theo logic riêng.

### Bản Đồ Kafka Topics

**Topics Produce:**

| Topic Name | Partition Key | Consumers | Mô tả |
|:---|:---|:---|:---|
| `t.cart.events` | `cart_id` | Cart (Postgres Sync), Analytics, Notification | Cart item events qua Redis Streams Outbox |

**Topics Consume:**

| Topic Name | Consumer Group | Producer | Events | Mô tả |
|:---|:---|:---|:---|:---|
| `t.order.events` | `cart-order-consumer` | Order Service | `ORDER_PAID` | Xóa SKU đã mua khỏi giỏ. Xem [Worker #6](cart_workers.md#6-order-event-consumer-worker-kafka-consumer) |

---

## 1. Cấu trúc Message Header chung (Kafka/Outbox Wrapper)
Tất cả các tin nhắn đẩy vào Redis Stream (`XADD`) và khi Forward tới Kafka đều mang theo cấu trúc dạng Map (Key-Value) để dễ bề điều hướng:

*   `event_id`: Định danh sự kiện sinh ở tầng [Outbox Relay Worker](cart_workers.md#1-outbox-relay-producer-worker) theo cơ chế deterministic từ `Redis Stream ID + cart_id`, dùng để Trace log và dedup ở downstream.
*   `event_type`: Loại sự kiện (Ví dụ: `CART_ITEM_ADDED`, `CART_CLEARED`...).
*   `aggregate_type`: Loại Aggregate Root (VD: `Cart`).
*   `aggregate_id`: ID của entity chủ thể (Cart ID).
*   `correlation_id`: ID để truy vết (thường lấy từ upstream hoặc user_id/session_id).
*   `timestamp`: Thời điểm sinh ra event (ISO 8601 UTC).
*   `source`: Định danh service gốc phát event (Cố định: `cart-service`).
*   `payload`: Nội dung chi tiết biến động dữ liệu (ở Redis Stream là chuỗi JSON; sau Relay sang Kafka là object JSON đã parse).

> **Lưu ý quan trọng:** Ở tầng Redis Stream (Outbox), message chỉ chứa `event_type` và `payload`. Các field `event_id`, `aggregate_type`, `aggregate_id`, `correlation_id`, `timestamp`, `source` được **Relay Worker bổ sung** khi forward sang Kafka. Xem chi tiết tại [Outbox Pattern](cart_outbox_pattern.md#21-kiến-trúc-luồng-mới).

## 2. Chi tiết các loại Event (Event Types) & Phân tích Payload

Dưới đây là mô tả chi tiết của phần tử JSON `payload` tùy thuộc vào từng loại `event_type`.

> **Quy ước nhất quán dữ liệu:** `current_price` là field runtime của API đọc giỏ, không được lưu trong Redis Hash Value và không nằm trong payload event Kafka.

### 2.1. CART_ITEM_ADDED
**Ngữ cảnh phát sinh:** Khách hàng thêm mới một mã sản phẩm (SKU) chưa từng tồn tại trong giỏ hàng hiện tại.
**Mục đích DB Consumer:** Tạo mới dòng bản ghi (Insert) vào bảng `cart_items`. Đồng thời **re-activate** bảng `carts` (`status = 'ACTIVE'`) và cập nhật `updated_at` theo event time mới nhất.

**Cấu trúc Payload (JSON):**
```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174000",
  "event_type": "CART_ITEM_ADDED",
  "aggregate_type": "Cart",
  "aggregate_id": "cart:user:12345",
  "correlation_id": "req-123",
  "timestamp": "2026-02-28T10:00:00Z",
  "source": "cart-service",
  "payload": {
    "cart_id": "{cart:user:12345}",
    "sku_id": "sku-1001",
    "user_id": "12345",
    "product_id": "prod-uuid",
    "seller_id": "seller-uuid",
    "seller_name": "Shop Tech Việt",
    "quantity": 2,
    "unit_price_at_add": 150000.00,
    "product_name": "Áo Thun Nam",
    "variant_info": "Màu Đen, Size L",
    "image_url": "https://cdn.example.com/item-image.jpg",
    "is_available": true,
    "added_at": "1708530000123",
    "updated_at": "1708530000123",
    "product_updated_at": "1708530000123"
  }
}
```
*(Ghi chú: Event payload bao gồm `user_id` để Postgres Sync Worker có thể upsert bảng `carts` mà không cần parse chuỗi `cart_id`. Trường `unit_price_at_add` lưu ở cột riêng trong DB, các trường còn lại (đời vào `product_snapshot` JSONB). Timestamps dùng **Milliseconds Epoch** (13 chữ số).*

### 2.2. CART_ITEM_UPDATED
**Ngữ cảnh phát sinh:** 
1. Khách hàng thay đổi số lượng sản phẩm (bấm tăng/giảm).
**Mục đích DB Consumer:** Cập nhật lại số lượng và/hoặc nội dung snapshot sản phẩm vào bảng `cart_items`. Đồng thời **re-activate** `carts` (`status = 'ACTIVE'`) nếu trước đó đang `DELETED`/`CHECKED_OUT`/`ABANDONED`. Cấu trúc JSON Payload *giống hệt* `CART_ITEM_ADDED`, nhưng `quantity`, nội dung snapshot và `updated_at` đã được làm mới. Đảm bảo Luỹ đẳng (`updated_at` event phải lớn hơn `updated_at` trong DB).

**Cấu trúc Payload (JSON):**
```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174001",
  "event_type": "CART_ITEM_UPDATED",
  "aggregate_type": "Cart",
  "aggregate_id": "cart:user:12345",
  "correlation_id": "req-124",
  "timestamp": "2026-02-28T10:05:00Z",
  "source": "cart-service",
  "payload": {
    "cart_id": "{cart:user:12345}",
    "sku_id": "sku-1001",
    "user_id": "12345",
    "product_id": "prod-uuid",
    "seller_id": "seller-uuid",
    "seller_name": "Shop Tech Việt",
    "quantity": 5,
    "unit_price_at_add": 150000.00,
    "product_name": "Áo Thun Nam",
    "variant_info": "Màu Đen, Size L",
    "image_url": "https://cdn.domain.com/abc.jpg",
    "is_available": true,
    "added_at": "1708530000123",
    "updated_at": "1708616400456",
    "product_updated_at": "1708616400456"
  }
}
```

### 2.3. CART_ITEM_DELETED
**Ngữ cảnh phát sinh:** Khách hàng chủ động nhấn nút Xóa rác (`HDEL`), hoặc tích chọn vài món đem đi Checkout dẫn đến giỏ hàng bị trừ đi các SKU này. Phục vụ cho tính năng [Batch Delete trong Caching Schema](cart_caching_schema.md#33-xóa-item-batch-delete-sau-checkout-hoặc-remove).
**Mục đích DB Consumer:** Đánh dấu xóa logic (Soft Delete/Tombstone) cho dòng sản phẩm `item_id = cart_id + sku_id` trong bảng `cart_items`. Áp dụng [Out-of-order validation](cart_idempotency.md#24-xử-lý-sai-thứ-tự-cho-delete-event) để đảm bảo không xóa nhầm item vừa được re-add.

> **Quy ước nghiệp vụ:** `CART_ITEM_DELETED` luôn biểu diễn thao tác xóa **toàn bộ dòng item theo `sku_id`** khỏi giỏ; không phải giảm từng đơn vị `quantity`.

**Cấu trúc Payload (JSON):**
```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174002",
  "event_type": "CART_ITEM_DELETED",
  "aggregate_type": "Cart",
  "aggregate_id": "cart:user:12345",
  "correlation_id": "req-125",
  "timestamp": "2026-02-28T10:10:00Z",
  "source": "cart-service",
  "payload": {
    "cart_id": "{cart:user:12345}",
    "sku_id": "sku-1001",
    "user_id": "12345",
    "deleted_at": "1708616400899"
  }
}
```

> **Quan trọng:** `deleted_at` (Tính bằng **Milliseconds Epoch**; với Rate Limit 100 req/s/user, collision trong 1ms là bất khả thi) phải được so sánh với `updated_at` trong `product_snapshot` (JSONB) trên PostgreSQL. Chỉ xóa nếu `deleted_at > (product_snapshot->>'updated_at')::bigint` để tránh xóa nhầm item vừa được thêm lại sau khi event DELETE bị trễ (Out-of-order).

### 2.4. CART_CLEARED
**Ngữ cảnh phát sinh:** 
Gửi sau cùng khi kết thúc một lô xóa mặt hàng (`HDEL`) mà dẫn đến giỏ hàng không còn món đồ nào (`HLEN == 0`), Key Redis của giỏ sẽ tự động bị tiêu hủy (lệnh `DEL`).
**Mục đích DB Consumer:** Chuyển đổi trạng thái `status` của master table `carts` từ `ACTIVE` sang `DELETED` theo cơ chế **guard event-time**. Chỉ cập nhật nếu `cleared_at` mới hơn hoặc bằng mốc hiện tại của cart; event cũ hơn phải bỏ qua để tránh out-of-order. Giúp cho CronJob dọn rác phân biệt được trạng thái để [xóa rác lạnh 3 tháng thay vì 6 tháng](cart_db_schema.md#4-quản-lý-không-gian-archival--cold-data-policy).

**Cấu trúc Payload (JSON):**
```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174003",
  "event_type": "CART_CLEARED",
  "aggregate_type": "Cart",
  "aggregate_id": "cart:user:12345",
  "correlation_id": "req-126",
  "timestamp": "2026-02-28T10:15:00Z",
  "source": "cart-service",
  "payload": {
    "cart_id": "{cart:user:12345}",
    "user_id": "12345",
    "cleared_at": "1708616500000"
  }
}
```

### 2.5. CART_CHECKED_OUT
**Ngữ cảnh phát sinh:**
Khi [Order Event Consumer Worker (#6)](cart_workers.md#6-order-event-consumer-worker-kafka-consumer) nhận event `ORDER_PAID` từ Order Service, Worker gọi Lua Batch Delete để xóa các SKU đã mua khỏi Redis. Nếu sau khi xóa giỏ hàng rỗng hoàn toàn (`HLEN == 0`), Lua Script emit event này vào Outbox Stream (với `ARGV[2] = "CART_CHECKED_OUT"`).

Khác với `CART_CLEARED` (phát sinh khi user chủ động xóa hết sản phẩm → trạng thái `DELETED`), event này phản ánh ngữ nghĩa "đã thanh toán thành công".

**Mục đích DB Consumer:** Chuyển đổi trạng thái `status` của master table `carts` từ `ACTIVE` sang `CHECKED_OUT` theo cơ chế **guard event-time**. Chỉ cập nhật nếu `checked_out_at` mới hơn hoặc bằng mốc hiện tại của cart; event cũ hơn phải bỏ qua. Giúp CronJob [Data Archival Cleaner](cart_workers.md#4-data-archival-cleaner-batch-cronjob) phân loại đúng vòng đời (hard-delete sau 3 tháng thay vì 6 tháng).

**Cấu trúc Payload (JSON):**
```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174005",
  "event_type": "CART_CHECKED_OUT",
  "aggregate_type": "Cart",
  "aggregate_id": "cart:user:12345",
  "correlation_id": "wf-10001",
  "timestamp": "2026-02-28T10:25:00Z",
  "source": "cart-service",
  "payload": {
    "cart_id": "{cart:user:12345}",
    "user_id": "12345",
    "checked_out_at": "1708616700000"
  }
}
```

> **Lưu ý contract:** Event này được phát từ Lua Batch Delete sau khi nhận `ORDER_PAID` và giỏ rỗng hoàn toàn. Payload tối thiểu chỉ cần `cart_id` + `checked_out_at` để Postgres Sync chuyển trạng thái.

> **Chú ý phân biệt:**
> | Event | Nguyên nhân | DB status |
> |:---|:---|:---|
> | `CART_CLEARED` | User xóa hết sản phẩm thủ công | `DELETED` |
> | `CART_CHECKED_OUT` | Tất cả sản phẩm đã mua thành công qua `ORDER_PAID` | `CHECKED_OUT` |

### 2.6. CART_ABANDONED
**Ngữ cảnh phát sinh:** [Abandoned Cart Marker CronJob](cart_workers.md#3-abandoned-cart-marker-batch-cronjob) quét bảng `carts` và chuyển các giỏ `ACTIVE` không tương tác trong 30 ngày sang `ABANDONED`.
**Mục đích downstream:** Promotion/Notification/Analytics dùng để kích hoạt flow chăm sóc lại người dùng.

**Cấu trúc Payload (JSON):**
```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174006",
  "event_type": "CART_ABANDONED",
  "aggregate_type": "Cart",
  "aggregate_id": "cart:user:12345",
  "correlation_id": "cron-20260315-020000",
  "timestamp": "2026-03-15T02:00:00Z",
  "source": "cart-service",
  "payload": {
    "cart_id": "{cart:user:12345}",
    "user_id": "12345",
    "abandoned_at": "1773530400000",
    "subtotal": 320000.00
  }
}
```

---

## 3. Chi Tiết Các Sự Kiện Tiêu Thụ (Consumed Events)

Phần này định nghĩa cấu trúc các event mà Cart Service **lắng nghe** từ các service khác để phản ứng xử lý nội bộ. Các event này được xử lý bởi [Worker #6](cart_workers.md#6-order-event-consumer-worker-kafka-consumer) còn dữ liệu Product sẽ được cập nhật theo cơ chế Pull (gRPC) khi người dùng truy cập.

---

### 3.1. ORDER_PAID (Từ Order Service)
**Topic:** `t.order.events`
**Consumer Group:** `cart-order-consumer`
**Xử lý bởi:** [Order Event Consumer Worker (#6)](cart_workers.md#6-order-event-consumer-worker-kafka-consumer)
**Nguồn gốc:** [Order Events - ORDER_PAID](../order/order_events.md#32-order_paid)

**Hành động của Cart Service:**
1. Extract `user_id`, `cart_id` và danh sách `sku_ids` từ payload.
2. Convert `payload.paid_at` (ISO-8601 UTC) sang `checkout_timestamp_ms` bằng `time.Parse(time.RFC3339, paidAt).UTC().UnixMilli()` trước khi gọi Lua Batch Delete.
3. Gọi Lua Batch Delete với `clear_event_type = "CART_CHECKED_OUT"` để xóa các SKU đã mua khỏi Redis Hash.
4. Nếu giỏ rỗng hoàn toàn: phát `CART_CHECKED_OUT` → Postgres Sync cập nhật `status = 'CHECKED_OUT'`.

**Cấu trúc Payload kỳ vọng (chỉ các trường Cart Service dùng):**
```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174001",
  "event_type": "ORDER_PAID",
  "aggregate_type": "Order",
  "aggregate_id": "10001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-02-28T10:05:00Z",
  "source": "order-service",
  "payload": {
    "order_id": 10001,
    "user_id": 999,
    "cart_id": "cart:user:999",
    "status": "PAID",
    "sku_ids": ["sku-001", "sku-002"],
    "paid_at": "2026-02-28T10:05:00Z"
    // paid_at là ISO-8601 UTC — Worker phải convert sang epoch milliseconds (UTC)
    // Dùng: time.Parse(time.RFC3339, paidAt) rồi .UTC().UnixMilli()
  }
}
```

> **Contract ổn định:** Cart dùng duy nhất `cart_id` + `sku_ids` để xóa item, không parse chuỗi ghép có dấu `:` nhằm tránh sai lệch định danh.

> **Idempotency:** Worker truyền `cart:idemp:order_paid:{order_id}` vào Lua Batch Delete. Lua sẽ tự set key trạng thái `PROCESSING` bằng `SET ... NX EX 300` ngay đầu script. Nếu đã có key thì skip an toàn. Khi script hoàn tất, key được đổi sang `DONE` với TTL 24h để chặn redelivery.

---
