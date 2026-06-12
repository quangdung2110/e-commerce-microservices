# Inventory Service - API Endpoints

**Liên kết nhanh (Related Documents):**
* [Database & Cache Schema](inventory_db_design.md)
* [Redis Caching & Lua Scripts](inventory_redis_design.md)
* [Events Structure & Kafka Topics](inventory_events.md)
* [Internal Data Flow](inventory_workflow.md)
* [Background Workers & Jobs](inventory_workers.md)
* [Observability & SLO](inventory_observability_slo.md)

---

Tài liệu này mô tả chi tiết toàn bộ HTTP API endpoints của Inventory Service: method, endpoint path, authentication, request/response body, và mô tả luồng hoạt động nội bộ.

---

## Tổng quan

| Method | Endpoint | Auth | Mô tả |
|:---|:---|:---|:---|
| `POST` | `/v1/inventory/routing-options` | JWT (User) | Tạo route plan fulfillment cho checkout preview |
| `POST` | `/v1/inventory/restock` | JWT (Admin) | Admin nhập hàng vào kho |
| `POST` | `/v1/inventory/returns/refund-scopes` | Internal (Service-to-Service) | Đăng ký refund scope trước khi hoàn hàng |

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
    "city_code": "SG",
    "district_code": "Q1"
  },
  "service_level": "STANDARD"
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
| `customer_location.city_code` | String(20) | ✅ | Mã tỉnh/thành |
| `customer_location.district_code` | String(20) | ✅ | Mã quận/huyện |
| `service_level` | String | ✅ | Mức dịch vụ: `STANDARD`, `EXPRESS`, `FAST_LANE` |

### Response

**200 OK**

```json
{
  "route_plan_id": "7b7dddad-6f6c-4f63-a728-df0fe96b9f31",
  "route_version": 3,
  "algorithm_version": "routing-v2.1.0",
  "inventory_snapshot_at": "2026-04-01T08:00:00Z",
  "shipping_rate_version": "2026-04-01T07:55:00Z",
  "expires_at": "2026-04-01T08:20:00Z",
  "items": [
    {
      "sku_id": "sku-001",
      "quantity": 2,
      "warehouse_id": 5,
      "routing_tier": "SMART_ROUTE"
    },
    {
      "sku_id": "sku-002",
      "quantity": 1,
      "warehouse_id": 5,
      "routing_tier": "FAST_LANE"
    }
  ],
  "shipping_options": [
    {
      "provider": "GHN",
      "service": "STANDARD",
      "fee": 35000,
      "delivery_days": 2
    },
    {
      "provider": "GHTK",
      "service": "EXPRESS",
      "fee": 45000,
      "delivery_days": 1
    }
  ]
}
```

| Field | Type | Mô tả |
|:---|:---|:---|
| `route_plan_id` | UUID | ID duy nhất của route plan, dùng để submit checkout |
| `route_version` | Integer | Version để detect stale plan |
| `algorithm_version` | String | Phiên bản thuật toán routing (deterministic) |
| `inventory_snapshot_at` | ISO 8601 | Thời điểm chụp tồn kho |
| `shipping_rate_version` | ISO 8601 | Phiên bản bảng giá shipping |
| `expires_at` | ISO 8601 | Hết hiệu lực. Sau thời điểm này, checkout submit sẽ bị reject |
| `items[].sku_id` | String | SKU đã được assign |
| `items[].quantity` | Integer | Số lượng từ request |
| `items[].warehouse_id` | Integer | Kho fulfill item này |
| `items[].routing_tier` | String | `SMART_ROUTE` / `FAST_LANE` — tier được chọn |
| `shipping_options` | Array[Object] | Các option vận chuyển khả dụng |

**4xx Error Responses**

| Status | Code | Mô tả |
|:---:|:---|:---|
| `400` | `INVALID_REQUEST` | Missing required fields hoặc validation lỗi |
| `400` | `INVALID_SERVICE_LEVEL` | `service_level` không hợp lệ |
| `404` | `SKU_NOT_FOUND` | Một hoặc nhiều SKU không tồn tại trong inventory |
| `409` | `INSUFFICIENT_STOCK` | Không đủ tồn kho khả dụng cho routing |

### Chi tiết hoạt động nội bộ

```
1. [API Layer] Nhận request, validate JWT + scope
2. [Candidate Generation]
   - Query physical_inventory.available_quantity theo warehouse
   - Build feasible warehouse combinations đủ stock cho mọi items
3. [Shipping Quote Call]
   - Gọi Shipping Service: POST /v1/shipping/calculate-batch
   - Input: warehouse list + customer_location + service_level
   - Output: shipping fee + delivery_days per warehouse
4. [Smart Routing Engine Scoring]
   score = w1*shipping_cost + w2*delivery_days + w3*split_penalty + w4*warehouse_priority
   Lower score = better option
   Tie-break: lower cost -> faster ETA -> fewer splits -> stable warehouse order
5. [Fast-Lane Fallback]
   Nếu không có SMART_ROUTE feasible và policy cho phép:
   - Chọn warehouse từ pre-ranked fast lane pool
   - Mark routing_tier = FAST_LANE
6. [Cache] Route plan được cache tại Redis với TTL = expires_at
7. [Response] Trả route plan với version deterministic
```

### Determinism & Replay

- Route plan là **deterministic**: cùng input snapshot + rate card + algorithm version -> cùng output.
- Khi Checkout submit với `route_plan_id + route_version`:
  - Nếu plan còn hiệu lực: proceed.
  - Nếu plan expired: reject `CONTRACT_INVALID_ROUTE_PLAN`, Checkout phải re-preview.

---

## 2. `POST /v1/inventory/restock`

Admin nhập hàng vào kho. Đây là entry point khởi tạo duy nhất cho inventory records.

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
| `product_id` | String(64) | ✅ | Product identifier (sẽ được verify qua `sku_product_mapping`) |
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
  "logical": {
    "total_quantity": 150,
    "reserved_quantity": 0,
    "available_quantity": 150
  },
  "event_id": "123e4567-e89b-12d3-a456-426614174003",
  "timestamp": "2026-04-01T10:00:00Z"
}
```

**4xx Error Responses**

| Status | Code | Mô tả |
|:---:|:---|:---|
| `400` | `INVALID_REQUEST` | Validation lỗi hoặc quantity <= 0 |
| `400` | `SKU_PRODUCT_MISMATCH` | `product_id` không khớp với `sku_id` trong `sku_product_mapping` |
| `404` | `WAREHOUSE_NOT_FOUND` | `warehouse_id` không tồn tại hoặc không active |

### Chi tiết hoạt động nội bộ

```
1. [API Layer] Validate JWT + scope + role
2. [Contract Guard]
   - warehouse_id tồn tại trong warehouses
   - sku_product_mapping exists: product_id khớp sku_id
3. [PostgreSQL Transaction]
   BEGIN;
   -- Upsert physical_inventory
   INSERT INTO physical_inventory (sku_id, product_id, warehouse_id, actual_quantity, reserved_quantity, updated_at)
     VALUES (:sku, :product, :wh, :qty, 0, NOW())
     ON CONFLICT (sku_id, warehouse_id)
     DO UPDATE SET
       actual_quantity = physical_inventory.actual_quantity + EXCLUDED.actual_quantity,
       updated_at = NOW();
   -- Upsert logical_inventory
   INSERT INTO logical_inventory (sku_id, product_id, total_quantity, reserved_quantity, version, updated_at)
     VALUES (:sku, :product, :qty, 0, 1, NOW())
     ON CONFLICT (sku_id)
     DO UPDATE SET
       total_quantity = logical_inventory.total_quantity + EXCLUDED.total_quantity,
       version = logical_inventory.version + 1,
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
     'INVENTORY_QUANTITY_CHANGED', :sku,
     '{"change_type":"ADMIN_RESTOCK","sku_id":"...","quantity_added":...}'::jsonb,
     NOW()
   );
   COMMIT;
4. [Redis Cache Update]
   HMSET inventory:physic:{sku_id:warehouse_id}:stock
     total {new_actual}, reserved 0, available {new_actual}
   HMSET inventory:logic:{sku_id}:stock
     total {new_total}, reserved 0, available {new_total}
5. [Debezium CDC]
   Read WAL of inventory_outbox
   Produce Kafka: topic=t.inventory.events, key=sku_id
   Event: INVENTORY_QUANTITY_CHANGED (change_type=ADMIN_RESTOCK)
```

### Idempotency

- Dùng `event_id` (UUID) làm idempotency key: re-submit cùng request với cùng payload sẽ không tạo duplicate ledger entry (UNIQUE constraint trên `event_id`).

---

## 3. `POST /v1/inventory/returns/refund-scopes`

Đăng ký một refund scope (tập hợp item cần hoàn) với Inventory. Inventory dùng scope này để xác định khi nào đã nhận đủ item và phát signal `INVENTORY_RETURN_RECEIVED_ALL` cho Order.

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
  "required_order_item_ids": [2001, 2002]
}
```

| Field | Type | Required | Mô tả |
|:---|:---|:---:|:---|
| `order_id` | Integer | ✅ | ID đơn hàng cần hoàn |
| `refund_request_id` | String(64) | ✅ | ID yêu cầu hoàn từ Order Service |
| `required_order_item_ids` | Array[Integer] | ✅ | Danh sách order_item_id cần hoàn đầy đủ |

### Response

**200 OK** (idempotent success)

```json
{
  "order_id": 10001,
  "refund_request_id": "rr-2026-0001",
  "scope_status": "OPEN",
  "required_count": 2,
  "received_count": 0
}
```

| Field | Type | Mô tả |
|:---|:---|:---|
| `order_id` | Integer | Từ request |
| `refund_request_id` | String | Từ request |
| `scope_status` | String | `OPEN` — chờ nhận đủ item |
| `required_count` | Integer | Số item cần hoàn |
| `received_count` | Integer | Số item đã nhận về kho (luôn 0 khi mới tạo) |

**409 Conflict** — `REFUND_SCOPE_MISMATCH`

```json
{
  "error": "REFUND_SCOPE_MISMATCH",
  "message": "Refund scope already exists with different required_order_item_ids",
  "existing_required_order_item_ids": [2001],
  "requested_required_order_item_ids": [2001, 2002]
}
```

| Status | Code | Mô tả |
|:---:|:---|:---|
| `400` | `INVALID_REQUEST` | Validation lỗi |
| `409` | `REFUND_SCOPE_MISMATCH` | Scope đã tồn tại với tập item khác |

### Chi tiết hoạt động nội bộ

```
1. [API Layer] Validate internal service token
2. [Idempotency check]
   SELECT * FROM inventory_refund_scopes
   WHERE order_id = :order_id AND refund_request_id = :refund_request_id
   IF exists:
     IF required_order_item_ids == existing.required_order_item_ids:
       RETURN 200 OK (idempotent hit)
     ELSE:
       RETURN 409 REFUND_SCOPE_MISMATCH
3. [Create scope]
   INSERT INTO inventory_refund_scopes (
     order_id, refund_request_id,
     required_order_item_ids, received_order_item_ids,
     status, created_at, updated_at
   ) VALUES (
     :order_id, :refund_request_id,
     :required_order_item_ids::jsonb, '[]'::jsonb,
     'OPEN', NOW(), NOW()
   );
4. [Response] scope_status = OPEN
```

### Quy tắc nghiệp vụ

| Trường hợp | Hành động |
|:---|:---|
| Lần đầu tạo scope | INSERT row mới, `status = OPEN`, `received_count = 0` |
| Replay cùng `(order_id, refund_request_id, required_order_item_ids)` | Trả 200 OK idempotent |
| Replay khác `required_order_item_ids` cho cùng `(order_id, refund_request_id)` | Trả `409 REFUND_SCOPE_MISMATCH` |

> Scope chỉ được chuyển sang `COMPLETED` khi **tất cả** `required_order_item_ids` đã được nhận về kho qua luồng `SHIPMENT_RETURNED`. Khi đó, hệ thống insert `inventory_outbox` event `INVENTORY_RETURN_RECEIVED_ALL` để Debezium publish lên Kafka, signal cho Order mở bước refund tiền.

---

## Appendix: Temporal Activities (Non-HTTP)

Reserve, Deduct, Release KHÔNG phải HTTP endpoints. Chúng được gọi qua Temporal SDK bởi Order Temporal Saga.

### `ReserveInventoryActivity`

```json
{
  "order_id": 10001,
  "route_plan_id": "7b7dddad-6f6c-4f63-a728-df0fe96b9f31",
  "route_version": 3,
  "items": [
    { "sku_id": "sku-001", "product_id": "prod-uuid-001", "quantity": 2, "warehouse_id": 5 }
  ]
}
```

- Gọi Redis Lua Script 1 (Reserve).
- Event `INVENTORY_RESERVED` được XADD vào Redis Stream -> Relay Worker -> Kafka.
- Ledger Worker ghi `inventory_reservations` + `inventory_ledger`.

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
- Event `INVENTORY_DEDUCTED` được XADD vào Redis Stream -> Relay Worker -> Kafka.
- Ledger Worker ghi `inventory_reservations.status = DEDUCTED` + `inventory_ledger`.

### `RollbackInventoryActivity`

```json
{
  "order_id": 10001,
  "items": [
    { "sku_id": "sku-001", "product_id": "prod-uuid-001", "quantity": 2, "warehouse_id": 5 }
  ],
  "reason": "ORDER_CANCELLED"
}
```

- `reason`: `ORDER_CANCELLED` | `PAYMENT_TIMEOUT` | `RESERVATION_EXPIRED` (từ scanner).
- Gọi Redis Lua Script 2 (Release).
- Event `INVENTORY_RELEASED` được XADD vào Redis Stream -> Relay Worker -> Kafka.
- Ledger Worker ghi `inventory_reservations.status = CANCELLED` + `inventory_ledger`.

### Lua return code mapping

| Code | Temporal Activity result | Hành động |
|:---:|:---|:---|
| `1` | Success | Event đã emit, tiếp tục saga |
| `2` | Idempotent success | Đã apply trước đó, không retry, trả success |
| `0` | `OUT_OF_STOCK` / `INSUFFICIENT_RESERVED` | Saga compensation |
| `-1` | `INVARIANT_BREACH` | Suspend SKU, fail activity, alert ops |
