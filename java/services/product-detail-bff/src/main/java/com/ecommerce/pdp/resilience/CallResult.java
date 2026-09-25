package com.ecommerce.pdp.resilience;

import java.util.Optional;

/**
 * Kết quả đã phân loại của một lời gọi dependency. Không bao giờ ở dạng lỗi Reactor:
 * caller chỉ cần xét {@link #outcome()} để quyết định degrade.
 *
 * @param retryAfterSeconds chỉ có khi {@link Outcome#CIRCUIT_OPEN}: số giây còn lại tới lúc
 *                          circuit chuyển half-open, làm tròn lên, tối thiểu 1
 */
public record CallResult<T>(Outcome outcome, T value, Integer retryAfterSeconds) {

    public static <T> CallResult<T> success(T value) {
        return new CallResult<>(Outcome.SUCCESS, value, null);
    }

    public static <T> CallResult<T> failure(Outcome outcome) {
        return new CallResult<>(outcome, null, null);
    }

    public static <T> CallResult<T> circuitOpen(int retryAfterSeconds) {
        return new CallResult<>(Outcome.CIRCUIT_OPEN, null, retryAfterSeconds);
    }

    public boolean isSuccess() {
        return outcome == Outcome.SUCCESS;
    }

    public Optional<T> valueIfSuccess() {
        return isSuccess() ? Optional.ofNullable(value) : Optional.empty();
    }
}
