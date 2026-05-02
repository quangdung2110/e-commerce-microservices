# Smart Routing Engine

## 1. Goal

Select the best fulfillment option per sub-cart using inventory availability, shipping fee, SLA, and deterministic tie-break rules.

## 2. Inputs

Required:

- SKU list and quantity.
- Customer destination.
- Candidate warehouses from `physical_inventory.available_quantity`.
- Shipping quotes from Shipping Service.

Versioned snapshot inputs:

- `inventory_snapshot_at`
- `shipping_rate_version`
- `algorithm_version`

## 3. Output Contract

Routing must return a reusable route plan:

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
    }
  ]
}
```

`warehouse_id` phải có cho mọi item; không cho phép `null` trong plan đã publish.

## 4. Candidate Generation

1. Build feasible warehouse combinations where stock is sufficient.
2. For each candidate, request shipping cost and ETA.
3. Discard candidates violating hard constraints:
   - stock not sufficient
   - max split item shipments exceeded
   - blocked lanes
   - provider timeout beyond policy

## 5. Scoring Model

Example weighted score:

`score = w1 * shipping_cost + w2 * delivery_days + w3 * split_penalty + w4 * warehouse_priority`

Lower score is better.

Suggested default weights:

- `w1 = 0.55`
- `w2 = 0.30`
- `w3 = 0.10`
- `w4 = 0.05`

## 6. Tie-Breakers

1. Lower shipping cost.
2. Faster ETA.
3. Fewer split item shipments.
4. Stable warehouse preference order.

## 7. Fast-Lane Fallback

If no full smart route is feasible and policy allows fast fallback:

1. Select warehouse from pre-ranked fast lane pool.
2. Still return concrete `warehouse_id` for each item.
3. Mark `routing_tier = FAST_LANE` in route plan.

## 8. Determinism

Given identical input snapshot, rate cards, and algorithm version, engine must produce identical route.

Checkout submit must pass back `route_plan_id + route_version`. If plan is expired, Inventory must reject with `CONTRACT_INVALID_ROUTE_PLAN` and require re-preview.

## 9. Observability

Track metrics:

- `routing_latency_ms`
- `candidates_generated`
- `fallback_rate`
- `average_shipping_cost`
- `split_item_shipment_ratio`
- `route_plan_expired_reject_total`