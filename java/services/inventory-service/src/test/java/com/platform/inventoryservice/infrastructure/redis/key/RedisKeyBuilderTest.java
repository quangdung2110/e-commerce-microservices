package com.platform.inventoryservice.infrastructure.redis.key;

import com.platform.inventoryservice.domain.vo.SkuWarehouseKey;
import com.platform.inventoryservice.infrastructure.redis.IdempotencyKeyBuilderImpl;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedisKeyBuilderTest {

    private final RedisKeyBuilder keys = new RedisKeyBuilder();
    private final IdempotencyKeyBuilderImpl idempBuilder = new IdempotencyKeyBuilderImpl();

    @Test
    void allKeysForSameSkuWarehouse_shareSameClusterSlot() {
        SkuWarehouseKey k = new SkuWarehouseKey("sku-001", 5L);
        
        String stock = keys.stockKey(k);
        String idemp = keys.idempotencyKey("reserve", k, 10001);
        String stream = keys.streamKey(k);
        String suspend = keys.suspensionKey(k);

        // Verify they all contain the same hash tag
        assertThat(stock).contains("{sku-001:5}");
        assertThat(idemp).contains("{sku-001:5}");
        assertThat(stream).contains("{sku-001:5}");
        assertThat(suspend).contains("{sku-001:5}");
    }
}
