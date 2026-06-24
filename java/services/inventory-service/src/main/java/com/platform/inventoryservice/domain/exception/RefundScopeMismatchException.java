package com.platform.inventoryservice.domain.exception;

import com.platform.inventoryservice.common.ErrorCode;

import java.util.List;

/**
 * Exception khi replay {@code /refund-expectations} với tập {@code order_item_ids}
 * khác với lần đăng ký đầu cho cùng {@code (order_id, refund_request_id)}.
 * Mang theo {@code existing} và {@code requested} để response 409 trả chi tiết.
 */
public class RefundScopeMismatchException extends DomainException {

    private final List<Long> existing;
    private final List<Long> requested;

    public RefundScopeMismatchException(List<Long> existing, List<Long> requested) {
        super(ErrorCode.REFUND_SCOPE_MISMATCH,
                "Refund scope mismatch. Existing: " + existing + ", Requested: " + requested);
        this.existing = List.copyOf(existing);
        this.requested = List.copyOf(requested);
    }

    public List<Long> existing() {
        return existing;
    }

    public List<Long> requested() {
        return requested;
    }
}