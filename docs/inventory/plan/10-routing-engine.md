# Module 10: Warehouse Routing Engine

> **Depends on:** M04 (Domain), M05 (Repository), M06 (Redis Cache)
> **Depended by:** M13 (HTTP API)

---

## 1. Goal

Triển khai warehouse routing engine phục vụ endpoint `POST /v1/inventory/routing-options`:

- **Candidate generation**: query `physical_inventory` cho mỗi SKU → danh sách warehouse có đủ stock.
- **Feasible combinations**: chọn 1 warehouse cho mỗi SKU → feasible plan.
- **Warehouse selection**: ưu tiên same-province, tie-break `warehouse_id ASC`.
- **Route plan builder**: sinh `RoutePlan` record, cache trong Redis với TTL 20 phút.

> Tham chiếu: `smart_routing_engine.md` §4; `inventory_endpoints.md` §1.

---

## 2. Package Layout

```
com.platform.inventoryservice.routing/
├── RoutingService.java                 # Entry point (implements port.inbound.RoutingPort)
├── WarehouseCandidateGenerator.java    # Step 1
├── FeasibleCombinationBuilder.java     # Step 2
├── WarehouseSelector.java              # Step 3
├── RoutePlanBuilder.java               # Step 4
└── RoutingItem.java (record)
```

---

## 3. Routing Flow

```
POST /v1/inventory/routing-options
        │
        ▼
[RoutingService.route(customerLocation, items)]
        │
        ├──> [WarehouseCandidateGenerator] → Map<skuId, List<AvailableStock>>
        │
        ├──> [FeasibleCombinationBuilder] → List<RoutePlan>
        │                                              (với mỗi plan: 1 SKU = 1 warehouse)
        │
        ├──> [WarehouseSelector] → RoutePlan picked
        │
        ├──> [RoutePlanBuilder] → RoutePlan record
        │
        └──> [RoutePlanCache.save] → Redis SET TTL 20min
```

---

## 4. RoutingService

```java
package com.platform.inventoryservice.routing;

@Service
@RequiredArgsConstructor
public class RoutingService {

    private final WarehouseCandidateGenerator candidateGenerator;
    private final FeasibleCombinationBuilder combinationBuilder;
    private final WarehouseSelector selector;
    private final RoutePlanBuilder builder;
    private final RoutePlanCache cache;
    private final InventoryProperties props;

    @Transactional(readOnly = true)
    public RoutePlan route(CustomerLocation location, List<RoutingItem> items) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }

        // 0. Pre-process items: Validate quantity > 0 and merge duplicate SKUs
        Map<String, Integer> mergedItemsMap = items.stream()
            .peek(item -> {
                if (item.quantity() <= 0) {
                    throw new IllegalArgumentException("Item quantity must be > 0: " + item.skuId());
                }
            })
            .collect(Collectors.groupingBy(
                RoutingItem::skuId,
                Collectors.summingInt(RoutingItem::quantity)
            ));
            
        List<RoutingItem> consolidatedItems = mergedItemsMap.entrySet().stream()
            .map(e -> new RoutingItem(e.getKey(), e.getValue()))
            .toList();

        // 1. Candidate generation
        List<String> skuIds = consolidatedItems.stream().map(RoutingItem::skuId).toList();
        Map<String, List<AvailableStock>> candidates = candidateGenerator.generate(skuIds);

        // 2. Validate all SKUs have at least 1 candidate
        for (RoutingItem item : consolidatedItems) {
            if (!candidates.containsKey(item.skuId())
                || candidates.get(item.skuId()).isEmpty()) {
                throw new InsufficientStockException(item.skuId());
            }
        }

        // 3. Build feasible combinations
        List<RoutePlanProto> plans = combinationBuilder.buildAll(consolidatedItems, candidates, location);

        // 4. Pick best plan
        RoutePlanProto best = selector.pickBest(plans, location);

        // 5. Build final RoutePlan (assign routePlanId, expiresAt)
        RoutePlan finalPlan = builder.build(best, location);

        // 6. Cache
        cache.save(finalPlan.routePlanId(), serialize(finalPlan),
                   Duration.ofMinutes(props.getRouting().getPlanTtlMinutes()));

        return finalPlan;
    }

    private String serialize(RoutePlan plan) {
        // Jackson serialize RoutePlan → JSON
        return EventSerializer.toJson(plan);
    }
}
```

> Internal DTO `RoutePlanProto` là intermediate trước khi gán UUID + expiresAt. Khác với domain `RoutePlan` (record final).

---

## 5. WarehouseCandidateGenerator

```java
@Component
@RequiredArgsConstructor
public class WarehouseCandidateGenerator {

    private final PhysicalInventoryRepository physicalRepo;

    /**
     * Trả về Map<skuId, List<AvailableStock>> chỉ chứa warehouse có available > 0.
     */
    public Map<String, List<AvailableStock>> generate(List<String> skuIds) {
        if (skuIds.isEmpty()) return Map.of();
        List<AvailableStock> rows = physicalRepo.findAvailableBySkuIds(skuIds);

        // Group by sku_id
        Map<String, List<AvailableStock>> result = new HashMap<>();
        for (AvailableStock row : rows) {
            result.computeIfAbsent(row.skuId(), k -> new ArrayList<>()).add(row);
        }
        return result;
    }
}
```

> Query dùng partial index `idx_physical_inventory_sku_available WHERE available_quantity > 0` (M02) — < 5ms cho vài chục SKU.

---

## 6. FeasibleCombinationBuilder

> Phase 14: Multi-warehouse fulfillment **KHÔNG** được phép (mỗi order chỉ pick 1 warehouse duy nhất). Vì vậy chỉ cần tìm 1 warehouse chứa đủ stock cho MỌI SKU.

```java
@Component
public class FeasibleCombinationBuilder {

    /**
     * Tìm warehouse có stock đủ cho MỌI SKU.
     * Trả về danh sách RoutePlanProto (thường chỉ 1 plan trừ khi có nhiều kho qualify).
     */
    public List<RoutePlanProto> buildAll(
        List<RoutingItem> items,
        Map<String, List<AvailableStock>> candidates,
        CustomerLocation location
    ) {
        // Tập warehouse xuất hiện ở tất cả SKU
        Set<Long> commonWarehouseIds = null;
        for (RoutingItem item : items) {
            Set<Long> whForSku = candidates.get(item.skuId()).stream()
                .filter(c -> c.quantity() >= item.quantity())
                .map(AvailableStock::warehouseId)
                .collect(Collectors.toSet());
            if (whForSku.isEmpty()) {
                throw new InsufficientStockException(item.skuId());   // → HTTP 409
            }
            if (commonWarehouseIds == null) {
                commonWarehouseIds = new HashSet<>(whForSku);
            } else {
                commonWarehouseIds.retainAll(whForSku);
            }
        }

        if (commonWarehouseIds == null || commonWarehouseIds.isEmpty()) {
            throw new InsufficientStockException(items.iterator().next().skuId());
        }

        // Mỗi warehouse qualify → 1 plan
        List<RoutePlanProto> plans = new ArrayList<>();
        for (Long whId : commonWarehouseIds.stream().sorted().toList()) {
            List<RoutePlanItem> planItems = items.stream().map(item -> {
                AvailableStock stock = candidates.get(item.skuId()).stream()
                    .filter(c -> c.warehouseId() == whId)
                    .findFirst().orElseThrow();
                return new RoutePlanItem(item.skuId(), stock.productId(), item.quantity(), whId);
            }).toList();

            // Aggregate available_quantity = min across SKUs (cùng warehouse)
            long totalAvailable = items.stream()
                .mapToLong(item -> candidates.get(item.skuId()).stream()
                    .filter(c -> c.warehouseId() == whId)
                    .findFirst().map(AvailableStock::quantity).orElse(0L))
                .min().orElse(0L);

            plans.add(new RoutePlanProto(whId, totalAvailable, planItems));
        }
        return plans;
    }
}
```

> Phase 14 single-warehouse constraint: nếu không có warehouse nào chứa đủ tất cả SKU → 409 INSUFFICIENT_STOCK.

---

## 7. WarehouseSelector

```java
@Component
@RequiredArgsConstructor
public class WarehouseSelector {

    private final WarehouseRepository warehouseRepo;

    /**
     * Pick plan tốt nhất:
     * 1. Same-province priority
     * 2. Tie-break: warehouse_id ASC (deterministic)
     */
    public RoutePlanProto pickBest(List<RoutePlanProto> plans, CustomerLocation location) {
        // Lookup warehouses để biết city_code
        Set<Long> whIds = plans.stream().map(p -> p.warehouseId()).collect(Collectors.toSet());
        
        // Use HashMap and loop to safely handle null cityCode values (avoid NPE)
        Map<Long, String> cityCodes = new HashMap<>();
        for (Warehouse wh : warehouseRepo.findByIds(whIds)) {
            cityCodes.put(wh.getId(), wh.getCityCode());
        }

        String targetCityCode = (location != null) ? location.cityCode() : null;

        return plans.stream()
            .min(Comparator
                .comparing((RoutePlanProto p) -> {
                    String whCityCode = cityCodes.get(p.warehouseId());
                    // Only match same-province if both are non-null and equal
                    boolean isSameProvince = targetCityCode != null 
                            && whCityCode != null 
                            && targetCityCode.equals(whCityCode);
                    return isSameProvince ? 0 : 1;
                })
                .thenComparingLong(RoutePlanProto::warehouseId))   // tie-break
            .orElseThrow(() -> new IllegalStateException("No feasible plans"));
    }
}
```

> Lưu ý: `WarehouseRepository.findByIds(Set<Long>)` cần được bổ sung vào M05 port interface.

---

## 8. RoutePlanBuilder

```java
@Component
public class RoutePlanBuilder {

    public RoutePlan build(RoutePlanProto proto, CustomerLocation location) {
        UUID routePlanId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(Duration.ofMinutes(20));   // TTL hardcode, override by config

        return new RoutePlan(
            routePlanId,
            expiresAt,
            proto.items(),
            List.of(new RoutePlan.WarehouseAssignment(
                proto.warehouseId(),
                proto.totalAvailable()
            ))
        );
    }
}
```

---

## 9. Internal DTOs

```java
public record RoutingItem(String skuId, int quantity) {}

record RoutePlanProto(
    long warehouseId,
    long totalAvailable,
    List<RoutePlan.RoutePlanItem> items
) {}
```

---

## 10. RoutePlanCache (M06 đã triển khai)

```java
public interface RoutePlanCache {
    void save(UUID routePlanId, String planJson, Duration ttl);
    Optional<String> find(UUID routePlanId);
    boolean exists(UUID routePlanId);
}
```

> `RoutePlanCacheImpl` (M06) implements bằng `route_plan:{id}` key + `SET ... EX ttl`.

---

## 11. Algorithm Correctness Notes

| Scenario | Hành vi |
|:---|:---|
| Cart 1 SKU, nhiều kho qualify | Pick same-province → tie-break `warehouse_id ASC` |
| Cart N SKU, không có kho chứa đủ tất cả | 409 `INSUFFICIENT_STOCK` |
| Cart N SKU, 1 kho chứa đủ tất cả | Pick warehouse đó |
| Cart N SKU, nhiều kho qualify | Pick same-province → tie-break |
| All warehouses inactive | 409 `INSUFFICIENT_STOCK` |
| `customer_location.city_code` = NULL | Không match same-province rule → tie-break fallback |
| Duplicate SKUs in request | Gom nhóm và tính tổng `quantity` trước khi build feasible plan |
| Invalid quantity (<= 0) | Throw `IllegalArgumentException` → HTTP 400 |
| SKU không tồn tại trong kho nào | Ném `InsufficientStockException` (HTTP 409) thay vì SkuNotFound |
| Kho có `city_code = null` | Tránh NPE bằng cách dùng `HashMap.put()`, không được match same-province |

---

## 12. Performance

| Operation | Latency target |
|:---|:---|
| Candidate generation query | < 5ms cho 50 SKU |
| Combination building | < 1ms |
| Warehouse lookup | < 2ms |
| RoutePlan build + cache write | < 2ms |
| **Total endpoint latency** | **< 20ms** (p95) |

---

## 13. Testing

| Test | Mục đích |
|:---|:---|
| Single SKU, single warehouse | Basic happy path |
| Single SKU, multi-warehouse, same-province | Pick same-province |
| Single SKU, multi-warehouse, no same-province | Tie-break warehouse_id ASC |
| Multi SKU, single warehouse qualifying | OK |
| Multi SKU, no warehouse qualifying | 409 INSUFFICIENT_STOCK |
| Multi SKU, multiple warehouses qualifying | Pick same-province |
| Cache hit/miss | TTL expiry, miss reject |
| Determinism | Same input → same output (cùng plan qua các lần preview) |

---

## 14. Verification

| Check | Cách verify |
|:---|:---|
| Endpoint returns route_plan_id | curl `/v1/inventory/routing-options` |
| Plan cached | `EXISTS route_plan:{id}` returns 1 |
| Plan TTL | `TTL route_plan:{id}` ≈ 1200s |
| Deterministic | Gọi 2 lần cùng input → cùng warehouse |
| Insufficient stock | Cart với SKU qty > available → 409 |
| Multi-SKU all-or-nothing | 1 SKU thiếu stock → toàn bộ 409 |

---

## 15. Files Created

```
src/main/java/com/platform/inventoryservice/routing/
├── RoutingService.java
├── WarehouseCandidateGenerator.java
├── FeasibleCombinationBuilder.java
├── WarehouseSelector.java
├── RoutePlanBuilder.java
└── RoutingItem.java (record)
```