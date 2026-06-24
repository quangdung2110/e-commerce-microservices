package com.platform.inventoryservice.routing;

import com.platform.inventoryservice.domain.entity.Warehouse;
import com.platform.inventoryservice.domain.vo.CustomerLocation;
import com.platform.inventoryservice.port.outbound.database.WarehouseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class WarehouseSelector {

    private final WarehouseRepository warehouseRepo;

    public RoutePlanProto pickBest(List<RoutePlanProto> plans, CustomerLocation location) {
        Set<Long> whIds = plans.stream().map(RoutePlanProto::warehouseId).collect(Collectors.toSet());
        
        // Use HashMap to safely handle null cityCode values
        Map<Long, String> cityCodes = new HashMap<>();
        for (Warehouse wh : warehouseRepo.findByIds(whIds)) {
            cityCodes.put(wh.getId(), wh.getCityCode());
        }

        String targetCityCode = (location != null) ? location.cityCode() : null;

        return plans.stream()
            .min(Comparator
                .comparing((RoutePlanProto p) -> {
                    String whCityCode = cityCodes.get(p.warehouseId());
                    boolean isSameProvince = targetCityCode != null 
                            && whCityCode != null 
                            && targetCityCode.equals(whCityCode);
                    return isSameProvince ? 0 : 1;
                })
                .thenComparingLong(RoutePlanProto::warehouseId))
            .orElseThrow(() -> new IllegalStateException("No feasible plans"));
    }
}
