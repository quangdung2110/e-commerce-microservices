package com.platform.paymentservice.integration;

import com.platform.paymentservice.application.port.out.ClockPort;
import com.platform.paymentservice.application.port.out.PaymentProviderPort;
import com.platform.paymentservice.application.port.out.ProviderOperationClaim;
import com.platform.paymentservice.application.port.out.ProviderOperationResult;
import com.platform.paymentservice.application.service.ProviderOperationExecutor;
import com.platform.paymentservice.domain.ProviderOperation;
import com.platform.paymentservice.domain.ProviderOperationStatus;
import com.platform.paymentservice.domain.ProviderOperationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderOperationIT extends AbstractPaymentIT {
    private static final Instant START = Instant.parse("2026-09-22T10:00:00Z");

    @BeforeEach
    void seedPayment() {
        jdbc.update("""
                INSERT INTO payment_transactions
                  (id,order_id,payment_attempt_number,user_id,amount_minor,currency,status,expires_at)
                VALUES ('TXN_PROVIDER',20,1,?,100,'VND','INITIATING',now()+interval '15 minutes')
                """, UUID.randomUUID());
    }

    @Test
    void provider_call_is_outside_transactions_and_result_is_bound_in_tx2() {
        MutableClock clock = new MutableClock(START);
        TransactionCheckingProvider provider = new TransactionCheckingProvider(
                new ProviderOperationResult.Confirmed("pi_provider", "req_provider", "{\"status\":\"requires_payment_method\"}"));
        ProviderOperationExecutor.Execution execution = executor(clock, provider).execute(operation(), "worker-a");

        assertThat(execution.outcome()).isEqualTo(ProviderOperationExecutor.Outcome.CONFIRMED);
        assertThat(provider.transactionWasActive).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM provider_operations WHERE id='POP_PROVIDER'", String.class))
                .isEqualTo("PROVIDER_CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT provider_object_id FROM provider_operations WHERE id='POP_PROVIDER'", String.class))
                .isEqualTo("pi_provider");
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM provider_operations WHERE id='POP_PROVIDER'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT EXTRACT(EPOCH FROM (safe_replay_until - first_dispatched_at))::bigint
                  FROM provider_operations WHERE id='POP_PROVIDER'
                """, Long.class)).isEqualTo(Duration.ofHours(23).toSeconds());
    }

    @Test
    void expired_lease_takeover_fences_the_stale_owner() {
        ProviderOperation operation = operation();
        ProviderOperationClaim.Acquired first = (ProviderOperationClaim.Acquired) providerOperations.createOrClaim(
                operation, "worker-a", START, Duration.ofSeconds(45));
        ProviderOperationClaim.Acquired takeover = (ProviderOperationClaim.Acquired) providerOperations.createOrClaim(
                operation, "worker-b", START.plusSeconds(46), Duration.ofSeconds(45));

        ProviderOperationResult result = new ProviderOperationResult.Confirmed("pi_takeover", null, "{\"status\":\"succeeded\"}");
        assertThat(providerOperations.finish(operation.id(), "worker-a", first.leaseToken(), result,
                START.plusSeconds(47), null)).isFalse();
        assertThat(providerOperations.finish(operation.id(), "worker-b", takeover.leaseToken(), result,
                START.plusSeconds(47), null)).isTrue();
        assertThat(takeover.leaseToken()).isEqualTo(first.leaseToken() + 1);
    }

    @Test
    void unknown_before_horizon_replays_same_key_but_after_horizon_searches_first() {
        MutableClock clock = new MutableClock(START);
        SequencedProvider provider = new SequencedProvider();
        ProviderOperationExecutor executor = executor(clock, provider);
        assertThat(executor.execute(operation(), "worker-a").outcome())
                .isEqualTo(ProviderOperationExecutor.Outcome.UNKNOWN);
        assertThat(provider.dispatchCalls).isEqualTo(1);

        clock.now = START.plus(Duration.ofHours(24));
        assertThat(executor.execute(operation(), "worker-b").outcome())
                .isEqualTo(ProviderOperationExecutor.Outcome.REVIEW_REQUIRED);
        assertThat(provider.dispatchCalls).isEqualTo(1);
        assertThat(provider.searchCalls).isEqualTo(1);
    }

    private ProviderOperationExecutor executor(ClockPort clock, PaymentProviderPort provider) {
        return new ProviderOperationExecutor(providerOperations, provider, clock,
                Duration.ofSeconds(45), Duration.ofHours(23), Duration.ofSeconds(30), 20);
    }

    private ProviderOperation operation() {
        return new ProviderOperation("POP_PROVIDER", "TXN_PROVIDER", null,
                ProviderOperationType.CREATE, "pay:create:TXN_PROVIDER", "command-provider",
                "{\"amount_minor\":100,\"currency\":\"VND\"}", ProviderOperationStatus.REQUESTED,
                null, 0, null, null, START.plus(Duration.ofDays(7)), START);
    }

    private static final class MutableClock implements ClockPort {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        @Override public Instant now() { return now; }
    }

    private static final class TransactionCheckingProvider implements PaymentProviderPort {
        private final ProviderOperationResult result;
        private boolean transactionWasActive;
        private TransactionCheckingProvider(ProviderOperationResult result) { this.result = result; }
        @Override public ProviderOperationResult dispatch(ProviderOperation operation) {
            transactionWasActive = TransactionSynchronizationManager.isActualTransactionActive();
            return result;
        }
        @Override public ProviderOperationResult searchBeforeAnyRecreate(ProviderOperation operation) {
            transactionWasActive = TransactionSynchronizationManager.isActualTransactionActive();
            return result;
        }
    }

    private static final class SequencedProvider implements PaymentProviderPort {
        private int dispatchCalls;
        private int searchCalls;
        @Override public ProviderOperationResult dispatch(ProviderOperation operation) {
            dispatchCalls++;
            return new ProviderOperationResult.Unknown("req_timeout", "{\"error_code\":\"TIMEOUT\"}");
        }
        @Override public ProviderOperationResult searchBeforeAnyRecreate(ProviderOperation operation) {
            searchCalls++;
            return new ProviderOperationResult.ReviewRequired(null, "{\"error_code\":\"NO_UNIQUE_MATCH\"}");
        }
    }
}
