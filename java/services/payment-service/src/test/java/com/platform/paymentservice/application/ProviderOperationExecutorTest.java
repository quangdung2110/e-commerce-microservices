package com.platform.paymentservice.application;

import com.platform.paymentservice.application.port.out.ClockPort;
import com.platform.paymentservice.application.port.out.PaymentProviderPort;
import com.platform.paymentservice.application.port.out.ProviderOperationClaim;
import com.platform.paymentservice.application.port.out.ProviderOperationPort;
import com.platform.paymentservice.application.port.out.ProviderOperationResult;
import com.platform.paymentservice.application.service.ProviderOperationExecutor;
import com.platform.paymentservice.domain.ProviderOperation;
import com.platform.paymentservice.domain.ProviderOperationStatus;
import com.platform.paymentservice.domain.ProviderOperationType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderOperationExecutorTest {
    private static final Instant NOW = Instant.parse("2026-09-22T10:00:00Z");

    @Test
    void persists_dispatches_and_binds_confirmed_result_with_fencing() {
        FakeOperations operations = new FakeOperations(operation(null, 0));
        RecordingProvider provider = new RecordingProvider(
                new ProviderOperationResult.Confirmed("pi_123", "req_123", "{\"status\":\"requires_payment_method\"}"));
        ProviderOperationExecutor.Execution execution = executor(operations, provider).execute(operations.operation, "worker-a");

        assertThat(execution.outcome()).isEqualTo(ProviderOperationExecutor.Outcome.CONFIRMED);
        assertThat(operations.markedDispatched).isTrue();
        assertThat(operations.finished).isTrue();
        assertThat(provider.dispatchCalls).isEqualTo(1);
        assertThat(provider.searchCalls).isZero();
    }

    @Test
    void searches_instead_of_recreating_after_the_safety_horizon() {
        FakeOperations operations = new FakeOperations(operation(NOW.minusSeconds(1), 1));
        RecordingProvider provider = new RecordingProvider(
                new ProviderOperationResult.Confirmed("pi_existing", "req_search", "{\"status\":\"succeeded\"}"));
        ProviderOperationExecutor.Execution execution = executor(operations, provider).execute(operations.operation, "worker-b");

        assertThat(execution.outcome()).isEqualTo(ProviderOperationExecutor.Outcome.CONFIRMED);
        assertThat(provider.dispatchCalls).isZero();
        assertThat(provider.searchCalls).isEqualTo(1);
    }

    @Test
    void exhausted_recovery_moves_to_manual_review_without_provider_call() {
        FakeOperations operations = new FakeOperations(operation(null, 20));
        RecordingProvider provider = new RecordingProvider(
                new ProviderOperationResult.Unknown(null, "{\"error_code\":\"TIMEOUT\"}"));
        ProviderOperationExecutor.Execution execution = executor(operations, provider).execute(operations.operation, "worker-c");

        assertThat(execution.outcome()).isEqualTo(ProviderOperationExecutor.Outcome.REVIEW_REQUIRED);
        assertThat(provider.dispatchCalls + provider.searchCalls).isZero();
        assertThat(operations.finishedResult).isInstanceOf(ProviderOperationResult.ReviewRequired.class);
    }

    @Test
    void refuses_a_safety_horizon_of_twenty_four_hours_or_more() {
        FakeOperations operations = new FakeOperations(operation(null, 0));
        RecordingProvider provider = new RecordingProvider(
                new ProviderOperationResult.Unknown(null, "{}"));
        assertThatThrownBy(() -> new ProviderOperationExecutor(operations, provider, () -> NOW,
                Duration.ofSeconds(45), Duration.ofHours(24), Duration.ofSeconds(30), 20))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ProviderOperationExecutor executor(FakeOperations operations, RecordingProvider provider) {
        ClockPort clock = () -> NOW;
        return new ProviderOperationExecutor(operations, provider, clock,
                Duration.ofSeconds(45), Duration.ofHours(23), Duration.ofSeconds(30), 20);
    }

    private static ProviderOperation operation(Instant safeReplayUntil, int attemptCount) {
        return new ProviderOperation("POP_01", "TXN_01", null, ProviderOperationType.CREATE,
                "pay:create:TXN_01", "command-01", "{\"amount_minor\":100}",
                ProviderOperationStatus.REQUESTED, null, attemptCount,
                safeReplayUntil == null ? null : NOW.minus(Duration.ofHours(23)), safeReplayUntil,
                NOW.plus(Duration.ofDays(1)), NOW);
    }

    private static final class FakeOperations implements ProviderOperationPort {
        private final ProviderOperation operation;
        private boolean markedDispatched;
        private boolean finished;
        private ProviderOperationResult finishedResult;

        private FakeOperations(ProviderOperation operation) { this.operation = operation; }

        @Override
        public ProviderOperationClaim createOrClaim(ProviderOperation ignored, String owner,
                                                    Instant now, Duration leaseTtl) {
            return new ProviderOperationClaim.Acquired(operation, 7);
        }

        @Override
        public boolean markDispatched(String operationId, String owner, long leaseToken,
                                      Instant dispatchedAt, Duration safeReplayHorizon) {
            markedDispatched = true;
            return leaseToken == 7;
        }

        @Override
        public boolean finish(String operationId, String owner, long leaseToken,
                              ProviderOperationResult result, Instant finishedAt, Instant nextRetryAt) {
            finished = true;
            finishedResult = result;
            return leaseToken == 7;
        }

        @Override
        public boolean heartbeat(String operationId, String owner, long leaseToken,
                                 Instant now, Duration leaseTtl) {
            return true;
        }
    }

    private static final class RecordingProvider implements PaymentProviderPort {
        private final ProviderOperationResult result;
        private int dispatchCalls;
        private int searchCalls;

        private RecordingProvider(ProviderOperationResult result) { this.result = result; }

        @Override
        public ProviderOperationResult dispatch(ProviderOperation operation) {
            dispatchCalls++;
            return result;
        }

        @Override
        public ProviderOperationResult searchBeforeAnyRecreate(ProviderOperation operation) {
            searchCalls++;
            return result;
        }
    }
}
