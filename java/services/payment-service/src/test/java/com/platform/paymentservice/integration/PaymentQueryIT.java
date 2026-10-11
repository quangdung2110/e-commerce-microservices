package com.platform.paymentservice.integration;

import com.platform.paymentservice.adapter.out.persistence.JdbcPaymentQueryAdapter;
import com.platform.paymentservice.application.port.out.PaymentQueryPort;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentQueryIT extends AbstractPaymentIT {
    private static final String TXN = "TXN_01K5V7M4R8Q2K7V6W5X1Y0ZABE";
    private static final String RFD = "RFD_01K5V7M4R8Q2K7V6W5X1Y0ZABF";
    private static final String DSP = "DSP_01K5V7M4R8Q2K7V6W5X1Y0ZABG";
    private static final Instant NOW = Instant.parse("2026-10-11T01:00:00Z");

    @Test
    void unknown_ids_are_empty() {
        JdbcPaymentQueryAdapter queries = new JdbcPaymentQueryAdapter(jdbcClient);
        assertThat(queries.findPayment(TXN)).isEmpty();
        assertThat(queries.findRefund(RFD)).isEmpty();
    }

    @Test
    void pending_payment_has_no_refund_and_no_disputes() {
        seedPayment("PENDING", null);
        PaymentQueryPort.PaymentView view = new JdbcPaymentQueryAdapter(jdbcClient).findPayment(TXN).orElseThrow();

        assertThat(view.orderId()).isEqualTo(10002L);
        assertThat(view.paymentAttemptNumber()).isEqualTo(1);
        assertThat(view.amountMinor()).isEqualTo(250_000L);
        assertThat(view.currency()).isEqualTo("VND");
        assertThat(view.status()).isEqualTo("PENDING");
        assertThat(view.succeededAt()).isNull();
        assertThat(view.refund()).isNull();
        assertThat(view.disputes()).isEmpty();
    }

    @Test
    void captured_payment_carries_its_refund_and_disputes_ordered_by_opening_time() {
        seedPayment("REFUNDING", NOW);
        jdbc.update("""
                INSERT INTO refund_transactions
                  (id, payment_transaction_id, order_id, refund_request_id, command_idempotency_key,
                   refund_amount_minor, reason, inventory_evidence, status)
                VALUES (?, ?, 10002, 'rfr_1', 'key-1', 250000, 'ADMIN_FORCE_REFUND_BEFORE_DISPATCH',
                        '{"type":"COMMITTED_ORDER_RELEASED_BEFORE_DISPATCH"}'::jsonb, 'PROCESSING')
                """, RFD, TXN);
        seedDispute("DSP_01K5V7M4R8Q2K7V6W5X1Y0ZABH", "dp_late", NOW.plusSeconds(3600));
        seedDispute(DSP, "dp_early", NOW.plusSeconds(60));

        PaymentQueryPort.PaymentView view = new JdbcPaymentQueryAdapter(jdbcClient).findPayment(TXN).orElseThrow();

        assertThat(view.status()).isEqualTo("REFUNDING");
        assertThat(view.succeededAt()).isEqualTo(NOW);
        assertThat(view.refund()).isNotNull();
        assertThat(view.refund().refundTransactionId()).isEqualTo(RFD);
        assertThat(view.refund().status()).isEqualTo("PROCESSING");
        assertThat(view.disputes()).extracting(PaymentQueryPort.DisputeView::disputeTransactionId)
                .containsExactly(DSP, "DSP_01K5V7M4R8Q2K7V6W5X1Y0ZABH");
        assertThat(view.disputes().getFirst().fundsState()).isEqualTo("UNCHANGED");
        assertThat(view.disputes().getFirst().closedAt()).isNull();

        PaymentQueryPort.RefundView refund = new JdbcPaymentQueryAdapter(jdbcClient).findRefund(RFD).orElseThrow();
        assertThat(refund.paymentTransactionId()).isEqualTo(TXN);
        assertThat(refund.amountMinor()).isEqualTo(250_000L);
        assertThat(refund.currency()).isEqualTo("VND");
    }

    private void seedPayment(String status, Instant succeededAt) {
        jdbc.update("""
                INSERT INTO payment_transactions
                  (id, order_id, payment_attempt_number, user_id, amount_minor, currency,
                   provider_transaction_id, status, expires_at, succeeded_at, financial_state_version)
                VALUES (?, 10002, 1, ?, 250000, 'VND', 'pi_query', ?, ?, ?, 3)
                """, TXN, UUID.randomUUID(), status, Timestamp.from(NOW.plusSeconds(900)),
                succeededAt == null ? null : Timestamp.from(succeededAt));
    }

    private void seedDispute(String id, String providerId, Instant openedAt) {
        jdbc.update("""
                INSERT INTO payment_disputes
                  (id, payment_transaction_id, order_id, provider_dispute_id, amount_minor, currency, reason_code,
                   status, opened_at, last_provider_event_created_at, last_provider_event_id)
                VALUES (?, ?, 10002, ?, 250000, 'VND', 'FRAUDULENT', 'OPEN', ?, ?, 'evt_1')
                """, id, TXN, providerId, Timestamp.from(openedAt), Timestamp.from(openedAt));
    }
}
