package com.platform.paymentservice.integration;

import com.platform.paymentservice.application.port.out.RetentionPort;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RetentionTrimmerIT extends AbstractPaymentIT {
    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");

    @Test
    void trims_eligible_technical_data_but_fails_closed_without_cdc_watermark() {
        jdbc.update("""
                INSERT INTO payment_transactions
                  (id,order_id,payment_attempt_number,user_id,amount_minor,currency,
                   provider_transaction_id,status,expires_at,client_secret_ciphertext,
                   client_secret_key_id,client_expires_at)
                VALUES ('TXN_RETENTION',900,1,?,100,'VND','pi_retention','PENDING',
                        ?::timestamptz,decode('0102','hex'),'key-v1',?::timestamptz)
                """, UUID.randomUUID(), "2025-01-01T00:00:00Z", "2025-01-01T00:00:00Z");
        jdbc.update("""
                INSERT INTO webhook_logs
                  (stripe_event_id,event_type,event_created_at,livemode,raw_body_ciphertext,
                   raw_body_key_id,raw_body_sha256,stripe_signature_ciphertext,signing_secret_id,
                   verified_at,status,received_at,processed_at,payment_transaction_id)
                VALUES ('evt_retention','payment_intent.succeeded',?::timestamptz,false,
                        decode('0102','hex'),'key-v1',repeat('a',64),decode('0304','hex'),
                        'whsec-v1',?::timestamptz,'PROCESSED',?::timestamptz,
                        ?::timestamptz,'TXN_RETENTION')
                """, "2025-01-01T00:00:00Z", "2025-01-01T00:00:00Z",
                "2025-01-01T00:00:00Z", "2025-01-01T00:00:00Z");
        jdbc.update("""
                INSERT INTO payment_idempotency
                  (idempotency_key,action,request_fingerprint,status,resource_id,response_code,
                   response_body,created_at,completed_at,expires_at)
                VALUES ('expired-retention-key','INITIATE',repeat('b',64),'COMPLETED',
                        'TXN_RETENTION',200,'{}',?::timestamptz,?::timestamptz,?::timestamptz)
                """, "2024-01-01T00:00:00Z", "2024-01-01T00:00:00Z", "2025-01-01T00:00:00Z");
        jdbc.update("""
                INSERT INTO reconciliation_runs
                  (lane,window_from,window_to,status,finished_at,started_at)
                VALUES ('OBJECT_STATE',?::timestamptz,?::timestamptz,'COMPLETED',
                        ?::timestamptz,?::timestamptz)
                """, "2024-01-01T00:00:00Z", "2024-01-02T00:00:00Z",
                "2024-01-02T00:00:00Z", "2024-01-01T00:00:00Z");
        jdbc.update("""
                INSERT INTO outbox_events
                  (event_id,aggregate_id,event_type,partition_key,headers,payload,created_at)
                VALUES (?::uuid,'TXN_RETENTION','test.event','900','{}','{}',?::timestamptz)
                """, "00000000-0000-4000-8000-000000000900", "2024-01-01T00:00:00Z");

        RetentionPort.Result result = retention.trim(new RetentionPort.Policy(
                15, 90, 12, 12, 7, "missing_slot"), NOW, 5000);

        assertThat(result.clientSecretsShredded()).isEqualTo(1);
        assertThat(result.webhookMetadataDeleted()).isEqualTo(1);
        assertThat(result.idempotencyDeleted()).isEqualTo(1);
        assertThat(result.reconciliationRunsDeleted()).isEqualTo(1);
        assertThat(result.outboxWatermarkAvailable()).isFalse();
        assertThat(result.outboxDeleted()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT client_secret_ciphertext IS NULL FROM payment_transactions WHERE id='TXN_RETENTION'", Boolean.class)).isTrue();
    }

    @Test
    void seven_year_financial_delete_requires_export_manifest_and_no_legal_hold() {
        jdbc.update("""
                INSERT INTO payment_transactions
                  (id,order_id,payment_attempt_number,user_id,amount_minor,currency,
                   provider_transaction_id,status,expires_at,succeeded_at,updated_at)
                VALUES ('TXN_ARCHIVE',901,1,?,100,'VND','pi_archive','SUCCESS',
                        ?::timestamptz,?::timestamptz,?::timestamptz)
                """, UUID.randomUUID(), "2018-01-01T00:00:00Z",
                "2018-01-01T00:00:00Z", "2018-01-01T00:00:00Z");
        jdbc.update("""
                INSERT INTO payment_status_history
                  (payment_transaction_id,transaction_type,transition_id,old_status,new_status,
                   financial_state_version,triggered_by,created_at)
                VALUES ('TXN_ARCHIVE','PAYMENT','T2','PENDING','SUCCESS',1,'WEBHOOK',
                        ?::timestamptz)
                """, "2018-01-01T00:00:00Z");
        RetentionPort.Policy policy = new RetentionPort.Policy(15, 90, 12, 12, 7, "missing_slot");

        assertThat(retention.trim(policy, NOW, 5000).financialPaymentsDeleted()).isZero();
        jdbc.update("""
                UPDATE payment_transactions SET financial_export_manifest_id='manifest-2018-01',
                       legal_hold_until=?::timestamptz WHERE id='TXN_ARCHIVE'
                """, "2026-09-26T00:00:00Z");
        assertThat(retention.trim(policy, NOW, 5000).financialPaymentsDeleted()).isZero();
        jdbc.update("UPDATE payment_transactions SET legal_hold_until=NULL WHERE id='TXN_ARCHIVE'");

        assertThat(retention.trim(policy, NOW, 5000).financialPaymentsDeleted()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE id='TXN_ARCHIVE'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_status_history WHERE payment_transaction_id='TXN_ARCHIVE'", Integer.class))
                .isZero();
    }
}
