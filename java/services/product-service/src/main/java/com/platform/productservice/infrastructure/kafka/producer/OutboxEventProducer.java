package com.platform.productservice.infrastructure.kafka.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.productservice.domain.event.DomainEvent;
import com.platform.productservice.infrastructure.kafka.deserializer.CloudEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class OutboxEventProducer {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventProducer.class);
    private static final String CORRELATION_ID_HEADER = "correlation_id";
    private static final String PARTITION_KEY_HEADER = "partition_key";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TopicRouter topicRouter;
    private final ObjectMapper objectMapper;

    public OutboxEventProducer(
            KafkaTemplate<String, String> kafkaTemplate,
            TopicRouter topicRouter,
            ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.topicRouter = topicRouter;
        this.objectMapper = objectMapper;
    }

    public void publish(DomainEvent event) {
        if (event == null) {
            log.warn("OutboxEventProducer: received null event, skipping");
            return;
        }

        String topic = topicRouter.routeTopic(event);
        String partitionKey = topicRouter.getPartitionKey(event);

        try {
            CloudEvent cloudEvent = buildCloudEvent(event);
            String payload = objectMapper.writeValueAsString(cloudEvent);

            ProducerRecord<String, String> record = new ProducerRecord<>(
                    topic,
                    partitionKey,
                    payload
            );

            addHeaders(record, partitionKey);

            kafkaTemplate.send(record)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("OutboxEventProducer: failed to send event {} to topic {}: {}",
                                    event.getEventType(), topic, ex.getMessage(), ex);
                        } else {
                            log.debug("OutboxEventProducer: sent event {} to topic {} partition {} offset {}",
                                    event.getEventType(),
                                    result.getRecordMetadata().topic(),
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        }
                    });
        } catch (JsonProcessingException e) {
            log.error("OutboxEventProducer: failed to serialize event {}: {}",
                    event.getEventType(), e.getMessage(), e);
        }
    }

    public CompletableFuture<SendResult<String, String>> publishAsync(DomainEvent event) {
        if (event == null) {
            log.warn("OutboxEventProducer: received null event, skipping");
            return CompletableFuture.failedFuture(new IllegalArgumentException("Event cannot be null"));
        }

        String topic = topicRouter.routeTopic(event);
        String partitionKey = topicRouter.getPartitionKey(event);

        try {
            CloudEvent cloudEvent = buildCloudEvent(event);
            String payload = objectMapper.writeValueAsString(cloudEvent);

            ProducerRecord<String, String> record = new ProducerRecord<>(
                    topic,
                    partitionKey,
                    payload
            );

            addHeaders(record, partitionKey);

            CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(record);

            future.whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("OutboxEventProducer: async send failed for event {} on topic {}: {}",
                            event.getEventType(), topic, ex.getMessage());
                } else {
                    log.debug("OutboxEventProducer: async send success for event {} on topic {} partition {}",
                            event.getEventType(),
                            result.getRecordMetadata().topic(),
                            result.getRecordMetadata().partition());
                }
            });

            return future;
        } catch (JsonProcessingException e) {
            log.error("OutboxEventProducer: failed to serialize event {}: {}",
                    event.getEventType(), e.getMessage(), e);
            return CompletableFuture.failedFuture(e);
        }
    }

    public void publishAll(List<DomainEvent> events) {
        if (events == null || events.isEmpty()) {
            log.debug("OutboxEventProducer: received empty event list, skipping");
            return;
        }

        for (DomainEvent event : events) {
            publish(event);
        }

        log.debug("OutboxEventProducer: initiated publish for {} events", events.size());
    }

    private CloudEvent buildCloudEvent(DomainEvent event) {
        return CloudEvent.fromDomainEvent(
                event.getEventId(),
                event.getEventType(),
                event.getAggregateType(),
                event.getAggregateId(),
                event.getPartitionKey(),
                UUID.randomUUID().toString(),
                event.getTimestamp(),
                event.getSource(),
                event.getSchemaVersion(),
                event.toCloudEventPayload()
        );
    }

    private void addHeaders(ProducerRecord<String, String> record, String partitionKey) {
        record.headers().add(new RecordHeader(
                CORRELATION_ID_HEADER,
                UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)
        ));
        if (partitionKey != null) {
            record.headers().add(new RecordHeader(
                    PARTITION_KEY_HEADER,
                    partitionKey.getBytes(StandardCharsets.UTF_8)
            ));
        }
    }
}
