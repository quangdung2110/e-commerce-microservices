package com.platform.inventoryservice.routing;

import com.platform.inventoryservice.domain.vo.CustomerLocation;
import com.platform.inventoryservice.domain.vo.RoutePlan;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class RoutePlanBuilder {

    public RoutePlan build(RoutePlanProto proto, CustomerLocation location, int ttlMinutes) {
        UUID routePlanId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(Duration.ofMinutes(ttlMinutes));

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
