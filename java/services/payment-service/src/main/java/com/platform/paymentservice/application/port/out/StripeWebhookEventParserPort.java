package com.platform.paymentservice.application.port.out;

public interface StripeWebhookEventParserPort {
    StripeWebhookEvent parse(byte[] exactBody) throws InvalidStripeEventException;

    final class InvalidStripeEventException extends Exception {
        public InvalidStripeEventException(String message, Throwable cause) {
            super(message, cause);
        }

        public InvalidStripeEventException(String message) {
            super(message);
        }
    }
}
