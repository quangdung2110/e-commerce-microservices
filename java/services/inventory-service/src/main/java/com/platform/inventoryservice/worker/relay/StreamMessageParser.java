package com.platform.inventoryservice.worker.relay;

import com.platform.inventoryservice.domain.event.StockMutationPayload;
import com.platform.inventoryservice.infrastructure.event.CloudEventImpl;
import com.platform.inventoryservice.infrastructure.event.EventSerializer;
import org.springframework.data.redis.connection.stream.MapRecord;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public class StreamMessageParser {

    private final MapRecord<String, Object, Object> msg;

    public StreamMessageParser(MapRecord<String, Object, Object> msg) {
        this.msg = msg;
    }

    public Parsed parse() {
        Map<Object, Object> body = msg.getValue();
        String eventType = (String) body.get("event_type");
        String changeType = (String) body.get("change_type");
        String payloadJson = (String) body.get("payload");

        StockMutationPayload payload = EventSerializer.fromJson(payloadJson, StockMutationPayload.class);
        // Use a deterministic UUID based on skuId and Redis Stream msg ID to ensure idempotency 
        // if RelayWorker crashes between Kafka publish and Redis ACK.
        String deterministicSeed = payload.skuId() + ":" + msg.getId().getValue();
        String eventId = java.util.UUID.nameUUIDFromBytes(deterministicSeed.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();

        return new Parsed(eventId, payload, changeType, eventType);
    }

    public record Parsed(
        String eventId,
        StockMutationPayload payload,
        String changeType,
        String eventType
    ) {
        public String partitionKey() {
            return payload.skuId();
        }

        public String buildCloudEventJson(Instant timestamp) {
            CloudEventImpl<StockMutationPayload> envelope = new CloudEventImpl<>(
                UUID.fromString(eventId),
                "INVENTORY_STOCK_MUTATION",
                "Inventory",
                payload.skuId(),
                UUID.randomUUID().toString(), // correlation - caller passes if available, or generate a new one
                timestamp,
                "inventory-service",
                1,
                payload
            );
            return envelope.toJsonString();
        }
    }
}
