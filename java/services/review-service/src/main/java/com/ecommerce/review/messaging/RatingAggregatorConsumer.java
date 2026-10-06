package com.ecommerce.review.messaging;

import com.ecommerce.review.common.RequestContext;
import com.ecommerce.review.outbox.ReviewEventType;
import com.ecommerce.review.service.RatingAggregateService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * WF-REVIEW-008: Review self-consuming its own lifecycle facts to move the rating
 * aggregate.
 *
 * <p>It handles exactly the five aggregate-affecting types. Everything else on the topic
 * is acknowledged without a durable effect — including {@code REVIEW_METRICS_UPDATED},
 * which this consumer produces and would otherwise feed back to itself, and both
 * notification requests, which belong to Notification.
 */
@Component
public class RatingAggregatorConsumer {

    private final InboundEvents inboundEvents;
    private final RatingAggregateService ratingAggregates;

    public RatingAggregatorConsumer(InboundEvents inboundEvents, RatingAggregateService ratingAggregates) {
        this.inboundEvents = inboundEvents;
        this.ratingAggregates = ratingAggregates;
    }

    @KafkaListener(
            // Disabled in the integration suite except in the CDC test, which is the
            // only one where a live consumer is the subject rather than background
            // noise writing into tables other tests are truncating.
            autoStartup = "${review.consumers.auto-startup:true}",
            topics = "${review.topics.review}",
            groupId = "${review.consumer-groups.rating}",
            containerFactory = "kafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        CloudEventEnvelope envelope = inboundEvents.parse(record.value());
        // Dispatch on the envelope type, never on the ce_type header: the headers are a
        // convenience mirror, and structured mode is what the contract makes normative.
        ReviewEventType type = ReviewEventType.fromType(envelope.type());
        if (type == null || !type.affectsRatingAggregate()) {
            acknowledgment.acknowledge();
            return;
        }
        RequestContext.setCorrelationId(envelope.correlationid());
        try {
            UUID eventId = inboundEvents.requireEventId(envelope);
            ratingAggregates.apply(eventId, type, envelope.data(),
                    record.topic(), record.partition(), record.offset());
            acknowledgment.acknowledge();
        } finally {
            RequestContext.clear();
        }
    }
}
