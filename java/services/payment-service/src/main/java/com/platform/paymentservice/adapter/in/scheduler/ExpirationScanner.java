package com.platform.paymentservice.adapter.in.scheduler;

import com.platform.paymentservice.application.service.CancelPaymentService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
final class ExpirationScanner {
    private final ObjectProvider<CancelPaymentService> cancels;
    private final int batchSize;

    ExpirationScanner(ObjectProvider<CancelPaymentService> cancels,
                      @Value("${payment.scheduler.expiration.batch-size:200}") int batchSize) {
        this.cancels = cancels;
        if (batchSize <= 0) throw new IllegalArgumentException("expiration batch size must be positive");
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${payment.scheduler.expiration.interval:PT1M}")
    void scan() {
        CancelPaymentService service = cancels.getIfAvailable();
        if (service != null) service.expireDue("expiration:" + UUID.randomUUID(), batchSize);
    }
}
