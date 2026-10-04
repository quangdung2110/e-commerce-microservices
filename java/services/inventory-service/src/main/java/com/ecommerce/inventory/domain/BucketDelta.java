package com.ecommerce.inventory.domain;

/**
 * A balanced bucket transition: the bucket deltas always sum to the {@code on_hand} delta.
 * The factories are exactly the rows of the domain.md transition table.
 */
public record BucketDelta(long onHand, long availableToSell, long reserved, long committed, long quarantine) {

    public BucketDelta {
        if (availableToSell + reserved + committed + quarantine != onHand) {
            throw new IllegalArgumentException("unbalanced bucket delta");
        }
    }

    /** Sellable receipt or positive adjustment. */
    public static BucketDelta sellableIn(long q) {
        return new BucketDelta(q, q, 0, 0, 0);
    }

    /** Negative adjustment; only from {@code available_to_sell}. */
    public static BucketDelta sellableOut(long q) {
        return new BucketDelta(-q, -q, 0, 0, 0);
    }

    public static BucketDelta reserve(long q) {
        return new BucketDelta(0, -q, q, 0, 0);
    }

    public static BucketDelta releaseReservation(long q) {
        return new BucketDelta(0, q, -q, 0, 0);
    }

    public static BucketDelta commitPayment(long q) {
        return new BucketDelta(0, 0, -q, q, 0);
    }

    /** Fenced late-payment recovery: {@code available_to_sell -> committed}. */
    public static BucketDelta recoverToCommitted(long q) {
        return new BucketDelta(0, -q, 0, q, 0);
    }

    public static BucketDelta releaseCommitted(long q) {
        return new BucketDelta(0, q, 0, -q, 0);
    }

    public static BucketDelta physicalDispatch(long q) {
        return new BucketDelta(-q, 0, 0, -q, 0);
    }

    public static BucketDelta returnReceipt(long q) {
        return new BucketDelta(q, 0, 0, 0, q);
    }

    public static BucketDelta inspectionPass(long q) {
        return new BucketDelta(0, q, 0, 0, -q);
    }

    /** {@code DISPOSE} or {@code RETURN_TO_SUPPLIER} after physical departure. */
    public static BucketDelta quarantineOut(long q) {
        return new BucketDelta(-q, 0, 0, 0, -q);
    }

    /** Disposition reversal: units physically back, into quarantine only. */
    public static BucketDelta quarantineBack(long q) {
        return new BucketDelta(q, 0, 0, 0, q);
    }

    public BucketDelta negate() {
        return new BucketDelta(-onHand, -availableToSell, -reserved, -committed, -quarantine);
    }
}
