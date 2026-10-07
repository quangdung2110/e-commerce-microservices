package com.ecommerce.order.service;

import com.ecommerce.order.common.CanonicalJson;
import com.ecommerce.order.common.Ids;
import com.ecommerce.order.common.Json;
import com.ecommerce.order.config.OrderProperties;
import com.ecommerce.order.store.OutboxStore;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Writes Order integration facts and Notification commands to the multi-destination
 * outbox inside the caller's transaction (INV-ORD-007/031, ADR-016). The caller holds
 * the order row lock, so both per-order sequences advance without gaps or reuse.
 */
@Component
public class OutboxWriter {

    public static final String SOURCE = "/services/order";
    public static final String ORDER_PAID = "com.ecommerce.order.paid.v1";
    public static final String ORDER_COMPLETED = "com.ecommerce.order.completed.v1";
    public static final String NOTIFICATION_REQUESTED = "com.ecommerce.notification.requested.v1";

    private static final Logger log = LoggerFactory.getLogger(OutboxWriter.class);

    private static final Map<String, String> FACT_SCHEMAS = Map.of(
            ORDER_PAID, "/contracts/events/order/v1/schemas/order-paid.schema.json",
            ORDER_COMPLETED, "/contracts/events/order/v1/schemas/order-completed.schema.json");

    private final OutboxStore outbox;
    private final NamedParameterJdbcTemplate jdbc;
    private final Json json;
    private final OrderProperties.Kafka topics;
    private final MeterRegistry meters;

    public OutboxWriter(OutboxStore outbox, NamedParameterJdbcTemplate jdbc, Json json, OrderProperties properties,
                        MeterRegistry meters) {
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.json = json;
        this.topics = properties.kafka();
        this.meters = meters;
    }

    /** Appends one of the two registered Order facts and advances the integration sequence. */
    public UUID appendFact(long orderId, String type, ObjectNode data, Instant time) {
        String schema = FACT_SCHEMAS.get(type);
        if (schema == null) {
            throw new IllegalArgumentException("Unregistered Order fact " + type);
        }
        long sequence = next(orderId, "last_event_sequence_id");
        UUID eventId = Ids.newUuid();
        ObjectNode event = envelope(eventId, type, schema, orderId, time, sequence);
        event.set("data", data);
        outbox.append(eventId, Ids.wire(orderId), "INTEGRATION_FACT", topics.orderTopic(), type, Ids.wire(orderId),
                null, json.write(event));
        return eventId;
    }

    /**
     * Requests one notification. The durable ledger returns the original identity for a
     * replayed dedup key and never advances the notification sequence twice.
     */
    public void requestNotification(long orderId, UUID userId, String template, String dedupKey,
                                    ObjectNode templateData, Instant time) {
        ObjectNode business = json.object();
        business.put("template", template);
        business.put("user_id", userId.toString());
        business.put("dedup_key", dedupKey);
        business.set("template_data", templateData);
        String digest = CanonicalJson.digest(business);
        Optional<OutboxStore.LedgerRow> existing = outbox.ledger(orderId, dedupKey);
        if (existing.isPresent()) {
            if (!existing.get().payloadSha256().equals(digest)) {
                meters.counter("order.notification.dedup_collision", "template", template).increment();
                log.error("Notification dedup collision quarantined order={} dedup_key={}", orderId, dedupKey);
            }
            return;
        }
        long sequence = next(orderId, "last_notification_sequence_id");
        UUID eventId = Ids.newUuid();
        ObjectNode event = envelope(eventId, NOTIFICATION_REQUESTED,
                "/contracts/events/notification/v1/schemas/notification-requested.schema.json", orderId, time, sequence);
        ObjectNode data = event.putObject("data");
        data.put("template", template);
        data.put("user_id", userId.toString());
        data.put("dedup_key", dedupKey);
        data.put("requested_at", time.toString());
        data.set("template_data", templateData);
        String payload = json.write(event);
        outbox.insertLedger(orderId, dedupKey, sequence, eventId, template, payload, digest);
        outbox.append(eventId, Ids.wire(orderId), "NOTIFICATION_COMMAND", topics.notificationTopic(),
                NOTIFICATION_REQUESTED, "orders/" + orderId, dedupKey, payload);
    }

    private ObjectNode envelope(UUID eventId, String type, String schema, long orderId, Instant time, long sequence) {
        ObjectNode event = json.object();
        event.put("specversion", "1.0");
        event.put("id", eventId.toString());
        event.put("type", type);
        event.put("source", SOURCE);
        event.put("subject", "orders/" + orderId);
        event.put("time", time.toString());
        event.put("datacontenttype", "application/json");
        event.put("dataschema", schema);
        event.put("correlationid", "order-" + orderId);
        event.put("sequence", sequence);
        return event;
    }

    private long next(long orderId, String column) {
        Long value = jdbc.queryForObject("UPDATE orders SET " + column + " = " + column + " + 1 WHERE id = :id RETURNING "
                + column, Map.of("id", orderId), Long.class);
        if (value == null) {
            throw new IllegalStateException("Order " + orderId + " not found for sequence " + column);
        }
        return value;
    }
}
