package com.platform.inventoryservice.routing;

import com.platform.inventoryservice.domain.exception.InsufficientStockException;
import com.platform.inventoryservice.domain.vo.AvailableStock;
import com.platform.inventoryservice.domain.vo.CustomerLocation;
import com.platform.inventoryservice.domain.vo.RoutePlan.RoutePlanItem;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class FeasibleCombinationBuilder {

    public List<RoutePlanProto> buildAll(
        List<RoutingItem> items,
        Map<String, List<AvailableStock>> candidates,
        CustomerLocation location
    ) {
        Set<Long> commonWarehouseIds = null;
        for (RoutingItem item : items) {
            Set<Long> whForSku = candidates.getOrDefault(item.skuId(), List.of()).stream()
                .filter(c -> c.quantity() >= item.quantity())
                .map(AvailableStock::warehouseId)
                .collect(Collectors.toSet());
            
            if (whForSku.isEmpty()) {
                throw new InsufficientStockException(item.skuId());
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

        List<RoutePlanProto> plans = new ArrayList<>();
        for (Long whId : commonWarehouseIds.stream().sorted().toList()) {
            List<RoutePlanItem> planItems = items.stream().map(item -> {
                AvailableStock stock = candidates.get(item.skuId()).stream()
                    .filter(c -> c.warehouseId() == whId)
                    .findFirst().orElseThrow();
                return new RoutePlanItem(item.skuId(), stock.productId(), item.quantity(), whId);
            }).toList();

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
