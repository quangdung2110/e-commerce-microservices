package com.platform.paymentservice.application.port.out;

import java.time.Instant;

public interface WebhookEventHandlerPort {
    HandlingResult handle(WebhookProcessingPort.Claimed claim, byte[] exactBody, Instant now)
            throws ProcessingException;

    enum HandlingResult { COMMITTED, FENCED }

    final class ProcessingException extends Exception {
        private final boolean retryable;

        public ProcessingException(String message, boolean retryable) {
            super(message);
            this.retryable = retryable;
        }

        public ProcessingException(String message, boolean retryable, Throwable cause) {
            super(message, cause);
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }
}
