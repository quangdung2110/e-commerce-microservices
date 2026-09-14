package com.platform.promotionservice.config;

import com.platform.promotionservice.worker.PromotionWorkers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Startup probe: readiness chỉ UP sau khi campaign ZSET và runtime claim fields/pool
 * đã rebuild (docs/promotion/operations.md §2). Khi workers bị tắt (test) coi là ready.
 */
@Component("promotionRuntime")
public class RuntimeReadinessHealthIndicator implements HealthIndicator {

    private final ObjectProvider<PromotionWorkers> workers;

    public RuntimeReadinessHealthIndicator(ObjectProvider<PromotionWorkers> workers) {
        this.workers = workers;
    }

    @Override
    public Health health() {
        PromotionWorkers bean = workers.getIfAvailable();
        if (bean == null || bean.isReady()) {
            return Health.up().withDetail("runtime", "rebuilt").build();
        }
        return Health.outOfService().withDetail("runtime", "rebuilding").build();
    }
}
