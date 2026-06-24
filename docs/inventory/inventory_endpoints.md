# Inventory Service - API Endpoints

**Liên kết nhanh (Related Documents):**
* [Database Schema](inventory_db_design.md)
* [Redis Caching & Lua Scripts](inventory_redis_design.md)
* [Events Structure & Kafka Topics](inventory_events.md)
* [Internal Data Flow](inventory_workflow.md)
* [Background Workers & Jobs](inventory_workers.md)
* [Warehouse Selection Engine](smart_routing_engine.md)

---

Tài liệu này mô tả chi tiết toàn bộ HTTP API endpoints của Inventory Service: method, endpoint path, authentication, request/response body, và mô tả luồng hoạt động nội bộ.

Logic mới: chỉ chọn kho có stock đủ. Thay thế `returns/refund-scopes` bằng `returns/refund-expectations`. Thêm `admin/unlock-sku` cho manual recovery. Thêm endpoint mới `ship-to-customer` để admin trigger Order Item FSM PROCESSING → DELIVERED. Mở rộng `restock` để hỗ trợ luồng return restock (`reference_type='ORDER_RETURNED_RESTOCK'`) thay thế cho `SHIPMENT_RETURNED` consumer cũ.

---

## Tổng quan

| Method | Endpoint | Auth | Mô tả |
|:---|:---|:---|:---|
| `POST` | `/v1/inventory/routing-options` | JWT (User) | Tạo route plan fulfillment cho checkout preview |
| `POST` | `/v1/inventory/restock` | JWT (Admin) | Admin nhập hàng vào kho. Hỗ trợ 2 mode: `ADMIN_RESTOCK` (mặc định) và `ORDER_RETURNED_RESTOCK` (buyer return). |
| `POST` | `/v1/inventory/ship-to-customer` | JWT (Admin) | Admin xác nhận kho đã xuất hàng cho khách (trigger Order Item FSM PROCESSING → DELIVERED) |
| `POST` | `/v1/inventory/returns/refund-expectations` | Internal (Service-to-Service) | Order đăng ký tập item cần hoàn |
| `POST` | `/v1/inventory/admin/unlock-sku` | JWT (Admin) | Manual unlock SKU bị suspend (recovery) |

> **Note:** Reserve, Deduct, Release là Temporal Activities, KHÔNG phải HTTP endpoints. Chúng được Order Temporal Saga gọi trực tiếp qua Temporal SDK.

---

## 1. `POST /v1/inventory/routing-options`

Tạo route plan fulfillment đầy đủ cho một giỏ hàng.

### Auth

| Loại | Chi tiết |
|:---|:---|
| **Required** | JWT Bearer Token |
| **Scope** | `inventory:read`, `inventory:routing` |
| **Ghi chú** | Customer location được trích từ JWT claims (nếu có) **và** luôn yêu cầu trong request body để tăng độ chính xác cho warehouse selection. Body schema đánh dấu `customer_location` là **required** — request không có body sẽ bị reject 400 `INVALID_REQUEST`. |

### Request

```json
{
  "items": [
    { "sku_id": "sku-001", "quantity": 2 },
    { "sku_id": "sku-002", "quantity": 1 }
  ],
  "customer_location": {
    "city_code": "HCM",
    "district_code": "Q1"
  }
}
```

| Field | Type | Required | Mô tả |
|:---|:---|:---:|:---|
| `items` | Array[Object] | ✅ | Danh sách SKU cần routing |
| `items[].sku_id` | String(64) | ✅ | SKU identifier |
| `items[].quantity` | Integer | ✅ | Số lượng cần đặt |
| `customer_location` | Object | ✅ | Vị trí giao hàng |
| `customer_location.city_code` | String(20) | ✅ | Mã tỉnh/thành phố (vd: `HCM`, `HN`). Tên field là `city_code` vì hệ thống thống nhất dùng tên `city_code` cho cả 2 phía (`warehouses.city_code` và `customer_location.city_code`) — về bản chất đây là mã **cấp tỉnh/thành phố trực thuộc trung ương** (tương đương khái niệm `province_code` trong các hệ thống khác). Dùng để so sánh `warehouses.city_code` cho rule same-province ở [Warehouse Selection §4.3](smart_routing_engine.md#43-warehouse-selection). |
| `customer_location.district_code` | String(20) | ✅ | Mã quận/huyện. Phase 14 chưa dùng trong algorithm (giữ required để tương thích ngược với frontend đã gửi; bỏ qua khi routing). Có thể relaxed thành optional trong tương lai khi algorithm dùng district để refine. |

### Response

**200 OK**

```json
{
  "route_plan_id": "7b7dddad-6f6c-4f63-a728-df0fe96b9f31",
  "expires_at": "2026-04-01T08:20:00Z",
  "items": [
    {
      "sku_id": "sku-001",
      "product_id": "prod-uuid-001",
      "quantity": 2,
      "warehouse_id": 5
    },
    {
      "sku_id": "sku-002",
      "product_id": "prod-uuid-002",
      "quantity": 1,
      "warehouse_id": 5
    }
  ],
  "warehouse_assignments": [
    {
      "warehouse_id": 5,
      "available_quantity": 50
    }
  ]
}
```

| Field | Type | Mô tả |
|---|:---|:---|
| `route_plan_id` | UUID | ID duy nhất của route plan, dùng để submit checkout |
| `expires_at` | ISO 8601 | Hết hiệu lực. Sau thời điểm này, checkout submit sẽ bị reject |
| `items[].sku_id` | String | SKU đã được assign |
| `items[].product_id` | String | Product denormalized (lấy từ `physical_inventory.product_id`, dùng cho `ReserveInventoryActivity` không cần lookup thêm) |
| `items[].quantity` | Integer | Số lượng từ request |
| `items[].warehouse_id` | Integer | Kho fulfill item này |
| `warehouse_assignments[]` | Array[Object] | Thông tin kho đã chọn để fulfill |
| `warehouse_assignments[].warehouse_id` | Integer | Kho tương ứng |
| `warehouse_assignments[].available_quantity` | Integer | Tồn kho khả dụng |

**4xx Error Responses**

| Status | Code | Mô tả |
|:---:|:---|:---|
| `400` | `INVALID_REQUEST` | Missing required fields hoặc validation lỗi |
| `404` | `SKU_NOT_FOUND` | Một hoặc nhiều SKU không tồn tại trong inventory |
| `409` | `INSUFFICIENT_STOCK` | Không đủ tồn kho khả dụng cho routing |

### Chi tiết hoạt động nội bộ

```
1. [API Layer] Nhận request, validate JWT + scope
2. [Candidate Generation]
   - SELECT sku_id, product_id, warehouse_id, available_quantity
     FROM physical_inventory
     WHERE sku_id = ANY(:sku_ids) AND available_quantity > 0
   - Build feasible warehouse combinations đủ stock cho mọi items
3. [Warehouse Selection (Phase 14)]
   - Pick warehouse có physical_inventory.available_quantity >= requested quantity
   - Nếu nhiều kho qualify → ưu tiên kho same-province với customer_location
4. [Cache]
   SET route_plan:{route_plan_id} = JSON, TTL = expires_at - now
5. [Response] Trả route plan với concrete warehouse_id cho mỗi item + product_id denormalized
```

### Plan Expiry

- Route plan có TTL 20 phút (mặc định).
- Khi Checkout submit với `route_plan_id`:
  - Nếu plan còn trong cache (TTL chưa hết): proceed.
  - Nếu plan đã expire: reject `CONTRACT_INVALID_ROUTE_PLAN`, Checkout phải gọi lại `/routing-options`.


---

## 2. `POST /v1/inventory/restock`

Nhập hàng vào kho. Hỗ trợ **2 loại nghiệp vụ** thông qua field `reference_type`:

1. **`ADMIN_RESTOCK`** (mặc định) — Admin nhập hàng mới. Đây cũng là entry point khởi tạo duy nhất cho inventory records (lần restock đầu cho SKU sẽ tạo row trong `physical_inventory` với `product_id` denormalized).
2. **`ORDER_RETURNED_RESTOCK`** — Admin xác nhận đã nhận hàng hoàn về kho từ buyer. Trigger inline việc mark `refund_returned_at` trên reservation và có thể emit `INVENTORY_RETURN_RECEIVED_ALL` (xem [Luồng 6](inventory_workflow.md#luồng-6-return-restock-nhận-hàng-hoàn-về-kho)).

### Auth

| Loại | Chi tiết |
|:---|:---|
| **Required** | JWT Bearer Token |
| **Role** | `ADMIN` |

### Request

**Trường hợp `ADMIN_RESTOCK`:**

```json
{
  "warehouse_id": 5,
  "sku_id": "sku-001",
  "product_id": "prod-uuid-001",
  "quantity": 100
}
```

**Trường hợp `ORDER_RETURNED_RESTOCK`:**

```json
{
  "warehouse_id": 5,
  "sku_id": "sku-001",
  "product_id": "prod-uuid-001",
  "quantity": 2,
  "reference_type": "ORDER_RETURNED_RESTOCK",
  "order_id": 10001,
  "order_item_id": 2001,
  "refund_request_id": "rr-2026-0001"
}
```

| Field | Type | Required | Mô tả |
|:---|:---|:---:|:---|
| `warehouse_id` | Integer | ✅ | ID kho nhập hàng, phải tồn tại và `is_active = true` |
| `sku_id` | String(64) | ✅ | SKU identifier |
| `product_id` | String(64) | ✅ | Product identifier. Lưu denormalized vào `physical_inventory.product_id` |
| `quantity` | Integer | ✅ | Số lượng nhập, phải > 0 |
| `reference_type` | String(50) | ❌ (default `ADMIN_RESTOCK`) | Loại biến động. Hỗ trợ: `ADMIN_RESTOCK`, `ORDER_RETURNED_RESTOCK`. Phải nằm trong enum `reference_type` hợp lệ (xem [DB §1.4](inventory_db_design.md#14-inventory_ledger)). |
| `order_id` | Integer | ✅ nếu `reference_type = 'ORDER_RETURNED_RESTOCK'` | Đơn hàng gốc của item hoàn |
| `order_item_id` | Integer | ✅ nếu `reference_type = 'ORDER_RETURNED_RESTOCK'` | Item trong đơn hàng |
| `refund_request_id` | String(64) | ✅ nếu `reference_type = 'ORDER_RETURNED_RESTOCK'` | Mã yêu cầu hoàn (do Order cấp qua `/refund-expectations`) |

**HTTP Headers (bắt buộc với `ORDER_RETURNED_RESTOCK`, khuyến nghị với `ADMIN_RESTOCK`):**

| Header | Required | Mô tả |
|:---|:---:|:---|
| `Authorization` | ✅ | JWT Bearer Token (xem [Auth](#auth) phía trên). |
| `Content-Type` | ✅ | `application/json`. |

### Response

**200 OK** (cả 2 trường hợp)

```json
{
  "success": true,
  "warehouse_id": 5,
  "sku_id": "sku-001",
  "product_id": "prod-uuid-001",
  "reference_type": "ORDER_RETURNED_RESTOCK",
  "quantity_added": 2,
  "physical": {
    "actual_quantity": 152,
    "reserved_quantity": 0,
    "available_quantity": 152
  },
  "refund": {
    "order_id": 10001,
    "order_item_id": 2001,
    "refund_request_id": "rr-2026-0001",
    "refund_returned_at": "2026-03-05T09:58:00Z",
    "all_items_received": true
  },
  "event_id": "123e4567-e89b-12d3-a456-426614174003",
  "timestamp": "2026-04-01T10:00:00Z"
}
```

> - Trường `refund` chỉ xuất hiện khi `reference_type = 'ORDER_RETURNED_RESTOCK'`.
> - `all_items_received = true` nghĩa là item vừa restock là item cuối cùng của `refund_request_id` — hệ thống sẽ emit `INVENTORY_RETURN_RECEIVED_ALL` (downstream Order Service sẽ mở bước refund tiền). Khi `false`, hệ thống chỉ ghi `INVENTORY_STOCK_CHANGED`.
> - Trước đây response trả cả `physical` và `logical`. Nay không còn `logical`; chỉ trả physical.

**4xx Error Responses**

| Status | Code | Mô tả |
|:---:|:---|:---|
| `400` | `INVALID_REQUEST` | Validation lỗi, quantity <= 0, hoặc thiếu trường bắt buộc theo `reference_type` |
| `404` | `WAREHOUSE_NOT_FOUND` | `warehouse_id` không tồn tại hoặc không active |
| `404` | `RESERVATION_NOT_FOUND` | (chỉ với `ORDER_RETURNED_RESTOCK`) Không tìm thấy reservation `(order_id, order_item_id, status='DEDUCTED')` |
| `409` | `REFUND_NOT_EXPECTED` | (chỉ với `ORDER_RETURNED_RESTOCK`) Reservation tồn tại nhưng `refund_expected = false` (Order chưa gọi `/refund-expectations` cho item này) |

### Chi tiết hoạt động nội bộ

```
1. [API Layer] Validate JWT + scope + role
2. [Contract Guard]
   - warehouse_id tồn tại trong warehouses và is_active = true
   - quantity > 0
   - Nếu reference_type = 'ORDER_RETURNED_RESTOCK':
     + order_id, order_item_id, refund_request_id phải present
     + Reservation (order_id, order_item_id, status='DEDUCTED') phải tồn tại
     + Reservation đó phải có refund_expected = true
3. [PostgreSQL Transaction]
   BEGIN;
   -- Upsert physical_inventory (kèm product_id denormalized)
   INSERT INTO physical_inventory (sku_id, product_id, warehouse_id, actual_quantity, reserved_quantity, updated_at)
     VALUES (:sku, :product, :wh, :qty, 0, NOW())
     ON CONFLICT (sku_id, warehouse_id)
     DO UPDATE SET
       actual_quantity = physical_inventory.actual_quantity + EXCLUDED.actual_quantity,
       updated_at = NOW();
   -- Nếu ORDER_RETURNED_RESTOCK: mark reservation đã nhận về
   IF :reference_type = 'ORDER_RETURNED_RESTOCK' THEN
     UPDATE inventory_reservations
       SET refund_returned_at = NOW(), updated_at = NOW()
       WHERE order_id = :order_id
         AND order_item_id = :order_item_id
         AND status = 'DEDUCTED'
         AND refund_returned_at IS NULL
       RETURNING refund_request_id;
   END IF;
   -- Audit trail
   INSERT INTO inventory_ledger (
     event_id, sku_id, product_id, warehouse_id,
     reference_type, quantity_change, balance_after,
     correlation_id, created_at
   ) VALUES (:event_id, :sku, :product, :wh, :reference_type, :qty, :new_balance, :correlation_id, NOW());
   -- Outbox INVENTORY_STOCK_CHANGED (cho Product Service cập nhật display cache)
   INSERT INTO inventory_outbox (
     event_id, schema_version, aggregate_type, aggregate_id,
     event_type, partition_key, payload, created_at
   ) VALUES (
     :event_id, 1, 'Inventory', :sku,
     'INVENTORY_STOCK_CHANGED', :sku,
     '{"change_type":":reference_type","sku_id":"...","quantity_added":...}'::jsonb,
     NOW()
   );
    -- Nếu ORDER_RETURNED_RESTOCK: check refund completion
    IF :reference_type = 'ORDER_RETURNED_RESTOCK' THEN
      SELECT COUNT(*) INTO :pending
      FROM inventory_reservations
      WHERE order_id = :order_id
        AND refund_request_id = :refund_request_id
        AND refund_expected = TRUE
        AND status = 'DEDUCTED'
        AND refund_returned_at IS NULL;
      -- Aggregate order_item_ids cho payload (chỉ chạy khi pending = 0 bên dưới)
      SELECT COALESCE(array_agg(order_item_id), '{}') INTO :order_item_ids
      FROM inventory_reservations
      WHERE order_id = :order_id
        AND refund_request_id = :refund_request_id
        AND refund_expected = TRUE
        AND status = 'DEDUCTED'
        AND refund_returned_at IS NOT NULL;
      IF :pending = 0 THEN
        INSERT INTO inventory_outbox (
          event_id, schema_version, aggregate_type, aggregate_id,
          event_type, partition_key, payload, created_at
        ) VALUES (
          :event_id_ret, 1, 'Inventory', :order_id,
          'INVENTORY_RETURN_RECEIVED_ALL', :order_id,
          jsonb_build_object(
            'order_id', :order_id,
            'refund_request_id', :refund_request_id,
            'order_item_ids', :order_item_ids,
            'confirmed_at', NOW()
          ),
          NOW()
        );
      END IF;
   END IF;
   COMMIT;
4. [Redis Cache Update]
   HMSET inventory:physic:{sku_id:warehouse_id}:stock
     total {new_actual}, reserved 0, available {new_actual}
5. [Debezium CDC]
   Read WAL of inventory_outbox
   Produce Kafka: topic=t.inventory.events, key=sku_id (cho INVENTORY_STOCK_CHANGED)
                  hoặc key=order_id (cho INVENTORY_RETURN_RECEIVED_ALL)
```


---

## 3. `POST /v1/inventory/returns/refund-expectations`

Đăng ký một tập hợp item cần hoàn với Inventory. Inventory đánh dấu các `inventory_reservations` row tương ứng với `refund_expected = true` để khi admin restock hàng hoàn về kho, hệ thống biết khi nào đủ điều kiện phát signal `INVENTORY_RETURN_RECEIVED_ALL`.

### Auth

| Loại | Chi tiết |
|:---|:---|
| **Required** | Internal Service Token (OIDC m2m) |
| **Service** | Order Service |
| **Header** | `X-Internal-Service: order-service` |

### Request

```json
{
  "order_id": 10001,
  "refund_request_id": "rr-2026-0001",
  "order_item_ids": [2001, 2002]
}
```

| Field | Type | Required | Mô tả |
|:---|:---|:---:|:---|
| `order_id` | Integer | ✅ | ID đơn hàng cần hoàn |
| `refund_request_id` | String(64) | ✅ | ID yêu cầu hoàn từ Order Service |
| `order_item_ids` | Array[Integer) | ✅ | Danh sách `order_item_id` cần hoàn đầy đủ |

### Response

**200 OK**

```json
{
  "order_id": 10001,
  "refund_request_id": "rr-2026-0001",
  "marked_count": 2
}
```

| Field | Type | Mô tả |
|---|:---|:---|
| `order_id` | Integer | Từ request |
| `refund_request_id` | String | Từ request |
| `marked_count` | Integer | Số reservation row được update `refund_expected = true` |

**409 Conflict** — `REFUND_SCOPE_MISMATCH`

```json
{
  "error": "REFUND_SCOPE_MISMATCH",
  "message": "Refund expectation already exists with different order_item_ids",
  "existing_order_item_ids": [2001],
  "requested_order_item_ids": [2001, 2002]
}
```

| Status | Code | Mô tả |
|:---:|:---|:---|
| `400` | `INVALID_REQUEST` | Validation lỗi |
| `404` | `RESERVATION_NOT_FOUND` | Không tìm thấy `DEDUCTED` reservation nào cho tập `order_item_ids` |
| `409` | `REFUND_SCOPE_MISMATCH` | Cùng `(order_id, refund_request_id)` đã đăng ký với tập item khác |

### Chi tiết hoạt động nội bộ

```
1. [API Layer] Validate internal service token
2. [Idempotency check]
   SELECT refund_request_id, order_item_id
   FROM inventory_reservations
   WHERE order_id = :order_id
     AND refund_request_id = :refund_request_id
     AND refund_expected = TRUE
     AND status = 'DEDUCTED';
   IF existing set != requested:
     RETURN 409 REFUND_SCOPE_MISMATCH
   IF existing set == requested:
     RETURN 200 OK (idempotent hit)
3. [Mark expectation]
   UPDATE inventory_reservations
   SET refund_expected = TRUE,
       refund_request_id = :refund_request_id,
       updated_at = NOW()
   WHERE order_id = :order_id
     AND order_item_id = ANY(:order_item_ids)
     AND status = 'DEDUCTED'
     AND refund_returned_at IS NULL;
   RETURN marked count
```

### Quy tắc nghiệp vụ

| Trường hợp | Hành động |
|:---|:---|
| Lần đầu đăng ký | UPDATE các reservation row match, set `refund_expected = true` |
| Replay cùng `(order_id, refund_request_id, order_item_ids)` | Trả 200 OK idempotent |
| Replay khác `order_item_ids` cho cùng `(order_id, refund_request_id)` | Trả `409 REFUND_SCOPE_MISMATCH` |

> Khi tất cả `refund_expected = true` reservation cùng `(order_id, refund_request_id)` đều có `refund_returned_at IS NOT NULL` (do admin restock với `reference_type = 'ORDER_RETURNED_RESTOCK'` cập nhật — xem [Endpoints §2](inventory_endpoints.md#2-post-v1inventoryrestock)), hệ thống insert `inventory_outbox` event `INVENTORY_RETURN_RECEIVED_ALL` để Debezium publish lên Kafka, signal cho Order mở bước refund tiền. Chi tiết tại [Workflow](inventory_workflow.md).

---

## 4. `POST /v1/inventory/ship-to-customer`

Admin xác nhận kho đã xuất hàng cho khách. Trigger event `INVENTORY_SHIP_TO_CUSTOMER` → Order Service consume → chuyển `order_items.status` từ `PROCESSING` sang `DELIVERED`.

### Auth

| Loại | Chi tiết |
|:---|:---|
| **Required** | JWT Bearer Token |
| **Role** | `ADMIN` |

### Request

```json
{
  "order_id": 10001,
  "warehouse_id": 5,
  "shipped_at": "2026-03-02T16:00:00Z",
  "courier_note": "Đã bàn giao cho đơn vị vận chuyển nội bộ"
}
```

| Field | Type | Required | Mô tả |
|:---|:---|:---:|:---|
| `order_id` | Integer | ✅ | ID đơn hàng đã shipped |
| `warehouse_id` | Integer | ✅ | Kho xuất hàng |
| `shipped_at` | ISO 8601 | ✅ | Mốc thời gian xuất kho |
| `courier_note` | String(512) | ❌ | Ghi chú nội bộ (audit) |

### Response

**200 OK**

```json
{
  "success": true,
  "order_id": 10001,
  "warehouse_id": 5,
  "shipped_at": "2026-03-02T16:00:00Z",
  "event_id": "123e4567-e89b-12d3-a456-426614174003",
  "affected_items": 2
}
```

**4xx Error Responses**

| Status | Code | Mô tả |
|:---:|:---|:---|
| `400` | `INVALID_REQUEST` | Validation lỗi |
| `404` | `ORDER_NOT_FOUND` | `order_id` không có bất kỳ `inventory_reservations` row nào (lookup tại `inventory_reservations.order_id`). Lưu ý: Inventory Service không sở hữu bảng `orders`; việc validate `order_id` chỉ dựa trên sự tồn tại của reservation tương ứng trong inventory. |

### Chi tiết hoạt động nội bộ

```
1. [API Layer] Validate JWT + scope + role
2. [Contract Guard] Validate order_id tồn tại (lookup tại `inventory_reservations.order_id`), warehouse_id tồn tại & active
3. [Validate warehouse has DEDUCTED reservations] Query `SELECT COUNT(*) FROM inventory_reservations WHERE order_id = :order_id AND warehouse_id = :warehouse_id AND status = 'DEDUCTED'`
   IF count = 0: RETURN 404 `RESERVATION_NOT_FOUND` (không có reservation DEDUCTED nào của order này tại kho này)
4. [Count affected items] Cùng query trên: `COUNT(*)` → trả về `affected_items`
5. [DB Insert outbox]
   BEGIN;
   INSERT INTO inventory_outbox (
     event_id, schema_version, aggregate_type, aggregate_id,
     event_type, partition_key, payload, created_at
   ) VALUES (
     :event_id, 1, 'Inventory', :order_id,
     'INVENTORY_SHIP_TO_CUSTOMER', :order_id,
     '{"order_id":..., "warehouse_id":..., "shipped_at":..., "courier_note":...}'::jsonb,
     NOW()
   );
   COMMIT;
6. [Debezium CDC]
   Read WAL of inventory_outbox
   Produce Kafka: topic=t.inventory.events, key=order_id
   Event: INVENTORY_SHIP_TO_CUSTOMER
7. [Order Service consume]
   order-inventory-ship-consumer (consumer group)
   → Lookup `inventory_reservations` theo `(order_id, warehouse_id)` lấy `order_item_ids` có `status='DEDUCTED'`
   → Với mỗi `order_item_id`, update `order_items.status` PROCESSING → DELIVERED
   → Idempotent theo event_id
   → Sau đó Auto-Complete check: nếu tất cả items của order (mọi warehouse) đều DELIVERED → ORDER_COMPLETED
```

> **⚠️ Multi-warehouse quan trọng:** Nếu order có item ở nhiều kho (multi-warehouse fulfillment), admin phải gọi `ship-to-customer` **riêng cho từng warehouse**. Filter `warehouse_id` là bắt buộc ở cả 3 bước:
> - Bước 3 (validate): chỉ ship khi kho này có reservation DEDUCTED
> - Bước 4 (`affected_items`): đếm đúng item của kho này
> - Bước 7 (Order consumer): chỉ update `order_items` của kho này
> 
> Nếu bỏ filter `warehouse_id`, sai ngữ nghĩa nghiêm trọng: item ở kho A có thể bị DELIVERED khi kho B gọi ship-to-customer.

> **Tại sao không ghi `inventory_ledger` / `physical_inventory` / `inventory_reservations`?**
>
> - Stock đã được `DEDUCTED` từ lúc payment success (xem [Workflow §2](inventory_workflow.md#luồng-2-deduct-inventory-xác-nhận-bán-đứt)), shipping chỉ là signal "kho đã xuất hàng vật lý" chứ không phải biến động tồn kho.
> - `inventory_ledger` chỉ ghi nhận biến động tồn kho (xem [DB §1.4](inventory_db_design.md#14-inventory_ledger)). Ghi ledger cho ship-to-customer sẽ vi phạm invariant "mỗi ledger entry đại diện cho 1 biến động tồn kho" và cần reference_type mới không có trong enum hợp lệ.
> - `inventory_reservations.status` giữ nguyên `DEDUCTED` vì stock thực sự đã bị trừ. Trạng thái shipping thuộc về `order_items.status` bên Order Service.
> - Không có `INVENTORY_STOCK_CHANGED` kèm theo vì không có biến động tồn kho.

---

## 5. `POST /v1/inventory/admin/unlock-sku`

Manual recovery: mở khoá SKU bị suspend (do invariant drift). Đây là bước thay thế cho auto-recovery cũ — bắt buộc có sự can thiệp của con người sau khi đã đối soát Redis/DB.

### Auth

| Loại | Chi tiết |
|:---|:---|
| **Required** | JWT Bearer Token |
| **Scope** | `inventory:admin` |
| **Role** | `ADMIN` |

### Request

```json
{
  "sku_id": "sku-001",
  "warehouse_id": 5,
  "reason": "Reconciled after manual fix",
  "correlation_id": "ops-2026-03-15-001"
}
```

| Field | Type | Required | Mô tả |
|:---|:---|:---:|:---|
| `sku_id` | String(64) | ✅ | SKU cần unlock |
| `warehouse_id` | Integer | ✅ | Kho cần unlock |
| `reason` | String(512) | ✅ | Lý do unlock (audit log) |
| `correlation_id` | String(128) | ✅ | ID phiên vận hành |

### Response

**200 OK**

```json
{
  "sku_id": "sku-001",
  "warehouse_id": 5,
  "unlocked": true,
  "unlocked_at": "2026-03-15T10:00:00Z"
}
```

**4xx Error Responses**

| Status | Code | Mô tả |
|:---:|:---|:---|
| `400` | `INVALID_REQUEST` | Validation lỗi |
| `404` | `SKU_NOT_SUSPENDED` | SKU chưa từng bị suspend hoặc đã unlock rồi |

### Chi tiết hoạt động nội bộ

```
1. [API Layer] Validate JWT + scope + role
2. [DEL suspended key]
   EXISTS inventory:suspended:{sku:wh}?
   IF NOT EXISTS: RETURN 404 SKU_NOT_SUSPENDED
   DEL inventory:suspended:{sku:wh}
3. [Audit log]
   Ghi log với sku_id, warehouse_id, reason, correlation_id, timestamp
4. [Response] Return unlocked = true
```

> Sau khi unlock, request `RESERVED/DEDUCT/RELEASE` tới SKU đó sẽ pass Lua script `KEYS[4] EXISTS` check và tiếp tục bình thường. Admin vẫn phải tự verify rằng `physical_inventory` đã nhất quán trước khi gọi unlock.

---

## Appendix: Temporal Activities (Non-HTTP)

Reserve, Deduct, Release KHÔNG phải HTTP endpoints. Chúng được gọi qua Temporal SDK bởi Order Temporal Saga.

### `ReserveInventoryActivity`

```json
{
  "order_id": 10001,
  "route_plan_id": "7b7dddad-6f6c-4f63-a728-df0fe96b9f31",
  "items": [
    { "sku_id": "sku-001", "product_id": "prod-uuid-001", "quantity": 2, "warehouse_id": 5 }
  ]
}
```

- Gọi Redis Lua Script 1 (Reserve).
- Event `INVENTORY_STOCK_MUTATION` (change_type=ORDER_RESERVED) được XADD vào Redis Stream -> Relay Worker -> Kafka.
- Ledger Worker ghi `inventory_reservations` + `inventory_ledger` + `inventory_outbox`.

### `DeductInventoryActivity`

```json
{
  "order_id": 10001,
  "items": [
    { "sku_id": "sku-001", "product_id": "prod-uuid-001", "quantity": 2, "warehouse_id": 5 }
  ]
}
```

- Gọi Redis Lua Script 3 (Deduct).
- Event `INVENTORY_STOCK_MUTATION` (change_type=ORDER_DEDUCTED) được XADD vào Redis Stream -> Relay Worker -> Kafka.
- Ledger Worker ghi `inventory_reservations.status = DEDUCTED` + `inventory_ledger` + `inventory_outbox`.

### `RollbackInventoryActivity`

```json
{
  "order_id": 10001,
  "items": [
    { "sku_id": "sku-001", "product_id": "prod-uuid-001", "quantity": 2, "warehouse_id": 5 }
  ],
  "change_type": "ORDER_CANCELLED"
}
```

- `change_type`: `ORDER_CANCELLED` (do Temporal compensation) | `RESERVATION_EXPIRED` (do Temporal timeout, hiếm).
- Gọi Redis Lua Script 2 (Release).
- Event `INVENTORY_STOCK_MUTATION` (change_type theo request) được XADD vào Redis Stream -> Relay Worker -> Kafka.
- Ledger Worker ghi `inventory_reservations.status = CANCELLED` + `inventory_ledger` + `inventory_outbox`.

### Lua return code mapping

| Code | Reason | Temporal Activity result | Hành động |
|:---:|:---|:---|:---|
| `{1, ''}` | Apply mới | Success | Event đã emit, tiếp tục saga |
| `{2, ''}` | Idempotent hit | Success | Đã apply trước đó, không retry, trả success |
| `{0, 'OUT_OF_STOCK'}` | Hết hàng | `OUT_OF_STOCK` | Saga compensation |
| `{0, 'INSUFFICIENT_RESERVED'}` | Deduct không đủ | `INSUFFICIENT_RESERVED` | Trả lỗi nghiệp vụ |
| `{0, 'RELEASE_UNDERFLOW'}` | Release không đủ | `RELEASE_UNDERFLOW` | Trả lỗi nghiệp vụ |
| `{0, 'SKU_SUSPENDED'}` | SKU bị khoá | `SKU_SUSPENDED` | Trả lỗi nghiệp vụ, yêu cầu admin unlock |
| `{-1, 'INVARIANT_DRIFT'}` | Drift | `INVARIANT_BREACH` | Suspend SKU, fail activity, alert ops |