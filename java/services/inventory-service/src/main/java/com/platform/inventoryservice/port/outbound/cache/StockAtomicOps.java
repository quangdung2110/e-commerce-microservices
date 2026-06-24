package com.platform.inventoryservice.port.outbound.cache;

import com.platform.inventoryservice.domain.enums.ChangeType;
import com.platform.inventoryservice.domain.vo.SkuWarehouseKey;

public interface StockAtomicOps {

    StockMutationResult reserve(SkuWarehouseKey key, long orderId, long orderItemId,
                                String productId, int quantity, String correlationId);

    StockMutationResult release(SkuWarehouseKey key, long orderId, long orderItemId,
                                String productId, int quantity, String correlationId,
                                ChangeType changeType);

    StockMutationResult deduct(SkuWarehouseKey key, long orderId, long orderItemId,
                               String productId, int quantity, String correlationId);
}
