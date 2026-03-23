package com.ecommerce.shipping.infrastructure.worker;

import com.ecommerce.shipping.application.port.out.OutboxRepositoryPort;
import com.ecommerce.shipping.domain.model.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

// Theo thiết kế shipping_workers.md mục 6: quét outbox DEAD mỗi 5 phút để alert.
@Component
public class DeadLetterHandlerWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(DeadLetterHandlerWorker.class);

    private final OutboxRepositoryPort outboxRepositoryPort;

    public DeadLetterHandlerWorker(OutboxRepositoryPort outboxRepositoryPort) {
        this.outboxRepositoryPort = outboxRepositoryPort;
    }

    @Scheduled(cron = "${shipping.workers.dead-letter.cron:0 */5 * * * *}")
    void scanDeadMessages() {
        List<OutboxEvent> deadEvents = outboxRepositoryPort.findDead(100);
        for (OutboxEvent event : deadEvents) {
            LOGGER.warn("Dead outbox event detected: id={}, type={}, aggregateId={}",
                    event.id(),
                    event.eventType(),
                    event.aggregateId());
        }
    }
}
