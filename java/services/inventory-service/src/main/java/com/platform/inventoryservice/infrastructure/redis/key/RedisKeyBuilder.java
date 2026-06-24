package com.platform.inventoryservice.infrastructure.redis.key;

import com.platform.inventoryservice.domain.vo.SkuWarehouseKey;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class RedisKeyBuilder {

    /** Stock hash: inventory:physic:{sku:wh}:stock */
    public String stockKey(SkuWarehouseKey key) {
        return "inventory:physic:" + key.hashTag() + ":stock";
    }

    /** Idempotency key: inventory:idemp:{op}:{sku:wh}:order:{orderId} */
    public String idempotencyKey(String op, SkuWarehouseKey key, long orderId) {
        return "inventory:idemp:" + op + ":" + key.hashTag() + ":order:" + orderId;
    }

    /** Stream outbox: inventory:stream:{sku:wh}:events */
    public String streamKey(SkuWarehouseKey key) {
        return "inventory:stream:" + key.hashTag() + ":events";
    }

    /** Suspension flag: inventory:suspended:{sku:wh} */
    public String suspensionKey(SkuWarehouseKey key) {
        return "inventory:suspended:" + key.hashTag();
    }

    /** Route plan cache: route_plan:{routePlanId} */
    public String routePlanKey(UUID routePlanId) {
        return "route_plan:" + routePlanId;
    }
}
