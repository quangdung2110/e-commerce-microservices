# Inventory Service - Database Schema Design

Tài liệu này mô tả thiết kế PostgreSQL và các ràng buộc dữ liệu lõi cho Inventory Service.

---

## 1. PostgreSQL Schema (Dữ liệu lõi)

### 1.1. `warehouses`

Lưu trữ thông tin kho hàng do Admin quản lý.

| Column | Type | Constraints | Mô tả |
|:---|:---|:---|:---|
| `id` | BIGSERIAL | PRIMARY KEY | Định danh kho |
| `name` | VARCHAR(255) | NOT NULL | Tên kho |
| `address` | VARCHAR(512) | NULL | Địa chỉ đầy đủ |
| `latitude` | DECIMAL(10, 7) | NULL | Vĩ độ |
| `longitude` | DECIMAL(10, 7) | NULL | Kinh độ |
| `city_code` | VARCHAR(20) | NULL | Mã tỉnh/thành |
| `district_code` | VARCHAR(20) | NULL | Mã quận/huyện |
| `ward_code` | VARCHAR(20) | NULL | Mã phường/xã |
| `is_active` | BOOLEAN | DEFAULT true | Kho có đang hoạt động |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT NOW() | Thời điểm tạo |
| `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT NOW() | Thời điểm cập nhật cuối |

---

### 1.2. `physical_inventory`

Nguồn commit nóng theo kho. Mỗi bản ghi đại diện cho tồn kho vật lý của một SKU tại một kho cụ thể. Cũng là nguồn aggregate duy nhất.

| Column | Type | Constraints | Mô tả |
|:---|:---|:---|:---|
| `id` | BIGSERIAL | PRIMARY KEY | Định danh bản ghi |
| `sku_id` | VARCHAR(64) | NOT NULL | Định danh SKU |
| `product_id` | VARCHAR(64) | NOT NULL | Định danh Product (denormalized từ restock request và Order activity call) |
| `warehouse_id` | BIGINT | NOT NULL, FK(`warehouses.id`) | Kho chứa |
| `actual_quantity` | INT | NOT NULL, CHECK (`actual_quantity >= 0`) | Tồn kho thực tế |
| `reserved_quantity` | INT | NOT NULL, DEFAULT 0, CHECK (`reserved_quantity >= 0`) | Số lượng đã giữ chỗ |
| `available_quantity` | INT | GENERATED ALWAYS AS (`actual_quantity - reserved_quantity`) STORED | Tồn kho khả dụng |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Thời điểm cập nhật cuối |

**Constraints:**

| Type | Expression | Mô tả |
|:---|:---|:---|
| UNIQUE | `(sku_id, warehouse_id)` | Mỗi SKU tại mỗi kho có tối đa 1 bản ghi |
| CHECK | `actual_quantity >= 0` | Tồn kho thực tế không âm |
| CHECK | `reserved_quantity >= 0` | Số lượng giữ chỗ không âm |
| CHECK | `actual_quantity >= reserved_quantity` | Tồn kho thực tế luôn >= đã giữ chỗ |

**Aggregate đọc:**

Khi cần tổng hợp per-SKU (cho routing candidate, cho event payload), dùng query:

```sql
SELECT warehouse_id, available_quantity
FROM physical_inventory
WHERE sku_id = ANY(:sku_ids) AND available_quantity > 0;
```

Với index `UNIQUE (sku_id, warehouse_id)` đã có, query này chạy < 5ms cho vài chục SKU. Tổng khả dụng per-SKU = `SUM(available_quantity) GROUP BY sku_id` tại application layer.

---

### 1.3. `inventory_reservations`

Theo dõi reservation theo item + warehouse. Ghi nhận trạng thái giữ chỗ của từng item trong đơn hàng. Cũng lưu trạng thái refund expectation và refund received để gate signal `INVENTORY_RETURN_RECEIVED_ALL`.

| Column | Type | Constraints | Mô tả |
|:---|:---|:---|:---|
| `id` | BIGSERIAL | PRIMARY KEY | Định danh reservation |
| `order_id` | BIGINT | NOT NULL, INDEX | Đơn hàng |
| `order_item_id` | BIGINT | NOT NULL | Item trong đơn hàng |
| `sku_id` | VARCHAR(64) | NOT NULL | SKU được giữ chỗ |
| `product_id` | VARCHAR(64) | NOT NULL | Product tương ứng (denormalized) |
| `warehouse_id` | BIGINT | NOT NULL | Kho giữ chỗ |
| `quantity` | INT | NOT NULL | Số lượng giữ chỗ |
| `route_plan_id` | UUID | NOT NULL | Route plan đã sử dụng |
| `status` | VARCHAR(50) | NOT NULL | `RESERVED` / `DEDUCTED` / `CANCELLED` |
| `idempotency_key` | VARCHAR(255) | NOT NULL, UNIQUE | Khoá chống trùng lặp |
| `expires_at` | TIMESTAMPTZ | NOT NULL | Hết hạn giữ chỗ |
| `refund_expected` | BOOLEAN | NOT NULL DEFAULT false | Item có nằm trong refund scope không |
| `refund_request_id` | VARCHAR(64) | NULL | Mã refund request (FK logic từ Order) |
| `refund_returned_at` | TIMESTAMPTZ | NULL | Thời điểm nhận hàng hoàn về kho |
| `created_at` | TIMESTAMPTZ | NOT NULL | Thời điểm tạo |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Thời điểm cập nhật cuối |

> **Đơn giản hóa:** Đã bỏ column `route_version` (chỉ có 1 algorithm Smart Routing, không cần version determinism).

**Indexes / Constraints:**

| Type | Expression | Mô tả |
|:---|:---|:---|
| INDEX | `(order_id)` | Tra cứu theo đơn hàng |
| INDEX | `(order_item_id)` | Tra cứu theo item |
| INDEX | `(status, expires_at)` | Compensate reservation quá hạn |
| INDEX | `(order_id, refund_request_id) WHERE refund_expected = true` | Query check refund completion |
| UNIQUE | `(idempotency_key)` | Chống duplicate reservation |
| UNIQUE | `(order_id, sku_id, warehouse_id) WHERE status = 'RESERVED'` | Ngăn 2 reservation cùng SKU-kho-đơn ở trạng thái RESERVED |

**Quy ước refund:**

- `refund_expected = true` được set khi Order gọi API `POST /v1/inventory/returns/refund-expectations` (xem [Endpoints §3](inventory_endpoints.md)).
- `refund_returned_at` được set khi admin restock với `reference_type='ORDER_RETURNED_RESTOCK'` cập nhật row tương ứng.
- Signal `INVENTORY_RETURN_RECEIVED_ALL` được emit khi tất cả reservation cùng `(order_id, refund_request_id)` có `refund_expected = true` đều có `refund_returned_at IS NOT NULL`.

---

### 1.4. `inventory_ledger`

Append-only audit trail ghi nhận mọi biến động tồn kho.

| Column | Type | Constraints | Mô tả |
|:---|:---|:---|:---|
| `id` | BIGSERIAL | PRIMARY KEY | Định danh ledger entry |
| `event_id` | UUID | NOT NULL, UNIQUE | ID event tạo ra ledger entry |
| `sku_id` | VARCHAR(64) | NOT NULL | SKU bị tác động |
| `product_id` | VARCHAR(64) | NOT NULL | Product tương ứng |
| `warehouse_id` | BIGINT | NOT NULL | Kho nơi xảy ra biến động |
| `reference_type` | VARCHAR(50) | NOT NULL | Loại biến động (xem bảng bên dưới) |
| `quantity_change` | INT | NOT NULL | Thay đổi số lượng (dương = tăng, âm = giảm) |
| `balance_after` | INT | NOT NULL | Số dư tồn kho tại kho sau thay đổi |
| `correlation_id` | VARCHAR(128) | NOT NULL | ID để trace nguyên nhân (Temporal workflow, request, ...) |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT NOW() | Thời điểm ghi |

**Giá trị hợp lệ của `reference_type`:**

| Value | Mô tả |
|:---|:---|
| `ORDER_RESERVED` | Giữ chỗ cho đơn hàng |
| `ORDER_DEDUCTED` | Xác nhận bán — trừ tồn kho |
| `ORDER_CANCELLED` | Đơn hàng bị hủy trước thanh toán (do Temporal compensation) |
| `RESERVATION_EXPIRED` | Reservation timeout do Order Temporal (cùng nghĩa nghiệp vụ với `ORDER_CANCELLED` nhưng phân biệt cho audit trail) |
| `ADMIN_RESTOCK` | Admin nhập hàng vào kho |
| `ORDER_RETURNED_RESTOCK` | Hàng hoàn về kho |
| `ADMIN_ADJUSTMENT` | Admin điều chỉnh tồn kho thủ công |

> `RESERVATION_EXPIRED` được giữ làm `reference_type` riêng (không gộp vào `ORDER_CANCELLED`) để audit trail phân biệt được "đơn bị buyer cancel chủ động" vs "đơn timeout do payment quá hạn". Tuy nhiên cả hai đều map sang `inventory_reservations.status = CANCELLED`.

**Indexes:**

| Type | Expression | Mô tả |
|:---|:---|:---|
| UNIQUE | `(event_id)` | Mỗi event chỉ tạo 1 ledger entry, đồng thời là idempotency key |
| INDEX | `(sku_id, created_at)` | Tra cứu lịch sử SKU theo thời gian |
| INDEX | `(warehouse_id, created_at)` | Tra cứu lịch sử kho theo thời gian |

---

### 1.5. `inventory_outbox`

Transactional Outbox pattern dùng chung với Debezium CDC Connector để phát event `INVENTORY_STOCK_CHANGED` và `INVENTORY_RETURN_RECEIVED_ALL`.

| Column | Type | Constraints | Mô tả |
|:---|:---|:---|:---|
| `event_id` | UUID | PRIMARY KEY | Định danh duy nhất sự kiện |
| `event_type` | VARCHAR(100) | NOT NULL | Loại sự kiện (VD: `INVENTORY_STOCK_CHANGED`) |
| `schema_version` | INT | DEFAULT 1 | Quản lý version schema top-level |
| `aggregate_type` | VARCHAR(50) | NOT NULL | Loại aggregate (VD: `Inventory`) |
| `aggregate_id` | VARCHAR(255) | NOT NULL | ID đối tượng tác động. VD: `sku_id` hoặc `order_id` |
| `partition_key` | VARCHAR(255) | NOT NULL | Topic Kafka partition key. Không được để CDC tự suy từ JSON |
| `payload` | JSONB | NOT NULL | Nội dung Event gốc |
| `created_at` | TIMESTAMPTZ | NOT NULL | Thời điểm tạo event |

---

## 2. Redis Caching Schema

Chi tiết key, script và cross-slot constraints nằm ở [inventory_redis_design.md](inventory_redis_design.md).

| Pattern | Mục đích |
|:---|:---|
| `inventory:physic:{sku:wh}:stock` | Hash tồn kho vật lý theo kho (commit source) |
| `inventory:idemp:{op}:{sku:wh}:order:{order_id}` | Idempotency key cho từng op (reserve/release/deduct) |
| `inventory:stream:{sku:wh}:events` | Redis Stream outbox cho event atomicity |
| `inventory:suspended:{sku:wh}` | Hash cờ SKU bị suspend do invariant drift |

---


## 3. Retention và Vận hành

| Bảng / Job | Retention | Chi tiết |
|:---|:---|:---|
| `inventory_outbox` | Time-Window Cleanup CronJob | Background xóa rác an toàn theo Timestamp cộng Watermark offset của Debezium |
| Redis key `inventory:suspended:*` | 24 giờ | Auto-cleanup nếu không có human intervention |
| `inventory_ledger` | Không xoá (audit trail) | Append-only vĩnh viễn |
| `inventory_reservations` | 90 ngày sau status = `CANCELLED`/`DEDUCTED` | Background job archive sang cold storage |

Metrics tối thiểu và health check nằm ở [inventory_observability_slo.md](inventory_observability_slo.md).
