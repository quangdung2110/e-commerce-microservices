package com.platform.productservice.worker.common;

public record EventProcessingResult(
        boolean success,
        boolean skipped,
        boolean stale,
        String errorMessage
) {
    public static EventProcessingResult ok() {
        return new EventProcessingResult(true, false, false, null);
    }

    public static EventProcessingResult skipped(String reason) {
        return new EventProcessingResult(false, true, false, reason);
    }

    public static EventProcessingResult stale(String reason) {
        return new EventProcessingResult(false, false, true, reason);
    }

    public static EventProcessingResult error(String message) {
        return new EventProcessingResult(false, false, false, message);
    }
}
