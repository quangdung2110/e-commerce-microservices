package com.ecommerce.pdp.compose;

import com.ecommerce.pdp.resilience.Outcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Metric ngữ nghĩa mà chỉ BFF biết (operations.md §Observability). Không dùng SKU ID,
 * product ID hay slug làm label.
 */
@Component
public class PdpMetrics {

    /** Vì sao một SKU là {@code UNKNOWN}. */
    public enum UnknownSource {
        INVENTORY_RETURNED("inventory_returned"),
        INVENTORY_MISSING_ITEM("inventory_missing_item"),
        CALL_FAILED("call_failed"),
        SKIPPED("skipped"),
        DISABLED("disabled");

        private final String tag;

        UnknownSource(String tag) {
            this.tag = tag;
        }
    }

    private final MeterRegistry registry;

    public PdpMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void unknownSkus(UnknownSource source, int count) {
        if (count > 0) {
            Counter.builder("pdp.sku.unknown").tag("source", source.tag).register(registry).increment(count);
        }
    }

    /** Nguồn của {@code UNKNOWN}/{@code UNAVAILABLE} khi cả lời gọi không cho kết quả. */
    public static String callSource(Outcome outcome) {
        return switch (outcome) {
            case DISABLED -> "disabled";
            case CIRCUIT_OPEN, BULKHEAD_REJECTED, BUDGET_SKIPPED -> "skipped";
            default -> "call_failed";
        };
    }

    public static UnknownSource unknownSource(Outcome outcome) {
        return switch (outcome) {
            case DISABLED -> UnknownSource.DISABLED;
            case CIRCUIT_OPEN, BULKHEAD_REJECTED, BUDGET_SKIPPED -> UnknownSource.SKIPPED;
            default -> UnknownSource.CALL_FAILED;
        };
    }

    public void reviewUnavailable(Outcome outcome) {
        Counter.builder("pdp.review.unavailable").tag("source", callSource(outcome)).register(registry).increment();
    }

    /**
     * Inventory vi phạm cam kết một item mỗi SKU nhưng batch vẫn dùng được (ADR-007): alert
     * mà không bỏ kết quả của các SKU khác.
     *
     * @param kind {@code missing} hoặc {@code extra}
     */
    public void inventoryItemViolations(String kind, int count) {
        if (count > 0) {
            Counter.builder("pdp.inventory.item.violations").tag("kind", kind).register(registry).increment(count);
        }
    }
}
