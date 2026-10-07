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
import com.platform.paymentservice.application.service.ProviderRecoveryService;
import com.platform.paymentservice.domain.Money;
import com.platform.paymentservice.domain.PaymentStateMachine;
import com.platform.paymentservice.domain.ProviderOperation;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderRecoveryIT extends AbstractPaymentIT {
    private static final Instant START = Instant.parse("2026-09-22T10:00:00Z");
    private static final String TXN = "TXN_RECOVERY";
    private static final String OP = "POP_RECOVERY";
    private static final String KEY = "recovery-command-key-0001";

    @Test
    void recovery_binds_create_aggregate_history_outbox_and_command_atomically() {
        MutableClock clock = new MutableClock(START);
        FixedIds ids = new FixedIds();
        LocalAesGcmSecretCipher cipher = new LocalAesGcmSecretCipher(
                "local-v1", "0123456789abcdef0123456789abcdef"
                .getBytes(StandardCharsets.US_ASCII), java.util.Map.of());
        PaymentProviderPort timeout = new FixedProvider(new ProviderOperationResult.Unknown(
                "req-timeout", "{\"error_code\":\"TIMEOUT\"}"));
        InitiatePaymentService initiate = new InitiatePaymentService(initiatePayments,
                providerOperations, timeout, cipher, ids, clock, new CanonicalFingerprint(),
                new PaymentStateMachine(), "pk_test", Duration.ofMinutes(15),
                Duration.ofSeconds(30), Duration.ofSeconds(45), Duration.ofHours(23),
                Duration.ofDays(1), Duration.ofSeconds(30));
        InitiatePaymentCommand command = new InitiatePaymentCommand(KEY, 42, 1,
                new Money(1000, "VND"), UUID.fromString("00000000-0000-4000-8000-000000000042"),
                null, "corr-recovery");

        assertThat(initiate.initiate(command, "api-owner"))
                .isInstanceOf(InitiatePaymentResult.ProviderOutcomeUnknown.class);
        clock.now = START.plusSeconds(31);
        ProviderRecoveryService service = new ProviderRecoveryService(providerRecovery,
                providerOperations, new FixedProvider(new ProviderOperationResult.Confirmed(
                "pi_recovered", "req-recovered",
                "{\"id\":\"pi_recovered\",\"status\":\"requires_payment_method\"}",
                "pi_recovered_secret".toCharArray())), initiatePayments, cancelPayments,
                refundPayments, cipher, ids, clock, Duration.ofSeconds(45),
                Duration.ofHours(23), Duration.ofSeconds(30), Duration.ofMinutes(5),
                20, Duration.ofSeconds(30));

        assertThat(service.recover("recovery-owner", 10)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_transactions WHERE id=?",
                String.class, TXN)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT status FROM provider_operations WHERE id=?",
                String.class, OP)).isEqualTo("PROVIDER_CONFIRMED");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_idempotency WHERE idempotency_key=?",
                String.class, KEY)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT transition_id FROM payment_status_history WHERE payment_transaction_id=?",
                String.class, TXN)).isEqualTo("T0.1");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isEqualTo(1);
    }

    private static final class MutableClock implements ClockPort {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        @Override public Instant now() { return now; }
    }

    private static final class FixedIds implements IdGeneratorPort {
        private int event;
        @Override public String nextPaymentTransactionId() { return TXN; }
        @Override public String nextProviderOperationId() { return OP; }
        @Override public String nextReviewCaseId() { return "PRC_RECOVERY"; }
        @Override public String nextRefundTransactionId() { return "RFD_RECOVERY"; }
        @Override public String nextFinancialJournalId() { return "FJN_RECOVERY"; }
        @Override public UUID nextEventId() {
            return UUID.fromString(String.format("00000000-0000-4000-8000-%012d", ++event));
        }
    }

    private record FixedProvider(ProviderOperationResult result) implements PaymentProviderPort {
        @Override public ProviderOperationResult dispatch(ProviderOperation operation) { return result; }
        @Override public ProviderOperationResult searchBeforeAnyRecreate(ProviderOperation operation) { return result; }
    }
}
