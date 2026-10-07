package com.platform.paymentservice.adapter.in.scheduler;

import com.platform.paymentservice.application.service.ProviderRecoveryService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
final class ProviderOperationRecoveryWorker {
    private final ObjectProvider<ProviderRecoveryService> recovery;
    private final int batchSize;

    ProviderOperationRecoveryWorker(ObjectProvider<ProviderRecoveryService> recovery,
                                    @Value("${payment.scheduler.recovery.batch-size:100}") int batchSize) {
        this.recovery = recovery;
        if (batchSize < 1 || batchSize > 1000)
            throw new IllegalArgumentException("provider recovery batch size must be 1..1000");
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${payment.scheduler.recovery.interval:PT30S}")
    void scan() {
        ProviderRecoveryService service = recovery.getIfAvailable();
        if (service != null) service.recover("provider-recovery:" + UUID.randomUUID(), batchSize);
    }
}
