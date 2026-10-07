package com.platform.paymentservice.application.service;

import com.platform.paymentservice.application.port.out.CancelPaymentPort;
import com.platform.paymentservice.application.port.out.CancelPaymentPreparation;
import com.platform.paymentservice.application.port.out.ClockPort;
import com.platform.paymentservice.application.port.out.IdGeneratorPort;
import com.platform.paymentservice.domain.DeclinePolicy;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

public final class DeclinePaymentService {
    private final CancelPaymentPort persistence;
    private final CancelPaymentService cancels;
    private final IdGeneratorPort ids;
    private final ClockPort clock;
    private final DeclinePolicy policy;
    private final Duration leaseTtl;
    private final Duration recoveryWindow;

    public DeclinePaymentService(CancelPaymentPort persistence, CancelPaymentService cancels,
                                 IdGeneratorPort ids, ClockPort clock, DeclinePolicy policy,
                                 Duration leaseTtl, Duration recoveryWindow) {
        this.persistence = Objects.requireNonNull(persistence);
        this.cancels = Objects.requireNonNull(cancels);
        this.ids = Objects.requireNonNull(ids);
        this.clock = Objects.requireNonNull(clock);
        this.policy = Objects.requireNonNull(policy);
        this.leaseTtl = positive(leaseTtl, "leaseTtl");
        this.recoveryWindow = positive(recoveryWindow, "recoveryWindow");
    }

    public DeclinePaymentResult record(DeclinePaymentCommand command, String owner) {
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("owner is required");
        String normalizedCode = command.declineCode().toLowerCase(Locale.ROOT);
        boolean intrinsic = policy.mustTerminate(normalizedCode, command.outcomeType(), 1);
        CancelPaymentPreparation preparation = persistence.prepareDecline(
                new CancelPaymentPort.DeclineRecord(
                        command.transactionId(), normalizedCode, command.errorMessage(),
                        command.outcomeType(), intrinsic, policy.maximumDeclines(),
                        command.providerEventId(),
                        command.replayCauseRef() == null ? command.providerEventId() : command.replayCauseRef(),
                        command.replayActor() == null ? "WEBHOOK" : "MANUAL",
                        command.replayActor() == null ? "stripe" : command.replayActor(),
                        command.sanitizedProviderResultJson(),
                        command.inboxId(), command.inboxLeaseToken(),
                        command.providerEventCreatedAt()),
                ids.nextProviderOperationId(), owner, clock.now(), leaseTtl,
                clock.now().plus(recoveryWindow));
        if (preparation instanceof CancelPaymentPreparation.Ignored)
            return new DeclinePaymentResult.Ignored();
        if (preparation instanceof CancelPaymentPreparation.DeclineRecorded recorded) {
            return recorded.terminationRequired()
                    ? new DeclinePaymentResult.TerminationPending(recorded.declineCount())
                    : new DeclinePaymentResult.RetryableRecorded(recorded.declineCount());
        }
        return new DeclinePaymentResult.CancelResolution(
                cancels.executePrepared((CancelPaymentPreparation.Acquired) preparation, owner));
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
