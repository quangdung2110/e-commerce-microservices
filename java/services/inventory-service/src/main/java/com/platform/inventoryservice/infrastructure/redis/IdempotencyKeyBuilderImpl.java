package com.platform.inventoryservice.infrastructure.redis;

import com.platform.inventoryservice.port.outbound.cache.IdempotencyKeyBuilder;
import org.springframework.stereotype.Component;

@Component
public class IdempotencyKeyBuilderImpl implements IdempotencyKeyBuilder {

    public static final String OP_RESERVE = "reserve";
    public static final String OP_RELEASE = "release";
    public static final String OP_DEDUCT  = "deduct";

    /** Build full key dùng cho cả Redis (key) và DB column. */
    @Override
    public String build(String op, String skuId, long warehouseId, long orderId) {
        if (!op.equals(OP_RESERVE) && !op.equals(OP_RELEASE) && !op.equals(OP_DEDUCT)) {
            throw new IllegalArgumentException("Unknown op: " + op);
        }
        return op + ":" + skuId + ":" + warehouseId + ":" + orderId;
    }
}
