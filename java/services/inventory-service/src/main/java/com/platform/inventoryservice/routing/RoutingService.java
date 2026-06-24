package com.platform.inventoryservice.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.inventoryservice.config.InventoryProperties;
import com.platform.inventoryservice.domain.exception.InsufficientStockException;
import com.platform.inventoryservice.domain.vo.AvailableStock;
import com.platform.inventoryservice.domain.vo.CustomerLocation;
import com.platform.inventoryservice.domain.vo.RoutePlan;
import com.platform.inventoryservice.port.inbound.RoutingPort;
import com.platform.inventoryservice.port.outbound.cache.RoutePlanCache;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RoutingService implements RoutingPort {

    private final WarehouseCandidateGenerator candidateGenerator;
    private final FeasibleCombinationBuilder combinationBuilder;
    private final WarehouseSelector selector;
    private final RoutePlanBuilder builder;
    private final RoutePlanCache cache;
    private final InventoryProperties props;
    private final ObjectMapper objectMapper;

    @Override
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
            if (!candidates.containsKey(item.skuId()) || candidates.get(item.skuId()).isEmpty()) {
                throw new InsufficientStockException(item.skuId());
            }
        }

        // 3. Build feasible combinations
        List<RoutePlanProto> plans = combinationBuilder.buildAll(consolidatedItems, candidates, location);

        // 4. Pick best plan
        RoutePlanProto best = selector.pickBest(plans, location);

        // 5. Build final RoutePlan
        int ttlMinutes = props.getRouting().getPlanTtlMinutes();
        RoutePlan finalPlan = builder.build(best, location, ttlMinutes);

        // 6. Cache
        cache.save(finalPlan.routePlanId(), serialize(finalPlan), Duration.ofMinutes(ttlMinutes));

        return finalPlan;
    }

    private String serialize(RoutePlan plan) {
        try {
            return objectMapper.writeValueAsString(plan);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize route plan", e);
        }
    }
}
