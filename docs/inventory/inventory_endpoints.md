# Inventory Service - API Endpoints

**Liên kết nhanh (Related Documents):**
* [Database Schema](inventory_db_design.md)
* [Redis Caching & Lua Scripts](inventory_redis_design.md)
* [Events Structure & Kafka Topics](inventory_events.md)
* [Internal Data Flow](inventory_workflow.md)
* [Background Workers & Jobs](inventory_workers.md)
* [Smart Routing Engine](smart_routing_engine.md)
* [Observability & SLO](inventory_observability_slo.md)

---

Tài liệu này mô tả chi tiết toàn bộ HTTP API endpoints của Inventory Service: method, endpoint path, authentication, request/response body, và mô tả luồng hoạt động nội bộ.

**Lưu ý về đơn giản hóa:** API `routing-options` đã loại bỏ các version field và tier; API `restock` giữ nguyên; thay thế `returns/refund-scopes` (bảng phức tạp) bằng `returns/refund-expectations` (đánh dấu row trực tiếp); thêm `admin/unlock-sku` cho manual recovery.

---

## Tổng quan

| Method | Endpoint | Auth | Mô tả |
|:---|:---|:---|:---|
| `POST` | `/v1/inventory/routing-options` | JWT (User) | Tạo route plan fulfillment cho checkout preview |
| `POST` | `/v1/inventory/restock` | JWT (Admin) | Admin nhập hàng vào kho |
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
| **Ghi chú** | Customer location được trích từ JWT claims hoặc request body |

### Request

```json
{
  "items": [
    { "sku_id": "sku-001", "quantity": 2 },
    { "sku_id": "sku-002", "quantity": 1 }
  ],
  "customer_location": {
    "latitude": 10.7626,
    "longitude": 106.6602,
    "city_code": "HCM",
    "district_code": "Q1"
  },
  "service_type": "STANDARD"
}
```

| Field | Type | Required | Mô tả |
|:---|:---|:---:|:---|
| `items` | Array[Object] | ✅ | Danh sách SKU cần routing |
| `items[].sku_id` | String(64) | ✅ | SKU identifier |
| `items[].quantity` | Integer | ✅ | Số lượng cần đặt |
| `customer_location` | Object | ✅ | Vị trí giao hàng |
| `customer_location.latitude` | Decimal | ✅ | Vĩ độ |
| `customer_location.longitude` | Decimal | ✅ | Kinh độ |
| `customer_location.city_code` | String(20) | ✅ | Mã tỉnh/thành (vd: `HCM`) |
| `customer_location.district_code` | String(20) | ✅ | Mã quận/huyện |
| `service_type` | String | ✅ | Mức dịch vụ: `STANDARD`, `EXPRESS` |

### Response

**200 OK**

```json
{
  "route_plan_id": "7b7dddad-6f6c-4f63-a728-df0fe96b9f31",
  "expires_at": "2026-04-01T08:20:00Z",
  "rate_card_version": "2026-04-01T07:55:00Z",
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
  "shipping_options": [
    {
      "warehouse_id": 5,
      "options": [
        {
          "provider": "GHN",
          "service_type": "STANDARD",
          "fee": 35000,
          "delivery_days": 2
        },
        {
          "provider": "GHTK",
          "service_type": "EXPRESS",
          "fee": 45000,
          "delivery_days": 1
        }
      ]
    }
  ]
}
```

| Field | Type | Mô tả |
|:---|:---|:---|
| `route_plan_id` | UUID | ID duy nhất của route plan, dùng để submit checkout |
| `expires_at` | ISO 8601 | Hết hiệu lực. Sau thời điểm này, checkout submit sẽ bị reject |
| `rate_card_version` | ISO 8601 | Phiên bản bảng giá shipping (do Shipping trả, để Checkout detect rate drift trong `pricing_hash`) |
| `items[].sku_id` | String | SKU đã được assign |
| `items[].product_id` | String | Product denormalized (lấy từ `physical_inventory.product_id`, dùng cho `ReserveInventoryActivity` không cần lookup thêm) |
| `items[].quantity` | Integer | Số lượng từ request |
| `items[].warehouse_id` | Integer | Kho fulfill item này |
| `shipping_options[]` | Array[Object] | Các option vận chuyển khả dụng, **per-warehouse** |
| `shipping_options[].warehouse_id` | Integer | Kho tương ứng với options[] |
| `shipping_options[].options[].provider` | String | 3PL provider code (vd: `GHN`, `NINJAVAN`) |
| `shipping_options[].options[].service_type` | String | Mức dịch vụ: `STANDARD` \| `EXPRESS` |
| `shipping_options[].options[].fee` | Integer | Phí ship flat-rate theo cặp tuyến + service_type |
| `shipping_options[].options[].delivery_days` | Integer | Số ngày dự kiến giao |

**4xx Error Responses**

| Status | Code | Mô tả |
|:---:|:---|:---|
| `400` | `INVALID_REQUEST` | Missing required fields hoặc validation lỗi |
| `400` | `INVALID_SERVICE_TYPE` | `service_type` không hợp lệ |
| `404` | `SKU_NOT_FOUND` | Một hoặc nhiều SKU không tồn tại trong inventory |
| `409` | `INSUFFICIENT_STOCK` | Không đủ tồn kho khả dụng cho routing |
| `422` | `NO_SHIPPING_OPTIONS_FOR_ROUTE` | Shipping trả về `options[]` rỗng cho ít nhất 1 warehouse trong feasible combinations |

### Chi tiết hoạt động nội bộ

```
1. [API Layer] Nhận request, validate JWT + scope
2. [Candidate Generation]
   - SELECT sku_id, product_id, warehouse_id, available_quantity
     FROM physical_inventory
     WHERE sku_id = ANY(:sku_ids) AND available_quantity > 0
   - Build feasible warehouse combinations đủ stock cho mọi items
3. [Shipping Quote Call]
   - Gọi Shipping Service: POST /v1/shipping/calculate-batch
   - Input: warehouse list + customer_location + service_type
   - Output: shipping fee + delivery_days per warehouse (flat-rate theo from→to)
4. [Smart Routing Engine Scoring]
   score = shipping_cost + delivery_days * cost_per_day
   Tie-break: warehouse_id ASC (deterministic)
5. [Cache]
   SET route_plan:{route_plan_id} = JSON, TTL = expires_at - now
6. [Response] Trả route plan với concrete warehouse_id cho mỗi item + product_id denormalized
```

### Plan Expiry

- Route plan có TTL 20 phút (mặc định).
- Khi Checkout submit với `route_plan_id`:
  - Nếu plan còn trong cache (TTL chưa hết): proceed.
  - Nếu plan đã expire: reject `CONTRACT_INVALID_ROUTE_PLAN`, Checkout phải gọi lại `/routing-options`.

> Đã lược bỏ: `algorithm_version`, `inventory_snapshot_at`, `route_version` (chỉ có 1 algorithm, không cần version determinism), `route_signature` (thừa vì Smart Routing scoring đã deterministic). Chỉ giữ `rate_card_version` (lấy trực tiếp từ Shipping `rate_card_version`) vì đó là thông tin từ bên thứ 3 cần thiết để Checkout revalidate rate trong `pricing_hash`. Chi tiết tại [Smart Routing Engine](smart_routing_engine.md).

---

## 2. `POST /v1/inventory/restock`

Admin nhập hàng vào kho. Đây là entry point khởi tạo duy nhất cho inventory records (lần restock đầu cho SKU sẽ tạo row trong `physical_inventory` với `product_id` denormalized).

### Auth

| Loại | Chi tiết |
|:---|:---|
| **Required** | JWT Bearer Token |
| **Scope** | `inventory:admin`, `inventory:write` |
| **Role** | `ADMIN` hoặc `WMS_OPERATOR` |

### Request

```json
{
  "warehouse_id": 5,
  "sku_id": "sku-001",
  "product_id": "prod-uuid-001",
  "quantity": 100
}
```

| Field | Type | Required | Mô tả |
|:---|:---|:---:|:---|
| `warehouse_id` | Integer | ✅ | ID kho nhập hàng, phải tồn tại và `is_active = true` |
| `sku_id` | String(64) | ✅ | SKU identifier |
| `product_id` | String(64) | ✅ | Product identifier. Lưu denormalized vào `physical_inventory.product_id` |
| `quantity` | Integer | ✅ | Số lượng nhập, phải > 0 |

### Response

**200 OK**

```json
{
  "success": true,
  "warehouse_id": 5,
  "sku_id": "sku-001",
  "product_id": "prod-uuid-001",
  "quantity_added": 100,
  "physical": {
    "actual_quantity": 150,
    "reserved_quantity": 0,
    "available_quantity": 150
  },
  "event_id": "123e4567-e89b-12d3-a456-426614174003",
  "timestamp": "2026-04-01T10:00:00Z"
}
```

> Trước đây response trả cả `physical` và `logical`. Nay không còn `logical`; chỉ trả physical.

**4xx Error Responses**

| Status | Code | Mô tả |
|:---:|:---|:---|
| `400` | `INVALID_REQUEST` | Validation lỗi hoặc quantity <= 0 |
| `404` | `WAREHOUSE_NOT_FOUND` | `warehouse_id` không tồn tại hoặc không active |

### Chi tiết hoạt động nội bộ

```
1. [API Layer] Validate JWT + scope + role
2. [Contract Guard]
   - warehouse_id tồn tại trong warehouses và is_active = true
   - quantity > 0
3. [PostgreSQL Transaction]
   BEGIN;
   -- Upsert physical_inventory (kèm product_id denormalized)
   INSERT INTO physical_inventory (sku_id, product_id, warehouse_id, actual_quantity, reserved_quantity, updated_at)
     VALUES (:sku, :product, :wh, :qty, 0, NOW())
     ON CONFLICT (sku_id, warehouse_id)
     DO UPDATE SET
       actual_quantity = physical_inventory.actual_quantity + EXCLUDED.actual_quantity,
       updated_at = NOW();
   -- Audit trail
   INSERT INTO inventory_ledger (
     event_id, sku_id, product_id, warehouse_id,
     reference_type, quantity_change, balance_after,
     correlation_id, created_at
   ) VALUES (:event_id, :sku, :product, :wh, 'ADMIN_RESTOCK', :qty, :new_balance, :correlation_id, NOW());
   -- Outbox for CDC
   INSERT INTO inventory_outbox (
     event_id, schema_version, aggregate_type, aggregate_id,
     event_type, partition_key, payload, created_at
   ) VALUES (
     :event_id, 1, 'Inventory', :sku,
     'INVENTORY_STOCK_CHANGED', :sku,
     '{"change_type":"ADMIN_RESTOCK","sku_id":"...","quantity_added":...}'::jsonb,
     NOW()
   );
   COMMIT;
4. [Redis Cache Update]
   HMSET inventory:physic:{sku_id:warehouse_id}:stock
     total {new_actual}, reserved 0, available {new_actual}
5. [Debezium CDC]
   Read WAL of inventory_outbox
   Produce Kafka: topic=t.inventory.events, key=sku_id
   Event: INVENTORY_STOCK_CHANGED (change_type=ADMIN_RESTOCK)
```

> Đã lược bỏ kiểm tra `SKU_PRODUCT_MISMATCH` (so sánh product_id với bảng `sku_product_mapping`) vì bảng này không còn tồn tại. Caller (Admin/WMS) chịu trách nhiệm cung cấp đúng cặp `sku_id` + `product_id`.

### Idempotency

- Dùng `event_id` (UUID) làm idempotency key: re-submit cùng request với cùng `event_id` sẽ không tạo duplicate ledger entry (UNIQUE constraint trên `event_id`).
- Caller có thể sinh `Idempotency-Key` ở client, server dùng làm `event_id` cho cả ledger lẫn outbox.

---

## 3. `POST /v1/inventory/returns/refund-expectations`

Đăng ký một tập hợp item cần hoàn với Inventory. Inventory đánh dấu các `inventory_reservations` row tương ứng với `refund_expected = true` để khi `SHIPMENT_RETURNED` đến, hệ thống biết khi nào đủ điều kiện phát signal `INVENTORY_RETURN_RECEIVED_ALL`.

API này thay thế cho bảng `inventory_refund_scopes` cũ (vốn có JSONB aggregation và state machine phức tạp). Logic bây giờ gói gọn trong 3 cột của `inventory_reservations`: `refund_expected`, `refund_request_id`, `refund_returned_at`.

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
| `order_item_ids` | Array[Integer] | ✅ | Danh sách `order_item_id` cần hoàn đầy đủ |

### Response

**200 OK** (idempotent success)

```json
{
  "order_id": 10001,
  "refund_request_id": "rr-2026-0001",
  "marked_count": 2
}
```

| Field | Type | Mô tả |
|:---|:---|:---|
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
     AND refund_expected = TRUE;
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

> Khi tất cả `refund_expected = true` reservation cùng `(order_id, refund_request_id)` đều có `refund_returned_at IS NOT NULL` (do `SHIPMENT_RETURNED` consumer cập nhật), hệ thống insert `inventory_outbox` event `INVENTORY_RETURN_RECEIVED_ALL` để Debezium publish lên Kafka, signal cho Order mở bước refund tiền. Chi tiết tại [Workflow §6](inventory_workflow.md).

---

## 4. `POST /v1/inventory/admin/unlock-sku`

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
