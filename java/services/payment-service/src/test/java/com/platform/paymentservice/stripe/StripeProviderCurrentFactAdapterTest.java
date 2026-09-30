package com.platform.paymentservice.stripe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.paymentservice.application.port.out.ProviderCurrentFactPort;
import com.platform.paymentservice.application.port.out.StripeWebhookEvent;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.StripeException;
import com.stripe.model.Dispute;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.model.StripeSearchResult;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentSearchParams;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class StripeProviderCurrentFactAdapterTest {
    private static final Instant CREATED = Instant.parse("2026-09-23T10:00:00Z");

    @Test
    void maps_current_payment_refund_and_dispute_lifecycle_facts() {
        FakeGateway gateway = new FakeGateway();
        gateway.intent = new PaymentIntent();
        gateway.intent.setId("pi_current");
        gateway.intent.setStatus("succeeded");
        gateway.intent.setAmount(150_000L);
        gateway.intent.setCurrency("vnd");
        gateway.intent.setLatestCharge("ch_current");
        gateway.refund = new Refund();
        gateway.refund.setId("re_current");
        gateway.refund.setStatus("failed");
        gateway.refund.setAmount(150_000L);
        gateway.refund.setCurrency("vnd");
        gateway.refund.setPaymentIntent("pi_current");
        gateway.refund.setCharge("ch_current");
        gateway.refund.setFailureReason("expired_or_canceled_card");
        gateway.dispute = new Dispute();
        gateway.dispute.setId("dp_current");
        gateway.dispute.setStatus("won");
        gateway.dispute.setAmount(150_000L);
        gateway.dispute.setCurrency("vnd");
        gateway.dispute.setCharge("ch_current");
        gateway.dispute.setReason("fraudulent");
        StripeProviderCurrentFactAdapter adapter = adapter(gateway);

        var payment = (ProviderCurrentFactPort.CurrentFact)
                adapter.retrievePaymentIntent(source("payment_intent.canceled"), "pi_current");
        var refund = (ProviderCurrentFactPort.CurrentFact)
                adapter.retrieveRefund(source("refund.updated"), "re_current");
        var dispute = (ProviderCurrentFactPort.CurrentFact)
                adapter.retrieveDispute(source("charge.dispute.updated"), "dp_current");

        assertThat(payment.event().eventType()).isEqualTo("payment_intent.succeeded");
        assertThat(payment.event().eventId()).isEqualTo("evt_tie");
        assertThat(refund.event().eventType()).isEqualTo("refund.failed");
        assertThat(refund.event().failureReason()).isEqualTo("expired_or_canceled_card");
        assertThat(dispute.event().eventType()).isEqualTo("charge.dispute.closed");
        assertThat(dispute.event().providerStatus()).isEqualTo("won");
    }

    @Test
    void does_not_infer_dispute_funds_movement_from_lifecycle_snapshot() {
        FakeGateway gateway = new FakeGateway();
        gateway.dispute = new Dispute();
        gateway.dispute.setId("dp_current");
        gateway.dispute.setStatus("under_review");
        gateway.dispute.setAmount(150_000L);
        gateway.dispute.setCurrency("vnd");

        var result = adapter(gateway).retrieveDispute(
                source("charge.dispute.funds_reinstated"), "dp_current");

        assertThat(result).isInstanceOf(ProviderCurrentFactPort.ReviewRequired.class);
        assertThat(((ProviderCurrentFactPort.ReviewRequired) result).reasonCode())
                .isEqualTo("DISPUTE_FUNDS_TIE_UNRESOLVED");
    }

    @Test
    void refund_status_is_the_source_of_truth_and_books_the_right_balance_transaction() {
        FakeGateway gateway = new FakeGateway();
        gateway.refund = refund("succeeded", null);
        gateway.refund.setBalanceTransaction("txn_refund_ok");
        gateway.refund.setFailureBalanceTransaction("txn_must_not_be_used");

        var succeeded = (ProviderCurrentFactPort.CurrentFact)
                adapter(gateway).retrieveRefund(source("charge.refunded"), "re_current");

        assertThat(succeeded.event().providerStatus()).isEqualTo("succeeded");
        assertThat(succeeded.event().providerBalanceTransactionId()).isEqualTo("txn_refund_ok");

        gateway.refund = refund("failed", "expired_or_canceled_card");
        gateway.refund.setBalanceTransaction("txn_refund_original");
        gateway.refund.setFailureBalanceTransaction("txn_refund_failed");
        var failed = (ProviderCurrentFactPort.CurrentFact)
                adapter(gateway).retrieveRefund(source("refund.updated"), "re_current");

        assertThat(failed.event().eventType()).isEqualTo("refund.failed");
        assertThat(failed.event().providerBalanceTransactionId()).isEqualTo("txn_refund_failed");
    }

    @Test
    void canceled_refund_is_a_failure_that_defaults_to_merchant_request() {
        FakeGateway gateway = new FakeGateway();
        gateway.refund = refund("canceled", null);

        var canceled = (ProviderCurrentFactPort.CurrentFact)
                adapter(gateway).retrieveRefund(source("refund.updated"), "re_current");

        assertThat(canceled.event().eventType()).isEqualTo("refund.failed");
        assertThat(canceled.event().failureReason()).isEqualTo("merchant_request");
    }

    @Test
    void requires_action_is_a_fact_and_pending_is_not() {
        FakeGateway gateway = new FakeGateway();
        gateway.refund = refund("requires_action", null);

        var action = (ProviderCurrentFactPort.CurrentFact)
                adapter(gateway).retrieveRefund(source("refund.updated"), "re_current");
        assertThat(action.event().providerStatus()).isEqualTo("requires_action");
        assertThat(action.event().providerBalanceTransactionId()).isNull();

        gateway.refund = refund("pending", null);
        assertThat(adapter(gateway).retrieveRefund(source("refund.updated"), "re_current"))
                .isInstanceOf(ProviderCurrentFactPort.NoTransition.class);
    }

    private static Refund refund(String status, String failureReason) {
        Refund refund = new Refund();
        refund.setId("re_current");
        refund.setStatus(status);
        refund.setAmount(150_000L);
        refund.setCurrency("vnd");
        refund.setPaymentIntent("pi_current");
        refund.setCharge("ch_current");
        refund.setFailureReason(failureReason);
        return refund;
    }

    @Test
    void transport_failure_is_retryable() {
        FakeGateway gateway = new FakeGateway();
        gateway.failure = new ApiConnectionException("timeout");

        assertThat(adapter(gateway).retrievePaymentIntent(
                source("payment_intent.succeeded"), "pi_current"))
                .isInstanceOf(ProviderCurrentFactPort.TemporarilyUnavailable.class);
    }

    private static StripeProviderCurrentFactAdapter adapter(FakeGateway gateway) {
        return new StripeProviderCurrentFactAdapter(gateway, new ObjectMapper(),
                Duration.ofSeconds(5), Duration.ofSeconds(15), 2);
    }

    private static StripeWebhookEvent source(String eventType) {
        return new StripeWebhookEvent("evt_tie", eventType, CREATED, "object_source",
                "pi_current", "ch_current", "re_current", "dp_current", "TXN_01",
                150_000L, "VND", null, null, null, null, null, null, null,
                null, "{}");
    }

    private static final class FakeGateway implements StripeGateway {
        private PaymentIntent intent;
        private Refund refund;
        private Dispute dispute;
        private StripeException failure;

        @Override public PaymentIntent createPaymentIntent(
                PaymentIntentCreateParams params, RequestOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override public PaymentIntent cancelPaymentIntent(
                String paymentIntentId, PaymentIntentCancelParams params, RequestOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override public PaymentIntent retrievePaymentIntent(
                String paymentIntentId, RequestOptions options) throws StripeException {
            if (failure != null) throw failure;
            return intent;
        }

        @Override public StripeSearchResult<PaymentIntent> searchPaymentIntents(
                PaymentIntentSearchParams params, RequestOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override public Refund retrieveRefund(String refundId, RequestOptions options)
                throws StripeException {
            if (failure != null) throw failure;
            return refund;
        }

        @Override public Dispute retrieveDispute(String disputeId, RequestOptions options)
                throws StripeException {
            if (failure != null) throw failure;
            return dispute;
        }
    }
}
