package com.platform.paymentservice.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class PaymentTransaction {
    private final String id;
    private final long orderId;
    private final int attemptNumber;
    private final UUID userId;
    private final Money amount;
    private final Instant expiresAt;
    private PaymentStatus status;
    private long financialStateVersion;

    public PaymentTransaction(String id, long orderId, int attemptNumber, UUID userId,
                              Money amount, Instant expiresAt, PaymentStatus status,
                              long financialStateVersion) {
        if (id == null || !id.startsWith("TXN_")) throw new IllegalArgumentException("invalid payment id");
        if (orderId <= 0 || attemptNumber < 1) throw new IllegalArgumentException("invalid order attempt");
        if (financialStateVersion < 0) throw new IllegalArgumentException("invalid financial version");
        this.id = id;
        this.orderId = orderId;
        this.attemptNumber = attemptNumber;
        this.userId = Objects.requireNonNull(userId);
        this.amount = Objects.requireNonNull(amount);
        this.expiresAt = Objects.requireNonNull(expiresAt);
        this.status = Objects.requireNonNull(status);
        this.financialStateVersion = financialStateVersion;
    }

    public PaymentTransition apply(PaymentStateMachine machine, PaymentTrigger trigger) {
        PaymentTransition transition = machine.transition(status, trigger);
        status = transition.to();
        if (transition.financial()) financialStateVersion++;
        return transition;
    }

    public String id() { return id; }
    public long orderId() { return orderId; }
    public int attemptNumber() { return attemptNumber; }
    public UUID userId() { return userId; }
    public Money amount() { return amount; }
    public Instant expiresAt() { return expiresAt; }
    public PaymentStatus status() { return status; }
    public long financialStateVersion() { return financialStateVersion; }
}
