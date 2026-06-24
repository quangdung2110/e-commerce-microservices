package com.platform.inventoryservice.domain.exception;

import com.platform.inventoryservice.common.ErrorCode;

public class RefundNotExpectedException extends DomainException {
    public RefundNotExpectedException(long orderId, long orderItemId) {
        super(ErrorCode.REFUND_NOT_EXPECTED, String.format("Refund not expected for orderId=%d orderItemId=%d", orderId, orderItemId));
    }
}
