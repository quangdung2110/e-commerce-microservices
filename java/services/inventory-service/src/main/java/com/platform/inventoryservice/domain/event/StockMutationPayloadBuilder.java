package com.platform.inventoryservice.domain.event;

public interface StockMutationPayloadBuilder {
    StockMutationPayload forMutation(String skuId, String productId, long warehouseId, 
                                     String changeType, int quantity, 
                                     long orderId, long orderItemId, String correlationId);
}
