package com.platform.paymentservice.adapter.in.scheduler;

import com.platform.paymentservice.application.service.RefundPollerService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
final class RefundStatusPoller {
    private final ObjectProvider<RefundPollerService> poller;
    private final int batchSize;

    RefundStatusPoller(ObjectProvider<RefundPollerService> poller,
                       @Value("${payment.scheduler.refund.batch-size:100}") int batchSize) {
        this.poller = poller;
        if (batchSize < 1 || batchSize > 1000)
            throw new IllegalArgumentException("refund poller batch size must be 1..1000");
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${payment.scheduler.refund.interval:PT10M}")
    void scan() {
        RefundPollerService service = poller.getIfAvailable();
        if (service != null) service.poll(batchSize);
    }
}
