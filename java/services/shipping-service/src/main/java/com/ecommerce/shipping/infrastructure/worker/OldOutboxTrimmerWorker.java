package com.ecommerce.shipping.infrastructure.worker;

import com.ecommerce.shipping.application.port.out.OutboxRepositoryPort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

// Theo thiết kế shipping_workers.md mục 5: xóa outbox PUBLISHED cũ hơn 15 ngày.
@Component
public class OldOutboxTrimmerWorker {

    private final OutboxRepositoryPort outboxRepositoryPort;

    public OldOutboxTrimmerWorker(OutboxRepositoryPort outboxRepositoryPort) {
        this.outboxRepositoryPort = outboxRepositoryPort;
    }

    @Scheduled(cron = "${shipping.workers.outbox-trimmer.cron:0 15 3 * * *}")
    void trimPublishedOutbox() {
        outboxRepositoryPort.deletePublishedOlderThan(OffsetDateTime.now().minusDays(15));
    }
}
