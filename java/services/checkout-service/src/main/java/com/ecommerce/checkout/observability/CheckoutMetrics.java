package com.ecommerce.checkout.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Metrics only Checkout can know (operations.md, "Metric"); the mesh already provides the golden
 * metrics per edge. Labels are bounded enums: a token, user ID, SKU or promotion ID is never a label.
 */
@Component
public class CheckoutMetrics {

    private final MeterRegistry registry;
    private final DistributionSummary cartLines;

    public CheckoutMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.cartLines = DistributionSummary.builder("checkout.cart.lines")
                .description("Number of cart lines per Preview")
                .publishPercentileHistogram(false)
                .register(registry);
    }

    /** One per problem returned, by the error catalog. */
    public void problem(String operation, String code) {
        count("checkout.problems", "operation", operation, "code", code);
    }

    /** {@code created}, {@code superseded_previous}, {@code active_changed}, {@code in_flight_blocked}, ... */
    public void previewCas(String result) {
        count("checkout.preview.cas", "result", result);
    }

    public void transition(String from, String to) {
        count("checkout.state.transitions", "from", from, "to", to);
    }

    /** {@code resolved_success}, {@code resolved_conflict}, {@code resolved_failed}, {@code still_preparing}, ... */
    public void recovery(String outcome) {
        count("checkout.recovery", "outcome", outcome);
    }

    public void voucherOptionDropped(String reason) {
        count("checkout.voucher.options.dropped", "reason", reason);
    }

    public void budgetGuard(String value) {
        count("checkout.promotion.budget.guard", "value", value);
    }

    public void cartLines(int lines) {
        cartLines.record(lines);
    }

    private void count(String name, String... tags) {
        Counter.builder(name).tags(tags).register(registry).increment();
    }
}
