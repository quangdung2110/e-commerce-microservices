package com.ecommerce.pdp.resilience;

/**
 * Response của dependency không dùng được: body không parse được, thiếu field bắt buộc,
 * sai kiểu/enum/giới hạn, hoặc vi phạm ràng buộc tham chiếu. Tính vào circuit breaker.
 * Field không biết không phải vi phạm (tolerant reader, ADR-006).
 */
public class ContractViolationException extends RuntimeException {

    public ContractViolationException(String message) {
        super(message);
    }

    public ContractViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
