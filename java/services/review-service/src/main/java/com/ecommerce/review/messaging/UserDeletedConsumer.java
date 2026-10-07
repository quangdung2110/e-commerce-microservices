package com.ecommerce.review.messaging;

import com.ecommerce.review.common.RequestContext;
import com.ecommerce.review.service.PrivacyService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * WF-REVIEW-010: consumes {@code USER_DELETED} from {@code t.auth.events}.
 *
 * <p>A failure here must not be quietly dead-lettered. Auth publishes this event
 * directly after commit with at most three retries and no outbox, so it can already be
 * lost before Review sees it; losing it a second time on Review's side would leave a
 * legal deadline with nothing tracking it ({@code docs/review/operations.md 6}).
 */
@Component
public class UserDeletedConsumer {

    private static final Logger log = LoggerFactory.getLogger(UserDeletedConsumer.class);
    private static final String USER_DELETED = "com.ecommerce.auth.user-deleted.v1";

    private final InboundEvents inboundEvents;
    private final PrivacyService privacyService;

    public UserDeletedConsumer(InboundEvents inboundEvents, PrivacyService privacyService) {
        this.inboundEvents = inboundEvents;
        this.privacyService = privacyService;
    }

    @KafkaListener(
            // Disabled in the integration suite except in the CDC test, which is the
            // only one where a live consumer is the subject rather than background
            // noise writing into tables other tests are truncating.
            autoStartup = "${review.consumers.auto-startup:true}",
            topics = "${review.topics.auth}",
            groupId = "${review.consumer-groups.privacy}",
            containerFactory = "kafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        CloudEventEnvelope envelope = inboundEvents.parse(record.value());
        if (!USER_DELETED.equals(envelope.type())) {
            // Auth publishes its whole identity lifecycle here; Review answers to one type.
            acknowledgment.acknowledge();
            return;
        }
        RequestContext.setCorrelationId(envelope.correlationid());
        try {
            UUID eventId = inboundEvents.requireEventId(envelope);
            UserDeletedPayload payload = inboundEvents.bind(envelope, UserDeletedPayload.class);
            if (payload.userId() == null || payload.deletedAt() == null
                    || payload.hardDeleteAfter() == null || payload.reason() == null) {
                throw new InboundEvents.ContractViolationException(
                        "USER_DELETED is missing user_id, deleted_at, reason or hard_delete_after", null);
            }
            privacyService.applyUserDeleted(eventId, payload,
                    record.topic(), record.partition(), record.offset());
            acknowledgment.acknowledge();
        } catch (RuntimeException e) {
            log.error("privacy erasure failed for a USER_DELETED event; it must be resolved, not dropped", e);
            throw e;
        } finally {
            RequestContext.clear();
        }
    }
}
