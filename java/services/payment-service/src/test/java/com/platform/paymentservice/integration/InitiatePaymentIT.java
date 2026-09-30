package com.platform.paymentservice.integration;

import com.platform.paymentservice.adapter.out.crypto.LocalAesGcmSecretCipher;
import com.platform.paymentservice.application.CanonicalFingerprint;
import com.platform.paymentservice.application.port.out.ClockPort;
import com.platform.paymentservice.application.port.out.IdGeneratorPort;
import com.platform.paymentservice.application.port.out.PaymentProviderPort;
import com.platform.paymentservice.application.port.out.ProviderOperationResult;
import com.platform.paymentservice.application.service.InitiatePaymentCommand;
import com.platform.paymentservice.application.service.InitiatePaymentResult;
import com.platform.paymentservice.application.service.InitiatePaymentService;
import com.platform.paymentservice.domain.Money;
import com.platform.paymentservice.domain.PaymentStateMachine;
import com.platform.paymentservice.domain.ProviderOperation;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InitiatePaymentIT extends AbstractPaymentIT {
    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");
    private static final String TXN = "TXN_01K5V7M4R8Q2K7V6W5X1Y0ZABC";
    private static final String OP = "POP_01K5V7M4R8Q2K7V6W5X1Y0ZABD";
    private static final String REVIEW = "PRC_01K5V7M4R8Q2K7V6W5X1Y0ZABF";
    private static final String KEY = "ord-10001-attempt-1-key";
    private static final UUID EVENT = UUID.fromString("00000000-0000-4000-8000-000000000123");
    private static final byte[] LOCAL_KEY = "0123456789abcdef0123456789abcdef"
            .getBytes(StandardCharsets.US_ASCII);

    @Test
    void tx1_provider_call_and_tx2_are_durable_atomic_and_secret_safe() {
        CapturingProvider provider = new CapturingProvider(new ProviderOperationResult.Confirmed(
                "pi_confirmed", "req_confirmed",
                "{\"id\":\"pi_confirmed\",\"status\":\"requires_payment_method\"}",
                "pi_confirmed_secret_safe".toCharArray()));
        InitiatePaymentService service = service(provider);

        InitiatePaymentResult result = service.initiate(command(1), "api-1");

        assertThat(result).isInstanceOf(InitiatePaymentResult.Pending.class);
        InitiatePaymentResult.Pending pending = (InitiatePaymentResult.Pending) result;
        assertThat(pending.transactionId()).isEqualTo(TXN);
        assertThat(pending.clientSecret()).containsExactly("pi_confirmed_secret_safe".toCharArray());
        assertThat(pending.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(provider.transactionWasActive).isFalse();
        assertThat(provider.calls).isEqualTo(1);
        assertThat(provider.operation.providerIdempotencyKey()).isEqualTo("pay:create:" + TXN);

        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE id=?", String.class, TXN))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT financial_state_version FROM payment_transactions WHERE id=?", Long.class, TXN))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT provider_transaction_id FROM payment_transactions WHERE id=?", String.class, TXN))
                .isEqualTo("pi_confirmed");
        assertThat(jdbc.queryForObject("SELECT status FROM provider_operations WHERE id=?", String.class, OP))
                .isEqualTo("PROVIDER_CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_idempotency WHERE idempotency_key=?", String.class, KEY))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT transition_id FROM payment_status_history WHERE payment_transaction_id=?", String.class, TXN))
                .isEqualTo("T0.1");
        assertThat(jdbc.queryForObject("SELECT event_type FROM outbox_events WHERE event_id=?", String.class, EVENT))
                .isEqualTo("com.ecommerce.payment.transaction.initiated.v2");
        assertThat(jdbc.queryForObject("SELECT partition_key FROM outbox_events WHERE event_id=?", String.class, EVENT))
                .isEqualTo("10001");
        assertThat(jdbc.queryForObject("SELECT payload->>'specversion' FROM outbox_events WHERE event_id=?", String.class, EVENT))
                .isEqualTo("1.0");
        assertThat(jdbc.queryForObject("SELECT payload->'data'->>'financial_state_version' FROM outbox_events WHERE event_id=?", String.class, EVENT))
                .isEqualTo("1");
        assertThat(allDurableJson()).doesNotContain("pi_confirmed_secret_safe");
        byte[] ciphertext = jdbc.queryForObject(
                "SELECT client_secret_ciphertext FROM payment_transactions WHERE id=?", byte[].class, TXN);
        assertThat(new String(ciphertext, StandardCharsets.ISO_8859_1))
                .doesNotContain("pi_confirmed_secret_safe");
    }

    @Test
    void completed_replay_regenerates_client_data_without_calling_stripe() {
        CapturingProvider provider = new CapturingProvider(new ProviderOperationResult.Confirmed(
                "pi_replay", null, "{\"id\":\"pi_replay\",\"status\":\"requires_payment_method\"}",
                "pi_replay_secret_value".toCharArray()));
        InitiatePaymentService service = service(provider);

        assertThat(service.initiate(command(1), "api-1"))
                .isInstanceOf(InitiatePaymentResult.Pending.class);
        InitiatePaymentResult replay = service.initiate(command(1), "api-2");

        assertThat(replay).isInstanceOf(InitiatePaymentResult.Pending.class);
        assertThat(((InitiatePaymentResult.Pending) replay).clientSecret())
                .containsExactly("pi_replay_secret_value".toCharArray());
        assertThat(provider.calls).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isEqualTo(1);
    }

    @Test
    void ambiguous_provider_result_is_recoverable_and_emits_no_fact() {
        CapturingProvider provider = new CapturingProvider(new ProviderOperationResult.Unknown(
                "req_timeout", "{\"error_code\":\"TIMEOUT\"}"));

        InitiatePaymentResult result = service(provider).initiate(command(1), "api-1");

        assertThat(result).isInstanceOf(InitiatePaymentResult.ProviderOutcomeUnknown.class);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE id=?", String.class, TXN))
                .isEqualTo("INITIATING");
        assertThat(jdbc.queryForObject("SELECT status FROM provider_operations WHERE id=?", String.class, OP))
                .isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_idempotency WHERE idempotency_key=?", String.class, KEY))
                .isEqualTo("IN_PROGRESS");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_status_history", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isZero();
    }

    @Test
    void definitive_provider_rejection_is_stored_for_replay_and_opens_review_atomically() {
        CapturingProvider provider = new CapturingProvider(new ProviderOperationResult.Rejected(
                "req_rejected", "{\"error_code\":\"IDEMPOTENCY_ERROR\"}"));
        InitiatePaymentService service = service(provider);

        InitiatePaymentResult result = service.initiate(command(1), "api-1");

        assertThat(result).isInstanceOf(InitiatePaymentResult.ProviderRejected.class);
        assertThat(provider.calls).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE id=?", String.class, TXN))
                .isEqualTo("INITIATING");
        assertThat(jdbc.queryForObject("SELECT requires_manual_review FROM payment_transactions WHERE id=?", Boolean.class, TXN))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT financial_state_version FROM payment_transactions WHERE id=?", Long.class, TXN))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT version FROM payment_transactions WHERE id=?", Long.class, TXN))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT status FROM provider_operations WHERE id=?", String.class, OP))
                .isEqualTo("PROVIDER_REJECTED");
        assertThat(jdbc.queryForObject("SELECT provider_request_id FROM provider_operations WHERE id=?", String.class, OP))
                .isEqualTo("req_rejected");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_idempotency WHERE idempotency_key=?", String.class, KEY))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT response_code FROM payment_idempotency WHERE idempotency_key=?", Integer.class, KEY))
                .isEqualTo(503);
        assertThat(jdbc.queryForObject("SELECT response_body->>'code' FROM payment_idempotency WHERE idempotency_key=?", String.class, KEY))
                .isEqualTo("PROVIDER_REQUEST_REJECTED");
        assertThat(jdbc.queryForObject("SELECT (response_body->>'retryable')::boolean FROM payment_idempotency WHERE idempotency_key=?", Boolean.class, KEY))
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT reason_code FROM payment_review_cases WHERE id=?", String.class, REVIEW))
                .isEqualTo("PROVIDER_CREATE_REJECTED");
        assertThat(jdbc.queryForObject("SELECT evidence->0->>'operation_id' FROM payment_review_cases WHERE id=?", String.class, REVIEW))
                .isEqualTo(OP);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_status_history", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isZero();

        InitiatePaymentResult replay = service.initiate(command(1), "api-2");

        assertThat(replay).isInstanceOf(InitiatePaymentResult.ProviderRejected.class);
        assertThat(provider.calls).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_review_cases", Integer.class)).isEqualTo(1);
    }

    @Test
    void retry_after_ambiguous_response_reuses_the_same_operation_and_original_expiry() {
        MutableClock clock = new MutableClock(NOW);
        SequencedProvider provider = new SequencedProvider();
        InitiatePaymentService service = service(provider, clock);

        assertThat(service.initiate(command(1), "api-1"))
                .isInstanceOf(InitiatePaymentResult.ProviderOutcomeUnknown.class);
        clock.now = NOW.plusSeconds(31);
        InitiatePaymentResult recovered = service.initiate(command(1), "api-2");

        assertThat(recovered).isInstanceOf(InitiatePaymentResult.Pending.class);
        assertThat(((InitiatePaymentResult.Pending) recovered).expiresAt())
                .isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(provider.calls).isEqualTo(2);
        assertThat(provider.firstKey).isEqualTo(provider.secondKey).isEqualTo("pay:create:" + TXN);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM provider_operations", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT lease_token FROM provider_operations WHERE id=?", Long.class, OP))
                .isEqualTo(1L);
    }

    @Test
    void retry_after_safety_horizon_searches_before_any_recreate() {
        MutableClock clock = new MutableClock(NOW);
        SearchRecoveryProvider provider = new SearchRecoveryProvider();
        InitiatePaymentService service = service(provider, clock);

        assertThat(service.initiate(command(1), "api-1"))
                .isInstanceOf(InitiatePaymentResult.ProviderOutcomeUnknown.class);
        clock.now = NOW.plus(Duration.ofHours(23)).plusSeconds(1);
        InitiatePaymentResult recovered = service.initiate(command(1), "api-2");

        assertThat(recovered).isInstanceOf(InitiatePaymentResult.Pending.class);
        assertThat(provider.dispatchCalls).isEqualTo(1);
        assertThat(provider.searchCalls).isEqualTo(1);
    }

    @Test
    void invalid_first_attempt_rolls_back_all_of_tx1() {
        InitiatePaymentResult result = service(new CapturingProvider(
                new ProviderOperationResult.Unknown(null, "{}")))
                .initiate(command(2), "api-1");

        assertThat(result).isInstanceOf(InitiatePaymentResult.InvalidAttemptSequence.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_idempotency", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM provider_operations", Integer.class)).isZero();
    }

    private InitiatePaymentService service(PaymentProviderPort provider) {
        return service(provider, new FixedClock());
    }

    private InitiatePaymentService service(PaymentProviderPort provider, ClockPort clock) {
        return new InitiatePaymentService(
                initiatePayments, providerOperations, provider,
                new LocalAesGcmSecretCipher("local-v1", LOCAL_KEY, Map.of()),
                new FixedIds(), clock, new CanonicalFingerprint(),
                new PaymentStateMachine(), "pk_test_documented",
                Duration.ofMinutes(15), Duration.ofSeconds(30), Duration.ofSeconds(45),
                Duration.ofHours(23), Duration.ofHours(25), Duration.ofSeconds(30));
    }

    private static InitiatePaymentCommand command(int attempt) {
        return new InitiatePaymentCommand(KEY, 10001, attempt, new Money(995000, "VND"),
                UUID.fromString("6f4c2b1a-0000-4000-8000-000000000001"), null,
                "order-checkout-8c1d");
    }

    private String allDurableJson() {
        return jdbc.queryForObject("""
                SELECT concat_ws('|',
                    (SELECT response_body::text FROM payment_idempotency LIMIT 1),
                    (SELECT provider_result::text FROM provider_operations LIMIT 1),
                    (SELECT provider_response::text FROM payment_status_history LIMIT 1),
                    (SELECT payload::text FROM outbox_events LIMIT 1))
                """, String.class);
    }

    private static final class FixedClock implements ClockPort {
        @Override public Instant now() { return NOW; }
    }

    private static final class MutableClock implements ClockPort {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        @Override public Instant now() { return now; }
    }

    private static final class FixedIds implements IdGeneratorPort {
        @Override public String nextPaymentTransactionId() { return TXN; }
        @Override public String nextProviderOperationId() { return OP; }
        @Override public String nextReviewCaseId() { return REVIEW; }
        @Override public UUID nextEventId() { return EVENT; }
    }

    private static final class CapturingProvider implements PaymentProviderPort {
        private final ProviderOperationResult result;
        private int calls;
        private boolean transactionWasActive;
        private ProviderOperation operation;

        private CapturingProvider(ProviderOperationResult result) {
            this.result = result;
        }

        @Override
        public ProviderOperationResult dispatch(ProviderOperation operation) {
            calls++;
            this.operation = operation;
            transactionWasActive = TransactionSynchronizationManager.isActualTransactionActive();
            return result;
        }

        @Override
        public ProviderOperationResult searchBeforeAnyRecreate(ProviderOperation operation) {
            throw new AssertionError("initial initiate must not search");
        }
    }

    private static final class SequencedProvider implements PaymentProviderPort {
        private int calls;
        private String firstKey;
        private String secondKey;

        @Override
        public ProviderOperationResult dispatch(ProviderOperation operation) {
            calls++;
            if (calls == 1) {
                firstKey = operation.providerIdempotencyKey();
                return new ProviderOperationResult.Unknown("req_lost", "{\"error_code\":\"TIMEOUT\"}");
            }
            secondKey = operation.providerIdempotencyKey();
            return new ProviderOperationResult.Confirmed(
                    "pi_recovered", "req_recovered",
                    "{\"id\":\"pi_recovered\",\"status\":\"requires_payment_method\"}",
                    "pi_recovered_secret".toCharArray());
        }

        @Override
        public ProviderOperationResult searchBeforeAnyRecreate(ProviderOperation operation) {
            throw new AssertionError("recovery is still inside the 23-hour safety horizon");
        }
    }

    private static final class SearchRecoveryProvider implements PaymentProviderPort {
        private int dispatchCalls;
        private int searchCalls;

        @Override
        public ProviderOperationResult dispatch(ProviderOperation operation) {
            dispatchCalls++;
            return new ProviderOperationResult.Unknown("req_lost", "{\"error_code\":\"TIMEOUT\"}");
        }

        @Override
        public ProviderOperationResult searchBeforeAnyRecreate(ProviderOperation operation) {
            searchCalls++;
            return new ProviderOperationResult.Confirmed(
                    "pi_found", "req_search",
                    "{\"id\":\"pi_found\",\"status\":\"requires_payment_method\"}",
                    "pi_found_secret".toCharArray());
        }
    }
}
