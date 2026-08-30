# Warehouse Selection Engine (Phase 14 — đổi tên từ Smart Routing Engine)

**Liên kết nhanh (Related Documents):**
* [Workflow §4: Routing Options](inventory_workflow.md)
* [Endpoints §1: routing-options](inventory_endpoints.md)

---

## 1. Goal

Pick a warehouse that has the required stock for each item in the cart. Since Phase 14 đã loại bỏ Shipping Service, scoring theo `shipping_cost + delivery_days` không còn ý nghĩa. Thuật toán mới:

1. Tìm warehouse có `physical_inventory.available_quantity >= requested_quantity`.
2. Nếu nhiều kho qualify → ưu tiên kho **same-province** với customer_location.
3. Tie-break: `warehouse_id ASC` (deterministic).

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
  "items": [
    {
      "sku_id": "sku-001",
      "product_id": "prod-uuid-001",
      "quantity": 2,
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

`warehouse_id` phải có cho mọi item; không cho phép `null` trong plan đã publish. `product_id` được denormalized từ `physical_inventory.product_id` (lưu từ lần restock đầu) để `ReserveInventoryActivity` không cần lookup lại.


## 4. Algorithm

### 4.1. Candidate Generation

```
SELECT sku_id, product_id, warehouse_id, available_quantity
FROM physical_inventory
WHERE sku_id = ANY(:requested_skus)
  AND available_quantity > 0
```

Với index `UNIQUE (sku_id, warehouse_id)` đã có, query này chạy < 5ms cho vài chục SKU. Cột `product_id` cần select để denormalize vào response `route_plan.items[]` (giúp `ReserveInventoryActivity` không cần lookup thêm).

### 4.2. Build Feasible Combinations

Với mỗi SKU, danh sách warehouse có đủ `available_quantity >= requested_quantity`. Tổ hợp warehouse hợp lệ = chọn 1 warehouse cho mỗi SKU sao cho tất cả SKU đều thoả mãn.

Với cart nhỏ (< 10 SKU) và mỗi SKU có 1-3 warehouse candidates, số tổ hợp thường < 50.

### 4.3. Warehouse Selection

Với mỗi tổ hợp feasible, áp dụng rule ưu tiên:

1. **Same-province preferred**: Ưu tiên warehouse có `warehouses.province_code = customer_location.province_code`.
2. **Stock availability**: Tất cả SKU phải đủ stock tại warehouse đó.
3. **Tie-break**: `warehouse_id ASC` (deterministic, ổn định qua các lần re-preview cùng input).

### 4.4. Pick Best

Chọn tổ hợp có warehouse ưu tiên (same-province) trước, nếu không có thì lấy warehouse_id nhỏ nhất.

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
| Không có kho nào có đủ stock cho 1 SKU | Reject `INSUFFICIENT_STOCK` (HTTP 409). Client phải giảm số lượng hoặc đợi restock |
