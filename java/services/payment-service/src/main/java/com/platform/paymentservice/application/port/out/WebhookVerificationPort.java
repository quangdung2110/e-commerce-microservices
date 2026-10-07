package com.platform.paymentservice.application.port.out;

import java.time.Instant;

public interface WebhookVerificationPort {
    VerifiedWebhook verify(byte[] exactBody, String stripeSignature, Instant receivedAt)
            throws InvalidWebhookException;

    final class InvalidWebhookException extends Exception {
        public InvalidWebhookException(String message) {
            super(message);
        }

        public InvalidWebhookException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
