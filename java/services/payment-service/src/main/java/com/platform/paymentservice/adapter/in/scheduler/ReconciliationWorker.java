package com.platform.paymentservice.adapter.in.scheduler;

import com.platform.paymentservice.application.port.out.ClockPort;
import com.platform.paymentservice.application.port.out.ReconciliationPort;
import com.platform.paymentservice.application.service.ReconciliationService;
import com.platform.paymentservice.application.service.BalanceReconciliationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

@Component
final class ReconciliationWorker {
    private final ObjectProvider<ReconciliationService> reconciliation;
    private final ObjectProvider<BalanceReconciliationService> balance;
    private final ClockPort clock;
    private final ReconciliationPort controls;
    private final Duration lookback;
    private final boolean frozen;
    private final int batchSize;

    ReconciliationWorker(ObjectProvider<ReconciliationService> reconciliation,
                         ObjectProvider<BalanceReconciliationService> balance,
                         ReconciliationPort controls,
                         ClockPort clock,
                         @Value("${payment.reconciliation.lookback:PT65M}") Duration lookback,
                         @Value("${payment.reconciliation.auto-correction-frozen:false}") boolean frozen,
                         @Value("${payment.reconciliation.batch-size:500}") int batchSize) {
        this.reconciliation = reconciliation;
        this.balance = balance;
        this.controls = controls;
        this.clock = clock;
        if (lookback == null || lookback.isZero() || lookback.isNegative())
            throw new IllegalArgumentException("reconciliation lookback must be positive");
        if (batchSize < 1 || batchSize > 1000)
            throw new IllegalArgumentException("reconciliation batch size must be 1..1000");
        this.lookback = lookback;
        this.frozen = frozen;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${payment.reconciliation.interval:PT1H}")
    void reconcile() {
        ReconciliationService service = reconciliation.getIfAvailable();
        if (service == null) return;
        Instant to = clock.now();
        boolean effectiveFreeze = controls.currentFreeze(frozen);
        service.runObjectState(to.minus(lookback), to, effectiveFreeze, batchSize);
        BalanceReconciliationService balanceService = balance.getIfAvailable();
        if (balanceService != null)
            balanceService.run(to.minus(lookback), to, effectiveFreeze, batchSize);
    }
}
