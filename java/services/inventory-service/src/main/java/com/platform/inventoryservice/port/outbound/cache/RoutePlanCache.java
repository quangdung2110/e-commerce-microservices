package com.platform.inventoryservice.port.outbound.cache;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public interface RoutePlanCache {
    void save(UUID routePlanId, String planJson, Duration ttl);
    Optional<String> find(UUID routePlanId);
    boolean exists(UUID routePlanId);
}
