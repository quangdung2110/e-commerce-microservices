package com.platform.paymentservice.application.port.out;

import com.platform.paymentservice.domain.PaymentStatus;

import java.time.Instant;
import java.util.Optional;

public interface PaymentClientDataPort {
    Optional<StoredClientData> find(String transactionId);

    record StoredClientData(
            String transactionId,
            int paymentAttemptNumber,
            PaymentStatus status,
            EncryptedSecret encryptedClientSecret,
            Instant clientExpiresAt) {}
}
