package com.platform.inventoryservice.infrastructure.outbox;

import com.platform.inventoryservice.domain.entity.OutboxEvent;
import com.platform.inventoryservice.domain.enums.OutboxEventType;
import com.platform.inventoryservice.infrastructure.event.CloudEventImpl;
import com.platform.inventoryservice.port.outbound.database.OutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TransactionalOutboxWriter {

    private final OutboxRepository outboxRepository;

    /**
     * Insert outbox row trong transaction hiện tại của caller.
     * KHÔNG có @Transactional — phải join với transaction của mutation.
     */
    public void write(OutboxEvent event) {
        outboxRepository.insert(event);
    }

    /**
     * Convenience: build envelope từ CloudEvent rồi insert.
     * Caller vẫn phải ở trong transaction.
     */
    public void writeCloudEvent(CloudEventImpl<?> envelope, String payloadJson) {
        OutboxEvent event = OutboxEvent.of(
            envelope.eventId(),
            OutboxEventType.valueOf(envelope.eventType()),
            envelope.aggregateId(),
            envelope.aggregateId(),   // default partition_key = aggregate_id cho cold track
            payloadJson,
            null                       // refundRequestId filled by dedicated method
        );
        outboxRepository.insert(event);
    }

    public void writeReturnReceivedAll(
        long orderId, String refundRequestId, String payloadJson
    ) {
        OutboxEvent event = OutboxEvent.of(
            OutboxEventType.INVENTORY_RETURN_RECEIVED_ALL,
            String.valueOf(orderId),
            String.valueOf(orderId),
            payloadJson,
            refundRequestId
        );
        outboxRepository.insert(event);
    }
}
