package com.ecommerce.inventory.domain;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Release 1 allocation (domain.md, "Allocation policy"): for each line, an active,
 * non-outage warehouse with {@code available_to_sell >= quantity}, smallest
 * {@code (fulfillment_priority, warehouse_id)}. One line never spans warehouses; there is no
 * locality, cost or consolidation heuristic.
 */
public final class AllocationPolicy {

    private AllocationPolicy() {
    }

    public record Candidate(String warehouseId, int fulfillmentPriority, boolean active, boolean outage,
                            long availableToSell) {
    }

    public record Line(String reference, String skuId, long quantity) {
    }

    public record Choice(Line line, Candidate warehouse) {
    }

    /**
     * Plans lines in request order. Quantity chosen for an earlier line of the same SKU is
     * deducted from a warehouse before later lines are considered, so the plan never
     * promises the same unit twice. An empty choice means the line has no eligible warehouse.
     */
    public static List<Optional<Choice>> plan(List<Line> lines, Map<String, List<Candidate>> candidatesBySku) {
        Map<String, Long> planned = new HashMap<>();
        return lines.stream().map(line -> {
            Optional<Candidate> chosen = candidatesBySku.getOrDefault(line.skuId(), List.of()).stream()
                    .filter(c -> c.active() && !c.outage())
                    .filter(c -> c.availableToSell() - planned.getOrDefault(key(c, line), 0L) >= line.quantity())
                    .min(Comparator.comparingInt(Candidate::fulfillmentPriority)
                            .thenComparing(Candidate::warehouseId));
            chosen.ifPresent(c -> planned.merge(key(c, line), line.quantity(), Long::sum));
            return chosen.map(c -> new Choice(line, c));
        }).toList();
    }

    private static String key(Candidate candidate, Line line) {
        return candidate.warehouseId() + '\u0000' + line.skuId();
    }
}
