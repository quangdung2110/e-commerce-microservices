package com.ecommerce.shipping.infrastructure.worker;

import com.ecommerce.shipping.application.port.out.OutboxRepositoryPort;
import com.ecommerce.shipping.domain.model.OutboxEvent;
import com.ecommerce.shipping.infrastructure.messaging.kafka.ShippingOutboxPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

// Theo thiết kế shipping_workers.md mục 1: polling mỗi 3 giây.
@Component
public class OutboxPollingWorker {

    private final OutboxRepositoryPort outboxRepositoryPort;
    private final ShippingOutboxPublisher shippingOutboxPublisher;

    public OutboxPollingWorker(OutboxRepositoryPort outboxRepositoryPort,
                               ShippingOutboxPublisher shippingOutboxPublisher) {
        this.outboxRepositoryPort = outboxRepositoryPort;
        this.shippingOutboxPublisher = shippingOutboxPublisher;
    }

    @Scheduled(fixedDelayString = "${shipping.workers.outbox.fixed-delay-ms:3000}")
    void pollAndPublish() {
        List<OutboxEvent> pendingEvents = outboxRepositoryPort.pollPending(100);
        for (OutboxEvent event : pendingEvents) {
            try {
                shippingOutboxPublisher.publish(event);
                outboxRepositoryPort.markPublished(event.id(), OffsetDateTime.now());
            } catch (Exception publishError) {
                int nextRetryCount = event.retryCount() + 1;
                if (nextRetryCount >= event.maxRetries()) {
                    outboxRepositoryPort.markDead(event.id(), nextRetryCount);
                } else {
                    outboxRepositoryPort.markFailed(event.id(), nextRetryCount, OffsetDateTime.now().plusSeconds(10));
                }
            }
        }
    }
}
