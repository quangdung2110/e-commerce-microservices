package com.ecommerce.inventory.service;

import com.ecommerce.inventory.common.ApiException;
import com.ecommerce.inventory.common.ErrorCode;
import com.ecommerce.inventory.config.InventoryProperties;
import com.ecommerce.inventory.store.AvailabilityStore;
import com.ecommerce.inventory.web.dto.ReadDtos;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Product Detail BFF batch read (ADR-017, ADR-025). Read-only: it never writes state, never
 * depends on Search readiness and exposes no quantity, warehouse or reservation. A known
 * state is returned while the SKU's oldest pending work is at most 30 seconds old.
 */
@Service
public class AvailabilityReadService {

    private final AvailabilityStore store;
    private final Clock clock;
    private final Duration budget;
    private final MeterRegistry meters;

    public AvailabilityReadService(AvailabilityStore store, Clock clock, InventoryProperties properties,
                                   MeterRegistry meters) {
        this.store = store;
        this.clock = clock;
        this.budget = properties.availability().freshnessBudget();
        this.meters = meters;
    }

    public ReadDtos.BatchSkuAvailabilityResponse read(List<String> skuIds) {
        List<AvailabilityStore.ReadRow> rows;
        try {
            rows = store.readAvailability(skuIds);
        } catch (DataAccessException e) {
            throw new ApiException(ErrorCode.INVENTORY_AVAILABILITY_UNAVAILABLE,
                    "The availability read model is unavailable", List.of(), 5);
        }
        Instant staleBefore = Instant.now(clock).minus(budget);
        List<ReadDtos.SkuAvailabilityResult> items = rows.stream().map(row -> {
            boolean missing = row.inStock() == null;
            boolean stale = row.oldestPendingAt() != null && row.oldestPendingAt().isBefore(staleBefore);
            if (missing || stale) {
                meters.counter("inventory_availability_reads_total", "status", "UNKNOWN").increment();
                return new ReadDtos.SkuAvailabilityResult(row.skuId(), "UNKNOWN", null, null);
            }
            String status = row.inStock() ? "IN_STOCK" : "OUT_OF_STOCK";
            meters.counter("inventory_availability_reads_total", "status", status).increment();
            return new ReadDtos.SkuAvailabilityResult(row.skuId(), status, row.revision(), row.evaluatedAt());
        }).toList();
        return new ReadDtos.BatchSkuAvailabilityResponse(items);
    }
}
