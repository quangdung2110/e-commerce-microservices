package com.ecommerce.promotion.infrastructure.worker;

import com.ecommerce.promotion.application.service.MaintenanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Cache Warmer — Startup Job.
 * Theo thiết kế redis_caching_design.md - Section 2.2: Startup Job.
 * Theo thiết kế promotion_workers.md - Section 2.1.
 *
 * Khi Service khởi động thành công → nạp tất cả active promotion budgets lên Redis.
 * HSETNX logic: chỉ nạp nếu key chưa tồn tại (tránh ghi đè counter đang hoạt động).
 */
@Component
public class CacheWarmerWorker {

    private static final Logger log = LoggerFactory.getLogger(CacheWarmerWorker.class);

    private final MaintenanceService maintenanceService;

    public CacheWarmerWorker(MaintenanceService maintenanceService) {
        this.maintenanceService = maintenanceService;
    }

    /**
     * @EventListener(ApplicationReadyEvent) — chạy SAU KHI Spring Boot đã khởi động đầy đủ.
     * Không dùng @PostConstruct vì DB/Redis có thể chưa ready.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmUpOnStartup() {
        log.info("=== Cache Warmer: Starting budget warm-up ===");
        try {
            maintenanceService.warmUpAllBudgets();
            log.info("=== Cache Warmer: Completed budget warm-up ===");
        } catch (Exception e) {
            // Log lỗi nhưng KHÔNG crash service — Redis có thể tạm thời unavailable
            log.error("Cache warm-up failed (service continues): {}", e.getMessage(), e);
        }
    }
}
