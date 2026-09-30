package com.platform.paymentservice.stripe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.paymentservice.application.port.out.ProviderOperationResult;
import com.platform.paymentservice.domain.ProviderOperation;
import com.platform.paymentservice.domain.ProviderOperationStatus;
import com.platform.paymentservice.domain.ProviderOperationType;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeSearchResult;
import com.stripe.model.StripeCollection;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.PaymentIntentSearchParams;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.RefundListParams;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StripePaymentProviderAdapterTest {
    @Test
    void creates_vnd_intent_with_stable_key_metadata_and_no_persisted_secret() {
        FakeGateway gateway = new FakeGateway(intent("pi_123", 150_000, "vnd", "secret-value"));
        StripePaymentProviderAdapter adapter = adapter(gateway);

        ProviderOperationResult.Confirmed result = (ProviderOperationResult.Confirmed) adapter.dispatch(operation());

        assertThat(gateway.createParams.getAmount()).isEqualTo(150_000L);
        assertThat(gateway.createParams.getCurrency()).isEqualTo("vnd");
        assertThat(gateway.createParams.getConfirmationMethod())
                .isEqualTo(PaymentIntentCreateParams.ConfirmationMethod.AUTOMATIC);
        assertThat(gateway.createParams.getMetadata()).containsAllEntriesOf(metadata());
        // ADR-022 / RULE-PAY-025: card only, regardless of the Dashboard payment-method config.
        assertThat(gateway.createParams.getPaymentMethodTypes()).containsExactly("card");
        assertThat(gateway.requestOptions.getIdempotencyKey()).isEqualTo("pay:create:TXN_01");
        assertThat(gateway.requestOptions.getConnectTimeout()).isEqualTo(5_000);
        assertThat(gateway.requestOptions.getReadTimeout()).isEqualTo(15_000);
        assertThat(gateway.requestOptions.getMaxNetworkRetries()).isEqualTo(2);
        assertThat(result.resultJson()).doesNotContain("secret-value", "client_secret");
        assertThat(new String(result.transientClientSecret().orElseThrow())).isEqualTo("secret-value");
        assertThat(result.toString()).contains("REDACTED").doesNotContain("secret-value");
    }

    @Test
    void after_horizon_binds_only_one_exact_metadata_amount_and_currency_match() {
        PaymentIntent exact = intent("pi_exact", 150_000, "vnd", "secret-exact");
        PaymentIntent wrongAmount = intent("pi_wrong", 149_999, "vnd", "secret-wrong");
        FakeGateway gateway = new FakeGateway(exact);
        gateway.searchData = List.of(exact, wrongAmount);

        ProviderOperationResult result = adapter(gateway).searchBeforeAnyRecreate(operation());

        assertThat(result).isInstanceOf(ProviderOperationResult.Confirmed.class);
        assertThat(((ProviderOperationResult.Confirmed) result).providerObjectId()).isEqualTo("pi_exact");
        assertThat(gateway.searchParams.getQuery())
                .isEqualTo("metadata['payment_transaction_id']:'TXN_01'");
        assertThat(gateway.createCalls).isZero();
    }

    @Test
    void maps_4xx_to_rejected_and_transport_failure_to_unknown() {
        FakeGateway rejected = new FakeGateway(new InvalidRequestException(
                "bad request", "amount", "req_bad", "parameter_invalid", 400, null));
        assertThat(adapter(rejected).dispatch(operation())).isInstanceOf(ProviderOperationResult.Rejected.class);

        FakeGateway unknown = new FakeGateway(new ApiConnectionException("timeout"));
        assertThat(adapter(unknown).dispatch(operation())).isInstanceOf(ProviderOperationResult.Unknown.class);
    }

    @Test
    void cancel_uses_stable_key_and_only_confirms_canceled_or_succeeded_provider_facts() {
        FakeGateway canceled = new FakeGateway(intent("pi_cancel", 150_000, "vnd", null));
        canceled.cancelResult.setStatus("canceled");

        ProviderOperationResult canceledResult = adapter(canceled).dispatch(cancelOperation());

        assertThat(canceledResult).isInstanceOf(ProviderOperationResult.Confirmed.class);
        assertThat(canceled.cancelPaymentIntentId).isEqualTo("pi_cancel");
        assertThat(canceled.cancelParams.getCancellationReason())
                .isEqualTo(PaymentIntentCancelParams.CancellationReason.ABANDONED);
        assertThat(canceled.requestOptions.getIdempotencyKey()).isEqualTo("pay:cancel:TXN_01");
        assertThat(canceledResult.resultJson()).contains("\"status\":\"canceled\"");

        FakeGateway processing = new FakeGateway(intent("pi_cancel", 150_000, "vnd", null));
        processing.cancelResult.setStatus("processing");
        assertThat(adapter(processing).dispatch(cancelOperation()))
                .isInstanceOf(ProviderOperationResult.Unknown.class);
    }

    @Test
    void cancel_error_retrieves_actual_fact_and_after_horizon_is_retrieve_only() {
        FakeGateway raced = new FakeGateway(new InvalidRequestException(
                "cannot cancel", null, "req_cancel", "payment_intent_unexpected_state", 400, null));
        raced.retrieveResult = intent("pi_cancel", 150_000, "vnd", null);
        raced.retrieveResult.setStatus("succeeded");

        ProviderOperationResult result = adapter(raced).dispatch(cancelOperation());

        assertThat(result).isInstanceOf(ProviderOperationResult.Confirmed.class);
        assertThat(result.resultJson()).contains("\"status\":\"succeeded\"");
        assertThat(raced.retrieveCalls).isEqualTo(1);

        FakeGateway lookupOnly = new FakeGateway(intent("pi_unused", 1, "vnd", null));
        lookupOnly.retrieveResult = intent("pi_cancel", 150_000, "vnd", null);
        lookupOnly.retrieveResult.setStatus("canceled");
        ProviderOperationResult lookup = adapter(lookupOnly).searchBeforeAnyRecreate(cancelOperation());
        assertThat(lookup).isInstanceOf(ProviderOperationResult.Confirmed.class);
        assertThat(lookupOnly.cancelCalls).isZero();
        assertThat(lookupOnly.retrieveCalls).isEqualTo(1);
    }

    @Test
    void refund_uses_stable_key_complete_metadata_and_lookup_only_after_horizon() {
        FakeGateway gateway = new FakeGateway(intent("pi_unused", 1, "vnd", null));
        gateway.refundResult = refund("re_exact", 150_000, "vnd", "pi_refund");

        ProviderOperationResult result = adapter(gateway).dispatch(refundOperation());

        assertThat(result).isInstanceOf(ProviderOperationResult.Confirmed.class);
        assertThat(gateway.refundCreateParams.getPaymentIntent()).isEqualTo("pi_refund");
        assertThat(gateway.refundCreateParams.getAmount()).isEqualTo(150_000L);
        assertThat(gateway.refundCreateParams.getReason()).isEqualTo(RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER);
        assertThat(gateway.refundCreateParams.getMetadata()).isEqualTo(refundMetadata());
        assertThat(gateway.requestOptions.getIdempotencyKey()).isEqualTo("pay:refund:RFD_01");

        Refund wrong = refund("re_wrong", 149_999, "vnd", "pi_refund");
        gateway.refundListData = List.of(gateway.refundResult, wrong);
        ProviderOperationResult lookup = adapter(gateway).searchBeforeAnyRecreate(refundOperation());
        assertThat(lookup).isInstanceOf(ProviderOperationResult.Confirmed.class);
        assertThat(((ProviderOperationResult.Confirmed) lookup).providerObjectId()).isEqualTo("re_exact");
        assertThat(gateway.refundListParams.getPaymentIntent()).isEqualTo("pi_refund");
        assertThat(gateway.refundCreateCalls).isEqualTo(1);
    }

    private static StripePaymentProviderAdapter adapter(FakeGateway gateway) {
        return new StripePaymentProviderAdapter(gateway, new ObjectMapper(),
                Duration.ofSeconds(5), Duration.ofSeconds(15), 2);
    }

    private static ProviderOperation operation() {
        return new ProviderOperation("POP_01", "TXN_01", null, ProviderOperationType.CREATE,
                "pay:create:TXN_01", "command-01", """
                {"amount_minor":150000,"currency":"VND","metadata":{
                  "payment_transaction_id":"TXN_01","order_id":"99",
                  "payment_attempt_number":"1","user_id":"00000000-0000-0000-0000-000000000001"}}
                """, ProviderOperationStatus.REQUESTED, null, 0, null, null,
                Instant.parse("2026-09-29T10:00:00Z"), Instant.parse("2026-09-22T10:00:00Z"));
    }

    private static ProviderOperation cancelOperation() {
        return new ProviderOperation("POP_CANCEL", "TXN_01", null, ProviderOperationType.CANCEL,
                "pay:cancel:TXN_01", "cancel-command-01", """
                {"provider_transaction_id":"pi_cancel","reason":"ORDER_COMMAND:PAYMENT_TIMEOUT"}
                """, ProviderOperationStatus.REQUESTED, null, 0, null, null,
                Instant.parse("2026-09-29T10:00:00Z"), Instant.parse("2026-09-22T10:00:00Z"));
    }

    private static ProviderOperation refundOperation() {
        return new ProviderOperation("POP_REFUND", "TXN_01", "RFD_01", ProviderOperationType.REFUND,
                "pay:refund:RFD_01", "refund-command-01", """
                {"provider_transaction_id":"pi_refund","amount_minor":150000,"currency":"VND",
                 "metadata":{"order_id":"99","refund_request_id":"request-1",
                 "refund_transaction_id":"RFD_01","payment_transaction_id":"TXN_01"}}
                """, ProviderOperationStatus.REQUESTED, null, 0, null, null,
                Instant.parse("2026-09-29T10:00:00Z"), Instant.parse("2026-09-22T10:00:00Z"));
    }

    private static Map<String, String> metadata() {
        return Map.of(
                "payment_transaction_id", "TXN_01",
                "order_id", "99",
                "payment_attempt_number", "1",
                "user_id", "00000000-0000-0000-0000-000000000001");
    }

    private static Map<String, String> refundMetadata() {
        return Map.of("order_id", "99", "refund_request_id", "request-1",
                "refund_transaction_id", "RFD_01", "payment_transaction_id", "TXN_01");
    }

    private static Refund refund(String id, long amount, String currency, String paymentIntent) {
        Refund refund = new Refund();
        refund.setId(id); refund.setAmount(amount); refund.setCurrency(currency);
        refund.setPaymentIntent(paymentIntent); refund.setCharge("ch_refund");
        refund.setStatus("pending"); refund.setMetadata(refundMetadata());
        return refund;
    }

    private static PaymentIntent intent(String id, long amount, String currency, String clientSecret) {
        PaymentIntent intent = new PaymentIntent();
        intent.setId(id);
        intent.setAmount(amount);
        intent.setCurrency(currency);
        intent.setStatus("requires_payment_method");
        intent.setClientSecret(clientSecret);
        intent.setMetadata(metadata());
        return intent;
    }

    private static final class FakeGateway implements StripeGateway {
        private final PaymentIntent createResult;
        private final StripeException failure;
        private PaymentIntentCreateParams createParams;
        private PaymentIntentCancelParams cancelParams;
        private PaymentIntentSearchParams searchParams;
        private RequestOptions requestOptions;
        private List<PaymentIntent> searchData = List.of();
        private PaymentIntent cancelResult;
        private PaymentIntent retrieveResult;
        private String cancelPaymentIntentId;
        private int createCalls;
        private int cancelCalls;
        private int retrieveCalls;
        private Refund refundResult;
        private RefundCreateParams refundCreateParams;
        private RefundListParams refundListParams;
        private List<Refund> refundListData = List.of();
        private int refundCreateCalls;

        private FakeGateway(PaymentIntent createResult) {
            this.createResult = createResult;
            this.cancelResult = createResult;
            this.retrieveResult = createResult;
            this.failure = null;
        }

        private FakeGateway(StripeException failure) {
            this.createResult = null;
            this.failure = failure;
        }

        @Override
        public PaymentIntent createPaymentIntent(PaymentIntentCreateParams params, RequestOptions options)
                throws StripeException {
            createCalls++;
            createParams = params;
            requestOptions = options;
            if (failure != null) throw failure;
            return createResult;
        }

        @Override
        public PaymentIntent cancelPaymentIntent(String paymentIntentId, PaymentIntentCancelParams params,
                                                 RequestOptions options) throws StripeException {
            cancelCalls++;
            cancelPaymentIntentId = paymentIntentId;
            cancelParams = params;
            requestOptions = options;
            if (failure != null) throw failure;
            return cancelResult;
        }

        @Override
        public PaymentIntent retrievePaymentIntent(String paymentIntentId, RequestOptions options)
                throws StripeException {
            retrieveCalls++;
            requestOptions = options;
            if (retrieveResult != null) return retrieveResult;
            if (failure != null) throw failure;
            return createResult;
        }

        @Override
        public StripeSearchResult<PaymentIntent> searchPaymentIntents(PaymentIntentSearchParams params,
                                                                      RequestOptions options)
                throws StripeException {
            searchParams = params;
            requestOptions = options;
            if (failure != null) throw failure;
            StripeSearchResult<PaymentIntent> result = new StripeSearchResult<>();
            result.setData(searchData);
            return result;
        }

        @Override
        public Refund createRefund(RefundCreateParams params, RequestOptions options) throws StripeException {
            refundCreateCalls++; refundCreateParams = params; requestOptions = options;
            if (failure != null) throw failure;
            return refundResult;
        }

        @Override
        public StripeCollection<Refund> listRefunds(RefundListParams params, RequestOptions options)
                throws StripeException {
            refundListParams = params; requestOptions = options;
            if (failure != null) throw failure;
            StripeCollection<Refund> result = new StripeCollection<>();
            result.setData(refundListData);
            return result;
        }
    }
}
