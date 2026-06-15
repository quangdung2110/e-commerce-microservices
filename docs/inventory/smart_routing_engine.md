# Smart Routing Engine

**Liên kết nhanh (Related Documents):**
* [Workflow §4: Routing Options](inventory_workflow.md)
* [Endpoints §1: routing-options](inventory_endpoints.md)

---

## 1. Goal

Pick the best warehouse for each item in the cart, balancing shipping cost and delivery speed.

## 2. Inputs

Required:
- SKU list and quantity.
- Customer destination (city, district, lat/lng).
- Candidate warehouses from `physical_inventory.available_quantity`.


## 3. Output Contract

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
    }
  ],
  "shipping_options": [
    {
      "warehouse_id": 5,
      "options": [
        { "provider": "GHN", "service_type": "STANDARD", "fee": 35000, "delivery_days": 2 },
        { "provider": "GHTK", "service_type": "EXPRESS", "fee": 45000, "delivery_days": 1 }
      ]
    }
  ]
}
```

`warehouse_id` phải có cho mọi item; không cho phép `null` trong plan đã publish. `product_id` được denormalized từ `physical_inventory.product_id` (lưu từ lần restock đầu) để `ReserveInventoryActivity` không cần lookup lại.

> **Đơn giản hóa:** Schema đã bỏ `route_version` (chỉ có 1 algorithm, không cần version), `route_signature` (thừa). Shipping trả về `rate_card_version` được relay trực tiếp cho Checkout dùng trong `pricing_hash`. Schema đầy đủ và tường minh xem [Inventory Endpoints §1 Response](inventory_endpoints.md#response).

## 4. Algorithm

### 4.1. Candidate Generation

```
SELECT sku_id, warehouse_id, available_quantity
FROM physical_inventory
WHERE sku_id = ANY(:requested_skus)
  AND available_quantity > 0
```

Với index `UNIQUE (sku_id, warehouse_id)` đã có, query này chạy < 5ms cho vài chục SKU.

### 4.2. Build Feasible Combinations

Với mỗi SKU, danh sách warehouse có đủ `available_quantity >= requested_quantity`. Tổ hợp warehouse hợp lệ = chọn 1 warehouse cho mỗi SKU sao cho tất cả SKU đều thoả mãn.

Với cart nhỏ (< 10 SKU) và mỗi SKU có 1-3 warehouse candidates, số tổ hợp thường < 50.

### 4.3. Score Each Combination

Với mỗi tổ hợp, gọi `POST /v1/shipping/calculate-batch` để lấy shipping fee + delivery_days cho mỗi `warehouse_id` trong combination.

**Chọn option trong quote:**
- Ưu tiên option có `service_type` khớp với `service_type` trong request input.
- Nếu không có option khớp, lấy option rẻ nhất.
- Nếu options rỗng → đánh dấu combination này INFEASIBLE.

**Scoring formula:**

```
score = shipping_cost + delivery_days * cost_per_day
```

- `shipping_cost`: fee của option đã chọn.
- `cost_per_day` mặc định = `5000` (VND). Có thể config theo business policy.

### 4.4. Pick Best

Chọn tổ hợp có `score` thấp nhất. Tie-break: `warehouse_id ASC` (deterministic, ổn định qua các lần re-preview cùng input).

> Đã lược bỏ weighted scoring 4 trọng số (`w1*cost + w2*eta + w3*split + w4*warehouse_priority`). Lý do: `split_penalty` chỉ áp dụng khi đơn phải chia nhiều kiện — thực tế cart < 10 SKU hiếm khi split quá 1-2 kho. `warehouse_priority` là heuristic không có data thực tế để config tốt. Linear score đơn giản đủ cho hầu hết use case.

## 5. Cache

- Key: `route_plan:{route_plan_id}`
- Value: JSON response object
- TTL: `expires_at - now` (mặc định 20 phút)
- Implementation: `SET route_plan:{id} <json> EX <ttl_seconds>`

Khi Checkout submit với `route_plan_id`:
- Lookup `route_plan:{id}` trong cache.
- Nếu hit: trả plan (sử dụng ngay).
- Nếu miss (TTL expired): reject `CONTRACT_INVALID_ROUTE_PLAN`, Checkout phải gọi lại `/routing-options`.


## 6. Plan Expiry Handling

| Trường hợp | Hành vi |
|:---|:---|
| Plan trong cache, TTL chưa hết | Proceed bình thường |
| Plan không có trong cache (TTL expired hoặc bị xoá) | Reject `CONTRACT_INVALID_ROUTE_PLAN`. Client phải gọi lại `/routing-options` |
| Redis cache unavailable | Reject `INVENTORY_UNAVAILABLE`. Không fallback về logic khác (đơn giản hoá) |
| Shipping trả `options[]` rỗng cho ít nhất 1 warehouse trong feasible combinations | Reject `NO_SHIPPING_OPTIONS_FOR_ROUTE` (HTTP 422). Client phải đổi địa chỉ hoặc liên hệ CSKH |

> Đã lược bỏ deterministic replay check (kiểm tra `algorithm_version` cũ → chạy lại cùng algorithm). Lý do: giờ chỉ có 1 algorithm, không có version drift.
