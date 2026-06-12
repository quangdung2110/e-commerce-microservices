# Inventory Service - Database & Cache Schema Design

Tài liệu này mô tả thiết kế PostgreSQL và các ràng buộc dữ liệu lõi cho Inventory Service sau khi chuẩn hóa contract liên service.

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

### 1.2. `sku_product_mapping`

Bảng mapping local để Inventory phát `product_id` chính xác trong `INVENTORY_QUANTITY_CHANGED` mà không phụ thuộc call runtime sang Product.

> **Ownership:** cập nhật bởi Product Mapping Sync Worker (consume `t.product.events`) và chỉ dùng để enrich event/outbox của Inventory.

| Column | Type | Constraints | Mô tả |
|:---|:---|:---|:---|
| `sku_id` | VARCHAR(64) | PRIMARY KEY | Định danh SKU |
| `product_id` | VARCHAR(64) | NOT NULL | Định danh Product |
| `source_version` | BIGINT | NOT NULL | Version nguồn (tăng dần) |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Thời điểm cập nhật cuối |

---

### 1.3. `physical_inventory`

Nguồn commit nóng theo kho. Mỗi bản ghi đại diện cho tồn kho vật lý của một SKU tại một kho cụ thể.

| Column | Type | Constraints | Mô tả |
|:---|:---|:---|:---|
| `id` | BIGSERIAL | PRIMARY KEY | Định danh bản ghi |
| `sku_id` | VARCHAR(64) | NOT NULL | Định danh SKU |
| `product_id` | VARCHAR(64) | NOT NULL | Định danh Product |
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

---

### 1.4. `logical_inventory`

Aggregate read model theo SKU. Tổng hợp toàn bộ tồn kho của một SKU qua tất cả các kho.

| Column | Type | Constraints | Mô tả |
|:---|:---|:---|:---|
| `id` | BIGSERIAL | PRIMARY KEY | Định danh bản ghi |
| `sku_id` | VARCHAR(64) | UNIQUE, NOT NULL | Định danh SKU |
| `product_id` | VARCHAR(64) | NOT NULL | Định danh Product |
| `total_quantity` | INT | NOT NULL, CHECK (`total_quantity >= 0`) | Tổng tồn kho |
| `reserved_quantity` | INT | NOT NULL, CHECK (`reserved_quantity >= 0`) | Tổng đã giữ chỗ |
| `available_quantity` | INT | GENERATED ALWAYS AS (`total_quantity - reserved_quantity`) STORED | Tổng khả dụng |
| `version` | BIGINT | NOT NULL | Optimistic lock version |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Thời điểm cập nhật cuối |

**Constraints:**

| Type | Expression | Mô tả |
|:---|:---|:---|
| UNIQUE | `sku_id` | Mỗi SKU có tối đa 1 bản ghi tổng hợp |
| CHECK | `total_quantity >= 0` | Tổng tồn kho không âm |
| CHECK | `reserved_quantity >= 0` | Tổng giữ chỗ không âm |
| CHECK | `total_quantity >= reserved_quantity` | Tổng tồn >= tổng giữ chỗ |

---

### 1.5. `inventory_reservations`

Theo dõi reservation theo item + warehouse. Ghi nhận trạng thái giữ chỗ của từng item trong đơn hàng.

| Column | Type | Constraints | Mô tả |
|:---|:---|:---|:---|
| `id` | BIGSERIAL | PRIMARY KEY | Định danh reservation |
| `order_id` | BIGINT | NOT NULL, INDEX | Đơn hàng |
| `order_item_id` | BIGINT | NULL | Item trong đơn hàng |
| `sku_id` | VARCHAR(64) | NOT NULL | SKU được giữ chỗ |
| `product_id` | VARCHAR(64) | NOT NULL | Product tương ứng |
| `warehouse_id` | BIGINT | NOT NULL | Kho giữ chỗ |
| `quantity` | INT | NOT NULL | Số lượng giữ chỗ |
| `route_plan_id` | UUID | NOT NULL | Route plan đã sử dụng |
| `route_version` | INT | NOT NULL | Version của route plan |
| `status` | VARCHAR(50) | NOT NULL | `RESERVED` / `DEDUCTED` / `CANCELLED` |
| `idempotency_key` | VARCHAR(255) | NOT NULL, UNIQUE | Khoá chống trùng lặp |
| `expires_at` | TIMESTAMPTZ | NOT NULL | Hết hạn giữ chỗ |
| `created_at` | TIMESTAMPTZ | NOT NULL | Thời điểm tạo |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Thời điểm cập nhật cuối |

**Indexes / Constraints:**

| Type | Expression | Mô tả |
|:---|:---|:---|
| INDEX | `(order_id)` | Tra cứu theo đơn hàng |
| INDEX | `(order_item_id)` | Tra cứu theo item |
| INDEX | `(status, expires_at)` | Scanner cleanup reservation quá hạn |
| UNIQUE | `(idempotency_key)` | Chống duplicate reservation |
| UNIQUE | `(order_id, sku_id, warehouse_id, status) WHERE status = 'RESERVED'` | Ngăn 2 reservation cùng SKU-kho-đơn ở trạng thái RESERVED |

---

### 1.6. `inventory_ledger`

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
| `ORDER_CANCELLED` | Đơn hàng bị hủy trước thanh toán |
| `RESERVATION_EXPIRED` | Giữ chỗ hết hạn, tự động giải phóng |
| `ADMIN_RESTOCK` | Admin nhập hàng vào kho |
| `ORDER_RETURNED_RESTOCK` | Hàng hoàn về kho |
| `ADMIN_ADJUSTMENT` | Admin điều chỉnh tồn kho thủ công |

**Indexes:**

| Type | Expression | Mô tả |
|:---|:---|:---|
| UNIQUE | `(event_id)` | Mỗi event chỉ tạo 1 ledger entry |
| INDEX | `(sku_id, created_at)` | Tra cứu lịch sử SKU theo thời gian |
| INDEX | `(warehouse_id, created_at)` | Tra cứu lịch sử kho theo thời gian |

---

### 1.7. `inventory_refund_scopes`

Bảng theo dõi scope hoàn hàng theo từng `refund_request_id`, dùng để gate signal `INVENTORY_RETURN_RECEIVED_ALL` về Order.

Đồng thời đóng vai trò idempotency cho luồng return restock: mọi event xử lý từ Kafka `SHIPMENT_RETURNED` đều được check qua bảng này (thay thế cho `inventory_event_idempotency` đã loại bỏ).

> Event `INVENTORY_RETURN_RECEIVED_ALL` chỉ được insert outbox đúng 1 lần khi scope chuyển `OPEN -> COMPLETED` (hoặc `MANUAL_OVERRIDE`).

| Column | Type | Constraints | Mô tả |
|:---|:---|:---|:---|
| `id` | BIGSERIAL | PRIMARY KEY | Định danh refund scope |
| `order_id` | BIGINT | NOT NULL | Đơn hàng cần hoàn |
| `refund_request_id` | VARCHAR(64) | NOT NULL | ID yêu cầu hoàn (từ Order) |
| `required_order_item_ids` | JSONB | NOT NULL, CHECK (jsonb_typeof = 'array') | Danh sách item cần hoàn đầy đủ |
| `received_order_item_ids` | JSONB | NOT NULL DEFAULT '[]'::jsonb, CHECK (jsonb_typeof = 'array') | Danh sách item đã nhận về kho |
| `status` | VARCHAR(32) | NOT NULL | `OPEN` / `COMPLETED` / `MANUAL_OVERRIDE` |
| `created_at` | TIMESTAMPTZ | NOT NULL | Thời điểm tạo scope |
| `updated_at` | TIMESTAMPTZ | NOT NULL | Thời điểm cập nhật cuối |
| `closed_at` | TIMESTAMPTZ | NULL | Thời điểm đóng scope (completed/manual_override) |
| `idempotency_key` | VARCHAR(255) | NULL, UNIQUE | Khoá idempotency cho SHIPMENT_RETURNED (shipment_id) |
| `payload_hash` | VARCHAR(128) | NULL | Checksum payload tránh replay lệch logic |

**Indexes / Constraints:**

| Type | Expression | Mô tả |
|:---|:---|:---|
| UNIQUE | `(order_id, refund_request_id)` | Mỗi order + refund request có tối đa 1 scope |
| INDEX | `(status, updated_at)` | Quét scope cần xử lý |
| UNIQUE | `(idempotency_key)` | Chống duplicate xử lý SHIPMENT_RETURNED |
| CHECK | `jsonb_typeof(required_order_item_ids) = 'array'` | Đảm bảo là mảng |
| CHECK | `jsonb_typeof(received_order_item_ids) = 'array'` | Đảm bảo là mảng |

---

### 1.8. `inventory_outbox`

Transactional Outbox pattern dùng chung với Debezium CDC Connector để phát event `INVENTORY_QUANTITY_CHANGED` và `INVENTORY_RETURN_RECEIVED_ALL`. Tuyệt đối không dùng Worker Polling.

| Column | Type | Constraints | Mô tả |
|:---|:---|:---|:---|
| `event_id` | UUID | PRIMARY KEY | Định danh duy nhất sự kiện |
| `schema_version` | INT | DEFAULT 1 | Quản lý version schema top-level |
| `aggregate_type` | VARCHAR(50) | NOT NULL | Loại aggregate (VD: `Inventory`) |
| `aggregate_id` | VARCHAR(255) | NOT NULL | ID đối tượng tác động. VD: `sku_id` hoặc `order_id` |
| `event_type` | VARCHAR(100) | NOT NULL | Loại sự kiện (VD: `INVENTORY_QUANTITY_CHANGED`) |
| `partition_key` | VARCHAR(255) | NOT NULL | Topic Kafka partition key. Không được để CDC tự suy từ JSON |
| `payload` | JSONB | NOT NULL | Nội dung Event gốc |
| `created_at` | TIMESTAMPTZ | NOT NULL | Thời điểm tạo event |

> [!IMPORTANT]
> **Append-Only cho Debezium CDC:** Bảng outbox đã loại bỏ hoàn toàn các cột phục vụ polling (`status`, `retry_count`, `next_retry_at`, v.v...). Tiến trình Retry, Dead Letter Queue được uỷ quyền và điều phối toàn bộ tại Kafka Connect runtime. Triệt để loại bỏ Dual-Write Problem mà không tốn CPU của DB.

---

## 2. Redis Caching Schema

Chi tiết key, script và cross-slot constraints nằm ở [inventory_redis_design.md](inventory_redis_design.md).

---

## 3. Kiến trúc Event Streaming

### 3.1. Luồng `INVENTORY_RESERVED` / `INVENTORY_DEDUCTED` / `INVENTORY_RELEASED`

| Bước | Thành phần | Hành động |
|:---|:---|:---|
| 1 | Lua script | Mutate Redis stock hash |
| 2 | Lua script | `XADD` event vào Redis Stream Outbox cùng slot |
| 3 | Redis Stream Relay Worker | Forward event sang Kafka `t.inventory.events` |
| 4 | Kafka Ledger Worker | Ghi `inventory_ledger` + update `inventory_reservations` |

### 3.2. Luồng `INVENTORY_QUANTITY_CHANGED`

| Bước | Thành phần | Hành động |
|:---|:---|:---|
| 1 | Ledger Worker | Update DB `inventory_ledger` và `logical_inventory` trong PostgreSQL transaction |
| 2 | Ledger Worker | Cùng transaction insert vào bảng append-only `inventory_outbox` |
| 3 | Debezium CDC Connector | Đọc WAL và sinh Kafka Message không độ trễ, Offload hoàn toàn sức tải của PostgreSQL |

---

## 4. Batching Ledger & Poison Handling (Redis-based)

### 4.1. Ledger Batching

| Bước | Hành động |
|:---|:---|
| 1 | Worker `poll(100ms)` |
| 2 | Group by `sku_id` |
| 3 | Kiểm tra Redis key `inventory:suspended:{sku_id:warehouse_id}` — nếu tồn tại, park event vào Redis Stream `inventory:failed:{sku_id:warehouse_id}:events` |
| 4 | Fetch mốc balance để tính tuyến tính trong RAM |
| 5 | Bulk insert ledger + update reservation status |

### 4.2. Poison Pill Strategy (Redis-based)

| Bước | Hành động |
|:---|:---|
| 1 | Batch fail -> chia nhỏ bằng binary split để xác định event lỗi |
| 2 | Event lỗi được `XADD` vào Redis Stream `inventory:failed:{sku_id:warehouse_id}:events` |
| 3 | Đặt Redis key `inventory:suspended:{sku_id:warehouse_id}` với TTL 24h |
| 4 | Event đi sau cùng SKU trong batch cũng park vào Redis Stream ở bước 2 |
| 5 | Recovery Worker replay khi key suspended hết hạn hoặc được unlock thủ công |

---

## 5. Retention và Vận hành

| Bảng / Job | Retention | Chi tiết |
|:---|:---|:---|
| `inventory_outbox` | Time-Window Cleanup CronJob | Background xóa rác an toàn theo Timestamp cộng Watermark offset của Debezium |
| Redis Stream `inventory:failed:*` | 7 ngày (TTL trên key) | Auto-cleanup qua TTL |
| Redis key `inventory:suspended:*` | 24 giờ | Auto-unlock nếu không có human intervention |

Chỉ số vận hành và alert nằm ở [inventory_observability_slo.md](inventory_observability_slo.md).
