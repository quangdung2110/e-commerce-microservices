package com.platform.inventoryservice.infrastructure.event;

import com.github.f4b6a3.ulid.UlidCreator;
import com.platform.inventoryservice.domain.event.ReturnReceivedAllPayload;
import com.platform.inventoryservice.domain.event.ShipToCustomerPayload;
import com.platform.inventoryservice.domain.event.StockChangedPayload;
import com.platform.inventoryservice.domain.event.StockMutationPayload;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class InventoryEventBuilder {

    public CloudEventImpl<StockMutationPayload> buildStockMutationEvent(StockMutationPayload payload) {
        return buildEvent("INVENTORY_STOCK_MUTATION", payload.skuId(), payload.correlationId(), payload);
    }

    public CloudEventImpl<StockChangedPayload> buildStockChangedEvent(StockChangedPayload payload, String correlationId) {
        return buildEvent("INVENTORY_STOCK_CHANGED", payload.skuId(), correlationId, payload);
    }

    public CloudEventImpl<ReturnReceivedAllPayload> buildReturnReceivedAllEvent(ReturnReceivedAllPayload payload, String correlationId) {
        return buildEvent("INVENTORY_RETURN_RECEIVED_ALL", String.valueOf(payload.orderId()), correlationId, payload);
    }

    public CloudEventImpl<ShipToCustomerPayload> buildShipToCustomerEvent(ShipToCustomerPayload payload, String correlationId) {
        return buildEvent("INVENTORY_SHIP_TO_CUSTOMER", String.valueOf(payload.orderId()), correlationId, payload);
    }

    private <P> CloudEventImpl<P> buildEvent(String eventType, String aggregateId, String correlationId, P payload) {
        return new CloudEventImpl<>(
                generateEventId(),
                eventType,
                SourceConstants.AGGREGATE_TYPE,
                aggregateId,
                correlationId,
                Instant.now(),
                SourceConstants.SERVICE_SOURCE,
                SourceConstants.SCHEMA_VERSION,
                payload
        );
    }

    private UUID generateEventId() {
        return UlidCreator.getMonotonicUlid().toUuid();
    }
}
