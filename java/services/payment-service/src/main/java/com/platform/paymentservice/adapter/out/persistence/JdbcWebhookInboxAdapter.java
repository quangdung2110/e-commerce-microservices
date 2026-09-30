package com.platform.paymentservice.adapter.out.persistence;

import com.platform.paymentservice.application.port.out.WebhookInboxPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.Optional;

@Component
public final class JdbcWebhookInboxAdapter implements WebhookInboxPort {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public JdbcWebhookInboxAdapter(JdbcClient jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public SaveResult saveVerified(VerifiedDelivery delivery) {
        if (!delivery.encryptedRawBody().keyId().equals(delivery.encryptedSignature().keyId())) {
            throw new IllegalStateException("webhook body and signature must use the same encryption key");
        }
        return transactions.execute(ignored -> saveInTransaction(delivery));
    }

    private SaveResult saveInTransaction(VerifiedDelivery delivery) {
        Optional<String> status = jdbc.sql("""
                INSERT INTO webhook_logs
                  (provider, stripe_event_id, event_type, event_created_at, api_version,
                   livemode, raw_body_ciphertext, raw_body_key_id, raw_body_sha256,
                   stripe_signature_ciphertext, signing_secret_id, verified_at,
                   status, received_at)
                VALUES
                  ('STRIPE', :event_id, :event_type, :event_created_at, :api_version,
                   :livemode, :raw_ciphertext, :key_id, :body_sha256,
                   :signature_ciphertext, :signing_secret_id, :verified_at,
                   'RECEIVED', :received_at)
                ON CONFLICT (stripe_event_id) DO UPDATE SET
                  event_type = EXCLUDED.event_type,
                  event_created_at = EXCLUDED.event_created_at,
                  api_version = EXCLUDED.api_version,
                  livemode = EXCLUDED.livemode,
                  raw_body_ciphertext = EXCLUDED.raw_body_ciphertext,
                  raw_body_key_id = EXCLUDED.raw_body_key_id,
                  raw_body_sha256 = EXCLUDED.raw_body_sha256,
                  stripe_signature_ciphertext = EXCLUDED.stripe_signature_ciphertext,
                  signing_secret_id = EXCLUDED.signing_secret_id,
                  verified_at = EXCLUDED.verified_at,
                  received_at = EXCLUDED.received_at
                WHERE webhook_logs.status NOT IN ('PROCESSED','IGNORED')
                RETURNING status
                """)
                .param("event_id", delivery.webhook().stripeEventId())
                .param("event_type", delivery.webhook().eventType())
                .param("event_created_at", Timestamp.from(delivery.webhook().eventCreatedAt()))
                .param("api_version", delivery.webhook().apiVersion())
                .param("livemode", delivery.webhook().livemode())
                .param("raw_ciphertext", delivery.encryptedRawBody().ciphertext())
                .param("key_id", delivery.encryptedRawBody().keyId())
                .param("body_sha256", delivery.rawBodySha256())
                .param("signature_ciphertext", delivery.encryptedSignature().ciphertext())
                .param("signing_secret_id", delivery.webhook().signingSecretId())
                .param("verified_at", Timestamp.from(delivery.verifiedAt()))
                .param("received_at", Timestamp.from(delivery.receivedAt()))
                .query(String.class).optional();
        if (status.isPresent()) return SaveResult.READY_TO_PUBLISH;

        String existing = jdbc.sql("SELECT status FROM webhook_logs WHERE stripe_event_id=:event_id")
                .param("event_id", delivery.webhook().stripeEventId())
                .query(String.class).single();
        if ("PROCESSED".equals(existing) || "IGNORED".equals(existing)) {
            return SaveResult.DUPLICATE_TERMINAL;
        }
        throw new IllegalStateException("webhook inbox upsert lost a non-terminal row");
    }
}
