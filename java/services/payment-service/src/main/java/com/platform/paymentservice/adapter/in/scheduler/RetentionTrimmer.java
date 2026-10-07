package com.platform.paymentservice.adapter.in.scheduler;

import com.platform.paymentservice.application.service.RetentionTrimmerService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
final class RetentionTrimmer {
    private final ObjectProvider<RetentionTrimmerService> trimmer;
    private final int batchSize;

    RetentionTrimmer(ObjectProvider<RetentionTrimmerService> trimmer,
                     @Value("${payment.retention.batch-size:5000}") int batchSize) {
        this.trimmer = trimmer;
        if (batchSize < 1 || batchSize > 5000)
            throw new IllegalArgumentException("retention batch size must be 1..5000");
        this.batchSize = batchSize;
    }

    @Scheduled(cron = "${payment.retention.cron:0 30 3 * * *}")
    void trim() {
        RetentionTrimmerService service = trimmer.getIfAvailable();
        if (service != null) service.trim(batchSize);
    }
}
