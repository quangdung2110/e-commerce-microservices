# Inventory Service - Định Nghĩa Các Sự Kiện & Kafka Topics

**Liên kết nhanh (Related Documents):**
* [Database Schema](inventory_db_design.md)
* [Redis Caching & Lua Scripts](inventory_redis_design.md)
* [Workflows (API & Data Flow)](inventory_workflow.md)
* [Background Workers & Jobs](inventory_workers.md)
* [Smart Routing Engine](smart_routing_engine.md)

**Cross-Service References:**
* [Product Events - Consumed](../product/product_events.md) — Product consume `INVENTORY_STOCK_CHANGED` (cập nhật display cache)
* [Order Workflow](../order/order_workflow.md#8-workflow-refund-return-flow) — Order consume `INVENTORY_RETURN_RECEIVED_ALL` để mở bước gọi Payment refund; Order consume `INVENTORY_SHIP_TO_CUSTOMER` để trigger item FSM PROCESSING → DELIVERED

---

Inventory Service chỉ produce events (không consume từ service ngoài sau Phase 14). Xem chi tiết cơ chế tại [§2.2](inventory_events.md#22-topics-consume).


## 1. Bản Đối Chiếu Event

| Event mới | Producer | Partition Key |
|:---|:---|:---|
| `INVENTORY_STOCK_MUTATION` (`change_type = ORDER_RESERVED`) | Redis Stream Relay Worker | `sku_id` |
| `INVENTORY_STOCK_MUTATION` (`change_type = ORDER_DEDUCTED`) | Redis Stream Relay Worker | `sku_id` |
| `INVENTORY_STOCK_MUTATION` (`change_type = ORDER_CANCELLED`) | Redis Stream Relay Worker | `sku_id` |
| `INVENTORY_STOCK_MUTATION` (`change_type = RESERVATION_EXPIRED`) | Redis Stream Relay Worker | `sku_id` |
| `INVENTORY_STOCK_CHANGED` (với `change_type` tương ứng) | Debezium CDC (DB outbox) | `sku_id` |
| `INVENTORY_RETURN_RECEIVED_ALL` (giữ nguyên) | Debezium CDC (DB outbox) | `order_id` |
| `INVENTORY_SHIP_TO_CUSTOMER` (Phase 14 — mới) | Debezium CDC (DB outbox) | `order_id` |

**Cơ chế Produce theo loại Event:**

| Event | Produce Method | Lý do |
|:---|:---|:---|
| `INVENTORY_STOCK_MUTATION` | **Redis Stream Outbox + Relay Worker** | Atomic với Redis mutation trong Lua script |
| `INVENTORY_STOCK_CHANGED` | **PostgreSQL Outbox Pattern** | Đồng bộ chặt với DB write; aggregate per-SKU đã chuẩn hoá |
| `INVENTORY_RETURN_RECEIVED_ALL` | **PostgreSQL Outbox Pattern** | Xác nhận nghiệp vụ đã nhận đủ toàn bộ item refund để Order mở bước refund tiền |
| `INVENTORY_SHIP_TO_CUSTOMER` | **PostgreSQL Outbox Pattern** | Signal nghiệp vụ cho Order Service chuyển `order_items.status` PROCESSING → DELIVERED |

> **Quy tắc chống duplicate:** Lua script trả mã `2` khi idempotent hit. Backend và Relay Worker không được phát thêm event mới cho idempotent hit.

---

## 2. Bản Đồ Kafka Topics

### 2.1 Topic Produce

| Topic Name | Partition Key | Consumers | Mô tả |
|:---|:---|:---|:---|
| `t.inventory.events` | `sku_id` cho stock events, `order_id` cho return | Inventory Ledger Worker (internal), Product Service, Order Service, Analytics | Tất cả event inventory (mutation + public) |

### 2.2 Topics Consume

> **Phase 14 đã loại bỏ:** Inventory Service không còn consume `t.shipping.events` (Shipping Service đã bị xóa). Trigger restock giờ là admin API `POST /v1/inventory/restock` với `reference_type='ORDER_RETURNED_RESTOCK'`.

---

## 3. Cấu Trúc Message Header Chung (CloudEvents Wrapper)

Tất cả messages phát lên Kafka đều tuân theo chuẩn CloudEvents (xem canonical tại [Service Contract Matrix §0.2](../_contracts/CONTRACT_MATRIX.md#02-kafka-envelope-cloudevents-wrapper)):

* `event_id`: UUID của sự kiện.
* `event_type`: Loại sự kiện.
* `aggregate_type`: Cố định `Inventory`.
* `aggregate_id`: `sku_id` với sự kiện mức SKU, hoặc `order_id` với `INVENTORY_RETURN_RECEIVED_ALL` và `INVENTORY_SHIP_TO_CUSTOMER`.
* `correlation_id`: Temporal Workflow ID hoặc upstream request ID.
* `timestamp`: ISO 8601 UTC.
* `source`: Cố định `inventory-service`.
* `schema_version`: Bắt buộc `1` (xem [Service Contract Matrix §0.2](../_contracts/CONTRACT_MATRIX.md#02-kafka-envelope-cloudevents-wrapper)).
* `payload`: JSON Object chi tiết.

---

## 4. Events Phát Đi (Produced Events)

### 4.1. INVENTORY_STOCK_MUTATION

**Ngữ cảnh:** Lua script Reserve/Deduct/Release trả `1`. Đây là event cấp thấp (per-warehouse) phục vụ Ledger Worker ghi sổ cái.
**Consumer chính:** Inventory Ledger Worker (internal).

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174000",
  "event_type": "INVENTORY_STOCK_MUTATION",
  "aggregate_type": "Inventory",
  "aggregate_id": "sku-001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-02-28T10:05:00Z",
  "source": "inventory-service",
  "schema_version": 1,
  "payload": {
    "sku_id": "sku-001",
    "product_id": "prod-uuid-001",
    "warehouse_id": 5,
    "change_type": "ORDER_RESERVED",
    "quantity": 2,
    "available_after": 798,
    "reserved_after": 2,
    "order_id": 10001,
    "order_item_id": 2001
  }
}
```

`change_type` hợp lệ:

| Value | Nguồn |
|:---|:---|
| `ORDER_RESERVED` | Script Reserve |
| `ORDER_DEDUCTED` | Script Deduct |
| `ORDER_CANCELLED` | Script Release (do Temporal compensation) |
| `RESERVATION_EXPIRED` | Script Release (do Temporal timeout) — hiện do Order compensation xử lý, nhưng enum giữ để tương thích |

**Contract note:** Với mọi event phát sinh từ flow đơn hàng, `order_item_id` là bắt buộc và không được null.

> **Phạm vi `change_type` của `INVENTORY_STOCK_MUTATION`:** Enum này chỉ chứa 4 giá trị phát sinh từ Lua scripts (Reserve/Deduct/Release). Các biến động tồn kho từ `ADMIN_RESTOCK` / `ORDER_RETURNED_RESTOCK` không qua Lua → chỉ phát `INVENTORY_STOCK_CHANGED` với các `change_type` tương ứng, không emit `INVENTORY_STOCK_MUTATION`. Consumer cần đủ cả 6 giá trị thì consume `INVENTORY_STOCK_CHANGED`.

> **Lưu ý chung về timestamp trên event:**
> - **Wrapper `timestamp`** (ISO 8601 UTC) là thời điểm phát event chính thức — áp dụng cho TẤT CẢ 4 event (`INVENTORY_STOCK_MUTATION`, `INVENTORY_STOCK_CHANGED`, `INVENTORY_RETURN_RECEIVED_ALL`, `INVENTORY_SHIP_TO_CUSTOMER`).
> - **Payload KHÔNG chứa** `event_timestamp`, `timestamp_iso`, `occurred_at` (đã loại bỏ để tránh trùng lặp với wrapper).
> - Consumer cần epoch_ms thì convert từ wrapper `timestamp` (parse ISO 8601 sang epoch).

> **Lưu ý phân biệt field `available_after` giữa 2 event liên quan:**
> 
> | Event | Field | Scope | Mục đích |
> |:---|:---|:---|:---|
> | `INVENTORY_STOCK_MUTATION` (event này) | `available_after` | **per-warehouse** (chỉ kho vừa mutate) | Nội bộ Inventory — Ledger Worker dùng để ghi nhận balance tại kho |
> | `INVENTORY_STOCK_CHANGED` (xem §4.2) | `available_quantity` | **per-SKU aggregate** (tổng mọi kho) | Public — Product Service dùng để hiển thị tổng tồn kho |
> 
> Hai field **khác scope** dù cùng đo lường "khả dụng". Consumer Product/Analytics chỉ consume `INVENTORY_STOCK_CHANGED.available_quantity` cho UI; không dùng `INVENTORY_STOCK_MUTATION.available_after` cho display.

#### 4.1.1. Kiến trúc Event Streaming (Lua → Kafka commands)

Đây là event cấp thấp (per-warehouse) sinh ra từ Redis Lua scripts trong đường nóng Reserve/Deduct/Release. Toàn bộ luồng đảm bảo atomic: mutation Redis đã apply VÀ event đã được queue trong cùng một Lua execution, không có khả năng mất event khi crash giữa chừng.

| Bước | Thành phần | Hành động |
|:---:|:---|:---|
| 1 | Lua script (Reserve / Deduct / Release) | Mutate Redis stock hash `inventory:physic:{sku:wh}:stock` (HINCRBY `available` / `reserved` / `total`) |
| 2 | Lua script | `XADD` event vào Redis Stream Outbox `inventory:stream:{sku:wh}:events` cùng hash slot với stock hash |
| 3 | Redis Stream Relay Worker | Đọc stream, gắn CloudEvents header, forward sang Kafka topic `t.inventory.events` với `key = sku_id` |
| 4 | Kafka Ledger Worker | Consume event, ghi `inventory_ledger` (UNIQUE `event_id` làm idempotency) + UPSERT `inventory_reservations` + cập nhật `physical_inventory` + insert `inventory_outbox` (cho `INVENTORY_STOCK_CHANGED`) trong cùng 1 PostgreSQL transaction |

**Lưu ý vận hành:**

- Bước 1 + 2 atomic nhờ Lua single-thread execution. Cả stock hash, idempotency key, stream outbox, suspension check phải cùng hash slot (xem [inventory_redis_design.md §5](inventory_redis_design.md)).
- Bước 3 stream `XACK` chỉ xảy ra sau khi Kafka produce xác nhận. Lỗi Kafka dẫn tới retry với exponential backoff; nếu crash giữa chừng, message ở lại stream và được re-poll bởi Relay Worker (consumer group `inventory-stream-relay`).
- Bước 4 Ledger Worker filter theo `event_type = INVENTORY_STOCK_MUTATION` và ignore `INVENTORY_STOCK_CHANGED` / `INVENTORY_RETURN_RECEIVED_ALL` để tránh vòng lặp vô hạn.

**Quy tắc chống duplicate:** Lua script trả mã `2` khi idempotent hit. Backend và Relay Worker không được phát thêm event mới cho idempotent hit.

### 4.2. INVENTORY_STOCK_CHANGED

**Ngữ cảnh:** Sau khi Ledger Worker ghi `inventory_ledger` + cập nhật `physical_inventory` -> insert `inventory_outbox` -> Debezium CDC publish Kafka.
**Consumer chính:** Product Service (update display cache).

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174003",
  "event_type": "INVENTORY_STOCK_CHANGED",
  "aggregate_type": "Inventory",
  "aggregate_id": "sku-001",
  "correlation_id": "sys-abc",
  "timestamp": "2026-02-28T10:05:01Z",
  "source": "inventory-service",
  "schema_version": 1,
  "payload": {
    "product_id": "prod-uuid-001",
    "sku_id": "sku-001",
    "available_quantity": 798,
    "change_type": "ORDER_RESERVED"
  }
}
```

> **Lưu ý timestamp:** Event này dùng **chỉ CloudEvents wrapper `timestamp`** (ISO 8601 UTC) làm thời điểm phát. Payload KHÔNG chứa thêm `event_timestamp` hay `timestamp_iso` để tránh trùng lặp với wrapper. Consumer nào cần epoch_ms thì convert từ wrapper `timestamp`.

`available_quantity` ở đây là tổng khả dụng per-SKU (sum từ `physical_inventory` tại emit time). `change_type` hợp lệ:

| Value | Nguồn |
|:---|:---|
| `ORDER_RESERVED` | sau Ledger Worker ghi reservation |
| `ORDER_DEDUCTED` | sau Ledger Worker ghi deduct |
| `ORDER_CANCELLED` | sau Ledger Worker ghi release |
| `RESERVATION_EXPIRED` | sau Ledger Worker ghi release do timeout |
| `ADMIN_RESTOCK` | Restock API |
| `ORDER_RETURNED_RESTOCK` | Admin Restock với `reference_type='ORDER_RETURNED_RESTOCK'` (Phase 14: không còn SHIPMENT_RETURNED consumer) |

**Contract note:** Producer bắt buộc gửi `product_id` + `sku_id`. Consumer Product vẫn validate cặp khóa để chặn drift.

#### 4.2.1. Kiến trúc Event Streaming (DB outbox → Kafka public)

Event này đại diện cho tổng tồn kho khả dụng per-SKU (aggregate) sau khi Ledger Worker / Restock API đã ghi xong PostgreSQL. Được phát đi thông qua **PostgreSQL Outbox Pattern + Debezium CDC** — đảm bảo đồng bộ chặt với DB write và zero load lên application.

| Bước | Thành phần | Hành động |
|:---:|:---|:---|
| 1 | Ledger Worker / Restock API | Mở PostgreSQL transaction, UPDATE `physical_inventory` (reserved_quantity / actual_quantity) + INSERT `inventory_ledger` + UPSERT `inventory_reservations` |
| 2 | Cùng transaction | INSERT vào bảng append-only `inventory_outbox` với `event_type = INVENTORY_STOCK_CHANGED`, `partition_key = sku_id`, payload chứa `product_id`, `sku_id`, `available_quantity`, `change_type` |
| 3 | PostgreSQL | COMMIT transaction — tất cả bước 1-3 atomic |
| 4 | Debezium CDC Connector | Đọc WAL qua Logical Replication Slot, extract `partition_key` làm Kafka message key (KHÔNG bóc JSON payload) |
| 5 | Debezium CDC | Produce message lên Kafka topic `t.inventory.events`, partition theo `sku_id`, độ trễ < 5ms |
| 6 | Product Service (downstream) | Consume `INVENTORY_STOCK_CHANGED`, cập nhật display cache |

**Lưu ý vận hành:**

- Cùng 1 transaction: hoặc tất cả bản ghi (`inventory_ledger`, `physical_inventory`, `inventory_outbox`) đều được ghi, hoặc không ghi gì cả — không thể có trạng thái "ledger đã ghi nhưng outbox chưa ghi".
- Debezium đọc trực tiếp từ WAL nên offload hoàn toàn khỏi application CPU. Không cần polling job, không cần UPDATE status trong DB.
- Kafka key = `sku_id` đảm bảo ordering per-SKU. Consumer (Product Service) vẫn phải idempotent theo `event_id` để chặn replay.
- Consumer validate cặp `product_id` + `sku_id` để chặn drift (xem Contract note phía trên).

**Tại sao không produce trực tiếp từ Ledger Worker?** Việc để Debezium CDC đọc WAL đảm bảo:

- Event publish ngay khi WAL flush (latency < 5ms so với polling 100ms+).
- Zero additional load trên PostgreSQL application.
- Crash giữa commit DB và produce Kafka không làm mất event — Debezium commit offset cùng transaction, replay sẽ tiếp tục từ offset chưa ack.

**Cleanup:** Bảng `inventory_outbox` được dọn bởi Outbox Cleanup Job (CronJob hàng ngày) — chỉ xoá các row đã được Debezium đọc xong (track offset watermark) hoặc dùng TTL 7 ngày conservative.

### 4.3. INVENTORY_RETURN_RECEIVED_ALL

**Ngữ cảnh:** Inventory đã nhận đủ toàn bộ item thuộc refund scope đã được Order đăng ký trước đó (qua `POST /v1/inventory/returns/refund-expectations`).
**Consumer chính:** Order Service (mở bước gọi Payment refund).

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174004",
  "event_type": "INVENTORY_RETURN_RECEIVED_ALL",
  "aggregate_type": "Inventory",
  "aggregate_id": "10001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-03-05T09:58:30Z",
  "source": "inventory-service",
  "schema_version": 1,
  "payload": {
    "order_id": 10001,
    "refund_request_id": "fdf4d7d3-4d35-4a61-a55a-8d2b1a315b6a",
    "order_item_ids": [2001],
    "confirmed_at": "2026-03-05T09:58:30Z"
  }
}
```

`INVENTORY_RETURN_RECEIVED_ALL` chỉ phát đúng 1 lần cho mỗi `order_id + refund_request_id` sau khi đủ toàn bộ item; downstream phải idempotent theo `event_id` và `refund_request_id`.

#### 4.3.1. Kiến trúc Event Streaming (DB outbox → Kafka public)

Event này là signal nghiệp vụ để Order mở bước gọi Payment refund sau khi đã nhận đủ toàn bộ item refund về kho. Cơ chế phát i tương tự `INVENTORY_STOCK_CHANGED`: **PostgreSQL Outbox Pattern + Debezium CDC**.

| Bước | Thành phần | Hành động |
|:---:|:---|:---|
| 1 | Admin (qua `POST /v1/inventory/restock` với `reference_type='ORDER_RETURNED_RESTOCK'`) | Request nhập hàng hoàn về kho, body mang theo `order_id`, `order_item_id`, `refund_request_id` |
| 2 | API Layer | Cùng PostgreSQL transaction: UPDATE `physical_inventory.actual_quantity` (cộng `quantity` trả về, có `ON CONFLICT` upsert) + UPDATE `inventory_reservations` set `refund_returned_at = NOW()` cho row match `(order_id, order_item_id, status='DEDUCTED', refund_returned_at IS NULL)` |
| 3 | Cùng transaction | INSERT `inventory_ledger` với `reference_type = ORDER_RETURNED_RESTOCK` |
| 4 | Cùng transaction | INSERT `inventory_outbox` với `event_type = INVENTORY_STOCK_CHANGED`, `partition_key = sku_id` (cho Product Service) |
| 5 | Cùng transaction | Refund completion check: `SELECT COUNT(*) FROM inventory_reservations WHERE order_id = :order_id AND refund_request_id = :refund_request_id AND refund_expected = TRUE AND status = 'DEDUCTED' AND refund_returned_at IS NULL` |
| 6 | Nếu completion count = 0 | INSERT `inventory_outbox` với `event_type = INVENTORY_RETURN_RECEIVED_ALL`, `partition_key = order_id`, payload chứa `order_id`, `refund_request_id`, `order_item_ids`, `confirmed_at`. `order_item_ids` aggregate từ các `order_item_id` có `refund_expected = TRUE AND refund_returned_at IS NOT NULL` (tại emit time = toàn bộ refund scope, vì completion check `pending = 0` đảm bảo). Có unique constraint `(order_id, refund_request_id) WHERE event_type = 'INVENTORY_RETURN_RECEIVED_ALL'` chống duplicate emit. |
| 7 | PostgreSQL | COMMIT transaction |
| 8 | Debezium CDC | Đọc WAL, produce message lên Kafka `t.inventory.events` với `key = order_id` (cho event RETURN_RECEIVED_ALL) hoặc `key = sku_id` (cho event STOCK_CHANGED) |

**Lưu ý vận hành:**

- Bước 1-7 cùng 1 transaction: đảm bảo inventory đã được restock VÀ ledger đã ghi VÀ outbox đã có event outbox trước khi commit.
- Bước 5-6 chỉ emit `INVENTORY_RETURN_RECEIVED_ALL` tối đa 1 lần cho mỗi `(order_id, refund_request_id)`. Khi admin restock item cuối cùng của `refund_request_id`, count = 0 → insert outbox. Lần restock tiếp theo cùng `(order_id, refund_request_id)`: nếu `refund_expected = TRUE` row nào đã có `refund_returned_at IS NOT NULL` thì count > 0, không insert. Nếu không còn row `refund_expected = TRUE` (do `REFUND_NOT_EXPECTED` chẳng hạn) thì unique constraint `(order_id, refund_request_id) WHERE event_type = 'INVENTORY_RETURN_RECEIVED_ALL'` chặn duplicate emit (xem [DB §1.5](inventory_db_design.md#15-inventory_outbox)).
- Bước 8 Kafka key = `order_id` cho event RETURN_RECEIVED_ALL đảm bảo ordering per-order; key = `sku_id` cho event STOCK_CHANGED đảm bảo ordering per-SKU. Product Service filter theo `event_type` để chỉ consume STOCK_CHANGED, Order Service filter để chỉ consume RETURN_RECEIVED_ALL.
- Consumer Order Service phải idempotent theo `event_id` và `refund_request_id` vì Kafka delivery có thể retry.

**Tại sao không có ledger write riêng cho `INVENTORY_RETURN_RECEIVED_ALL`?** Đây là event tín hiệu nghiệp vụ, không phải biến động tồn kho. Biến động tồn kho đã được ghi vào `inventory_ledger` ở bước 3 với `reference_type = ORDER_RETURNED_RESTOCK`. Việc insert thêm một `inventory_ledger` row cho `INVENTORY_RETURN_RECEIVED_ALL` sẽ gây trùng lặp audit và vi phạm invariant "mỗi event chỉ tạo 1 ledger entry" (xem [inventory_db_design.md §1.4](inventory_db_design.md)).

**Quan hệ với `INVENTORY_STOCK_CHANGED`:** Cùng 1 lần admin restock `ORDER_RETURNED_RESTOCK` có thể emit 2 outbox row:

1. `INVENTORY_STOCK_CHANGED` với `change_type = ORDER_RETURNED_RESTOCK` — cho Product Service cập nhật display cache.
2. `INVENTORY_RETURN_RECEIVED_ALL` (nếu là lần cuối cùng) — cho Order Service mở bước refund tiền.

Hai event này hoàn toàn độc lập về mặt nghiệp vụ, không thay thế nhau.

---

### 4.4. INVENTORY_SHIP_TO_CUSTOMER

**Ngữ cảnh:** Admin xác nhận kho đã xuất hàng cho khách (qua API `POST /v1/inventory/ship-to-customer`). Trigger này thay thế cho `SHIPMENT_STATUS_UPDATED` từ Shipping Service (đã bị xóa).
**Consumer chính:** Order Service (`order-inventory-ship-consumer`) → cập nhật `order_items.status = DELIVERED`.

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174005",
  "event_type": "INVENTORY_SHIP_TO_CUSTOMER",
  "aggregate_type": "Inventory",
  "aggregate_id": "10001",
  "correlation_id": "wf-10001",
  "timestamp": "2026-03-02T16:00:00Z",
  "source": "inventory-service",
  "schema_version": 1,
  "payload": {
    "order_id": 10001,
    "warehouse_id": 5,
    "shipped_at": "2026-03-02T16:00:00Z",
    "courier_note": "Đã bàn giao cho đơn vị vận chuyển nội bộ"
  }
}
```

> Order Service consumer xác định `order_item_ids[]` cần chuyển sang `DELIVERED` bằng cách query `inventory_reservations` theo `order_id` trong payload, lọc các row có `status = 'DEDUCTED'`. Mỗi `order_item_id` tương ứng sẽ được `UPDATE order_items.status PROCESSING → DELIVERED` (idempotent theo `event_id`).
>
> Lưu ý: `inventory_reservations.status` chỉ có 3 giá trị `RESERVED / DEDUCTED / CANCELLED`. Cụm từ "PROCESSING state of order_item" ở các bản trước là sai — `PROCESSING` thuộc về `order_items.status` bên Order Service, không thuộc inventory.

#### 4.4.1. Kiến trúc Event Streaming (DB outbox → Kafka public)

Event này là signal nghiệp vụ (không phải biến động tồn kho) phát ra khi admin xác nhận đã xuất hàng từ kho. Cơ chế phát đi tương tự `INVENTORY_STOCK_CHANGED`: **PostgreSQL Outbox Pattern + Debezium CDC**.

| Bước | Thành phần | Hành động |
|:---:|:---|:---|
| 1 | Admin/WMS gọi `POST /v1/inventory/ship-to-customer` với `{order_id, warehouse_id, shipped_at, courier_note?}` | API Layer validate JWT + warehouse + order tồn tại |
| 2 | Cùng PostgreSQL transaction | INSERT vào `inventory_outbox` với `event_type = INVENTORY_SHIP_TO_CUSTOMER`, `partition_key = order_id`, payload chứa `order_id, warehouse_id, shipped_at, courier_note` |
| 3 | PostgreSQL | COMMIT transaction |
| 4 | Debezium CDC Connector | Đọc WAL qua Logical Replication Slot, extract `partition_key = order_id` làm Kafka message key (không bóc JSON) |
| 5 | Debezium CDC | Produce lên Kafka topic `t.inventory.events` với `key = order_id`, độ trễ < 5ms |
| 6 | Order Service (downstream) | Consume `INVENTORY_SHIP_TO_CUSTOMER`, lookup `inventory_reservations` theo `(order_id, warehouse_id)` (lọc cả warehouse_id để tránh DELIVERED nhầm trong multi-warehouse order) để lấy `order_item_ids` đang ở `status = 'DEDUCTED'`, update `order_items.status PROCESSING → DELIVERED` idempotent theo `event_id` |

**Lưu ý vận hành:**

- Khác với các event stock, `INVENTORY_SHIP_TO_CUSTOMER` **không đi kèm** `INVENTORY_STOCK_CHANGED` (vì không có biến động tồn kho — stock đã DEDUCTED lúc payment success).
- Không có `INVENTORY_LEDGER` entry cho event này (chi tiết tại [Workflow §7](inventory_workflow.md#luồng-7-ship-to-customer-xuất-hàng-cho-khách)).
- Order Service consumer phải idempotent theo `event_id` để chặn replay khi Kafka redeliver.
- `affected_items` trong response API là số `order_item_ids` thuộc `(order_id, warehouse_id)` đang ở trạng thái `DEDUCTED` trên `inventory_reservations`, query tại API Layer trước khi emit outbox. Filter `warehouse_id` là bắt buộc cho multi-warehouse order.

---

## 5. Events Tiêu Thụ (Consumed Events)

### 5.1. Internal command events (trong cùng topic)

Inventory Ledger Worker consume từ `t.inventory.events` (cùng topic với public events) và chỉ xử lý `INVENTORY_STOCK_MUTATION`:

* Ledger Worker ignore `INVENTORY_STOCK_CHANGED` và `INVENTORY_RETURN_RECEIVED_ALL` (đây là event phát ra bởi chính nó hoặc từ DB outbox).
* Filter theo `event_type` trước khi xử lý để tránh vòng lặp vô hạn.

---