package com.platform.inventoryservice.routing;

import com.platform.inventoryservice.domain.vo.AvailableStock;
import com.platform.inventoryservice.port.outbound.database.PhysicalInventoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class WarehouseCandidateGenerator {

    private final PhysicalInventoryRepository physicalRepo;

    public Map<String, List<AvailableStock>> generate(List<String> skuIds) {
        if (skuIds.isEmpty()) return Map.of();
        List<AvailableStock> rows = physicalRepo.findAvailableBySkuIds(skuIds);

        Map<String, List<AvailableStock>> result = new HashMap<>();
        for (AvailableStock row : rows) {
            result.computeIfAbsent(row.skuId(), k -> new ArrayList<>()).add(row);
        }
        return result;
    }
}
