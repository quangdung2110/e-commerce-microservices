package com.platform.inventoryservice.infrastructure.event;

import com.platform.inventoryservice.domain.event.StockMutationPayload;
import com.platform.inventoryservice.domain.event.StockMutationPayloadBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class StockMutationPayloadBuilderImpl implements StockMutationPayloadBuilder {

    private final InventoryEventBuilder eventBuilder;

    @Override
    public StockMutationPayload forMutation(String skuId, String productId, long warehouseId, String changeType, int quantity, long orderId, long orderItemId, String correlationId) {
        return new StockMutationPayload(
                skuId,
                productId,
                warehouseId,
                changeType,
                quantity,
                0, // availableAfter - dummy, Redis doesn't populate this initially
                0, // reservedAfter - dummy, Redis doesn't populate this initially
                orderId,
                orderItemId,
                correlationId
        );
    }

    public String buildCloudEventJson(StockMutationPayload payload) {
        CloudEventImpl<StockMutationPayload> cloudEvent = eventBuilder.buildStockMutationEvent(payload);
        return cloudEvent.toJsonString();
    }
}
