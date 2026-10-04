package com.ecommerce.inventory.domain;

/**
 * The five buckets of one {@code StockPosition}. Every instance satisfies
 * {@code available_to_sell + reserved + committed + quarantine = on_hand} with all buckets
 * non-negative (domain.md, "Stock bucket model").
 */
public record Buckets(long onHand, long availableToSell, long reserved, long committed, long quarantine) {

    public static final Buckets EMPTY = new Buckets(0, 0, 0, 0, 0);

    public Buckets {
        if (onHand < 0 || availableToSell < 0 || reserved < 0 || committed < 0 || quarantine < 0) {
            throw new InvariantViolation("negative bucket");
        }
        if (availableToSell + reserved + committed + quarantine != onHand) {
            throw new InvariantViolation("bucket equation does not hold");
        }
    }

    /** Applies a balanced delta; a result that would break the invariant is refused. */
    public Buckets apply(BucketDelta delta) {
        return new Buckets(
                onHand + delta.onHand(),
                availableToSell + delta.availableToSell(),
                reserved + delta.reserved(),
                committed + delta.committed(),
                quarantine + delta.quarantine());
    }

    /** Whether {@link #apply} would succeed. */
    public boolean permits(BucketDelta delta) {
        return onHand + delta.onHand() >= 0
                && availableToSell + delta.availableToSell() >= 0
                && reserved + delta.reserved() >= 0
                && committed + delta.committed() >= 0
                && quarantine + delta.quarantine() >= 0;
    }

    public static final class InvariantViolation extends IllegalStateException {
        public InvariantViolation(String message) {
            super(message);
        }
    }
}
