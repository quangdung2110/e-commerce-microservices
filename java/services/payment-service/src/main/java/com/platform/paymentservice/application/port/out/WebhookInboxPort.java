package com.platform.paymentservice.application.port.out;

import java.time.Instant;

public interface WebhookInboxPort {
    SaveResult saveVerified(VerifiedDelivery delivery);

    enum SaveResult { READY_TO_PUBLISH, DUPLICATE_TERMINAL }

    record VerifiedDelivery(
            VerifiedWebhook webhook,
            EncryptedSecret encryptedRawBody,
            EncryptedSecret encryptedSignature,
            String rawBodySha256,
            Instant receivedAt,
            Instant verifiedAt) {
    }
}
