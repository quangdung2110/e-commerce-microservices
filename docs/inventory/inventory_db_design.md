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
| `province_code` | VARCHAR(20) | NULL | Mã tỉnh/thành, thống nhất với địa chỉ giao hàng |
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
| `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT NOW() | Thời điểm cập nhật cuối |

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
| `expires_at` | TIMESTAMPTZ | NOT NULL | Hết hạn giữ chỗ |
| `refund_expected` | BOOLEAN | NOT NULL DEFAULT false | Item có nằm trong refund scope không |
| `refund_request_id` | VARCHAR(64) | NULL | Mã refund request (FK logic từ Order) |
| `refund_returned_at` | TIMESTAMPTZ | NULL | Thời điểm nhận hàng hoàn về kho |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT NOW() | Thời điểm tạo |
| `updated_at` | TIMESTAMPTZ | NOT NULL DEFAULT NOW() | Thời điểm cập nhật cuối |


**Indexes / Constraints:**

| Type | Expression | Mô tả |
|:---|:---|:---|
| INDEX | `(order_id)` | Tra cứu theo đơn hàng |
| INDEX | `(order_item_id)` | Tra cứu theo item |
| INDEX | `(status, expires_at)` | Compensate reservation quá hạn |
| INDEX | `(order_id, refund_request_id) WHERE refund_expected = true` | Query check refund completion |
| UNIQUE | `(order_id, sku_id, warehouse_id) WHERE status = 'RESERVED'` | Ngăn 2 reservation cùng SKU-kho-đơn ở trạng thái RESERVED |

> **Ghi chú idempotency:** Idempotency ở hot path được đảm bảo bởi Redis SETNX trong Lua (TTL 30 ngày, xem [Redis §2.2](inventory_redis_design.md)). Idempotency ở cold path được đảm bảo bởi partial UNIQUE `(order_id, sku_id, warehouse_id) WHERE status = 'RESERVED'` ở trên — reservation là 1 row duy nhất trong cả lifecycle `(RESERVED → DEDUCTED|CANCELLED)` cho mỗi `(order_id, sku_id, warehouse_id)`. Ledger cold path dùng `inventory_ledger.event_id` UNIQUE.

**Quy ước refund:**

- `refund_expected = true` được set khi Order gọi API `POST /v1/inventory/returns/refund-expectations` (xem [Endpoints §3](inventory_endpoints.md)).
- `refund_returned_at` được set khi admin restock với `reference_type='ORDER_RETURNED_RESTOCK'` cập nhật row tương ứng.
- Signal `INVENTORY_RETURN_RECEIVED_ALL` được emit khi tất cả reservation cùng `(order_id, refund_request_id)` có `refund_expected = true` đều có `refund_returned_at IS NOT NULL`.

**Cơ chế `expires_at` và reservation timeout:**

- `expires_at` được set bởi `ReserveInventoryActivity` ngay khi `inventory_reservations` row được insert. Giá trị mặc định = `created_at + 30 phút` (config; chỉnh trong Order Temporal Saga). Mục đích: giới hạn thời gian giữ chỗ — nếu buyer không thanh toán trong 30 phút, reservation sẽ hết hạn và inventory sẽ được release lại.
- **Không có worker scan expiry trong Inventory Service** (theo Phase 14 đơn giản hoá). Thay vào đó, Order Temporal Saga sử dụng Temporal workflow timer:
  - Khi Reserve thành công, Temporal workflow của Order tạo 1 timer 30 phút.
  - Khi timer fire mà workflow vẫn đang ở state pre-payment (chưa nhận payment success callback), Temporal sẽ tự gọi `RollbackInventoryActivity` với `change_type='RESERVATION_EXPIRED'`.
  - Inventory không cần tự quét DB để tìm reservation hết hạn — việc này do Temporal đảm nhận.
- Index `(status, expires_at)` vẫn giữ để phục vụ debug/reporting query: `SELECT * FROM inventory_reservations WHERE status = 'RESERVED' AND expires_at < NOW()` (chỉ để dashboard theo dõi, không trigger cancel tự động).
- Sau khi Rollback, `inventory_reservations.status` chuyển sang `CANCELLED` với `reference_type='RESERVATION_EXPIRED'` (xem [DB §1.4](inventory_db_design.md#14-inventory_ledger)).

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
| `quantity_change` | INT | NOT NULL | **Thay đổi `actual_quantity`** (dương = tăng tồn kho vật lý, âm = giảm tồn kho vật lý) |
| `balance_after` | INT | NOT NULL | **`actual_quantity` tại `(sku_id, warehouse_id)` sau biến động** (KHÔNG phải `available_quantity`) |
| `correlation_id` | VARCHAR(128) | NOT NULL | ID để trace nguyên nhân. **Nguồn:** (1) Temporal Activity: lấy từ Temporal Workflow ID của Order Saga; (2) `/v1/inventory/restock` Admin API: lấy từ HTTP header `X-Correlation-Id` (nếu có) hoặc auto-generate UUID v4. (3) `/v1/inventory/ship-to-customer`: tương tự (2). Đảm bảo mọi ledger/outbox row đều có `correlation_id` để trace end-to-end. |
| `created_at` | TIMESTAMPTZ | NOT NULL DEFAULT NOW() | Thời điểm ghi |

**Giá trị hợp lệ của `reference_type`:**

| Value | `quantity_change` | `balance_after` thay đổi thế nào | Mô tả |
|:---|:---:|:---|:---|
| `ORDER_RESERVED` | `0` | unchanged | Giữ chỗ cho đơn hàng — `actual_quantity` không đổi, chỉ `reserved_quantity` tăng |
| `ORDER_DEDUCTED` | `-quantity` (âm) | giảm | Xác nhận bán — trừ `actual_quantity` |
| `ORDER_CANCELLED` | `0` | unchanged | Đơn bị buyer cancel — `actual_quantity` không đổi, chỉ `reserved_quantity` giảm |
| `RESERVATION_EXPIRED` | `0` | unchanged | Reservation timeout — tương tự `ORDER_CANCELLED` về ledger impact |
| `ADMIN_RESTOCK` | `+quantity` (dương) | tăng | Admin nhập hàng vào kho |
| `ORDER_RETURNED_RESTOCK` | `+quantity` (dương) | tăng | Hàng hoàn về kho |

> **`quantity_change` chỉ track `actual_quantity`**, KHÔNG track `reserved_quantity`. Lý do: ledger là append-only audit trail cho biến động tồn kho vật lý (physical stock). Reserve/Cancel chỉ là "bookkeeping" trong `reserved_quantity` không phải biến động vật lý. Nếu cần trace `reserved_quantity` history, query `inventory_reservations` (đã có `created_at` + `updated_at`).

> `RESERVATION_EXPIRED` được giữ làm `reference_type` riêng (không gộp vào `ORDER_CANCELLED`) để audit trail phân biệt được "đơn bị buyer cancel chủ động" vs "đơn timeout do payment quá hạn". Tuy nhiên cả hai đều map sang `inventory_reservations.status = CANCELLED`.

**Indexes:**

| Type | Expression | Mô tả |
|:---|:---|:---|
| UNIQUE | `(event_id)` | Mỗi event chỉ tạo 1 ledger entry, đồng thời là idempotency key |
| INDEX | `(sku_id, created_at)` | Tra cứu lịch sử SKU theo thời gian |
| INDEX | `(warehouse_id, created_at)` | Tra cứu lịch sử kho theo thời gian |

---

### 1.5. `inventory_outbox`

Transactional Outbox pattern dùng chung với Debezium CDC Connector để phát event `INVENTORY_STOCK_CHANGED`, `INVENTORY_RETURN_RECEIVED_ALL` và `INVENTORY_SHIP_TO_CUSTOMER`.

| Column | Type | Constraints | Mô tả |
|:---|:---|:---|:---|
| `event_id` | UUID | PRIMARY KEY | Định danh duy nhất sự kiện |
| `event_type` | VARCHAR(100) | NOT NULL | Loại sự kiện (VD: `INVENTORY_STOCK_CHANGED`) |
| `schema_version` | INT | DEFAULT 1 | Quản lý version schema top-level |
| `aggregate_type` | VARCHAR(50) | NOT NULL | Loại aggregate (VD: `Inventory`) |
| `aggregate_id` | VARCHAR(255) | NOT NULL | ID đối tượng tác động. VD: `sku_id` hoặc `order_id` |
| `partition_key` | VARCHAR(255) | NOT NULL | Topic Kafka partition key. Không được để CDC tự suy từ JSON |
| `refund_request_id` | VARCHAR(64) | NULL | Chỉ dùng cho `event_type = 'INVENTORY_RETURN_RECEIVED_ALL'`. Lưu `refund_request_id` ở dạng column riêng (không trích từ JSONB) để index unique hoạt động hiệu quả. |
| `payload` | JSONB | NOT NULL | Nội dung Event gốc |
| `created_at` | TIMESTAMPTZ | NOT NULL | Thời điểm tạo event |

**Indexes / Constraints:**

| Type | Expression | Mô tả |
|:---|:---|:---|
| UNIQUE | `(event_id)` (PK) | Mỗi event chỉ tạo 1 outbox row |
| UNIQUE (partial) | `(aggregate_id, refund_request_id) WHERE event_type = 'INVENTORY_RETURN_RECEIVED_ALL'` | Đảm bảo `INVENTORY_RETURN_RECEIVED_ALL` chỉ emit đúng 1 lần cho mỗi `(order_id, refund_request_id)` — chống duplicate emit khi 2 transaction đồng thời cùng thấy `pending = 0` ở refund completion check (xem [Workflow §6](inventory_workflow.md#luồng-6-return-restock-nhận-hàng-hoàn-về-kho)). |
| INDEX | `(created_at)` | Hỗ trợ Outbox Cleanup Job xóa row theo watermark. |

---

## 2. Redis Caching Schema

Chi tiết key, script và cross-slot constraints nằm ở [inventory_redis_design.md](inventory_redis_design.md).

| Pattern | Mục đích |
|:---|:---|
| `inventory:physic:{sku_id:warehouse_id}:stock` | Hash tồn kho vật lý theo kho (commit source) |
| `inventory:idemp:{op}:{sku_id:warehouse_id}:order:{order_id}` | Idempotency key cho từng op (reserve/release/deduct). `{op}` = `reserve` \| `release` \| `deduct`. |
| `inventory:stream:{sku_id:warehouse_id}:events` | Redis Stream outbox cho event atomicity |
| `inventory:suspended:{sku_id:warehouse_id}` | Hash cờ SKU bị suspend do invariant drift |

> **Lưu ý hash tag:** Mọi key trong bảng trên (và tất cả key được Lua script truy cập trong cùng 1 execution) phải dùng hash tag `{sku_id:warehouse_id}` thống nhất để đảm bảo cùng Redis Cluster slot — xem chi tiết tại [Redis §5](inventory_redis_design.md).

---


## 3. Retention và Vận hành

| Bảng / Job | Retention | Chi tiết |
|:---|:---|:---|
| `inventory_outbox` | Time-Window Cleanup CronJob | Background xóa rác an toàn theo Timestamp cộng Watermark offset của Debezium |
| Redis key `inventory:suspended:*` | 24 giờ | Auto-cleanup nếu không có human intervention |
| `inventory_ledger` | Không xoá (audit trail) | Append-only vĩnh viễn |
| `inventory_reservations` | 90 ngày sau status = `CANCELLED`/`DEDUCTED` | Background job archive sang cold storage |

