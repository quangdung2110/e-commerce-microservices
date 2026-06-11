package com.platform.productservice.infrastructure.redis;

import org.springframework.stereotype.Component;

/**
 * Builds consistent Redis key strings for the Product Service.
 * All keys follow the pattern: product:{resource}:{identifier}
 */
@Component
public class RedisKeyBuilder {

    private static final String KEY_PREFIX = "product";

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(":");
            }
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    /**
     * Returns the SKU cache key: product:sku:{skuId}
     */
    public String sku(String skuId) {
        return join(KEY_PREFIX, "sku", skuId);
    }

    /**
     * Returns the active markdown cache key: product:markdown:active:{skuId}
     */
    public String markdownActive(String skuId) {
        return join(KEY_PREFIX, "markdown", "active", skuId);
    }

    /**
     * Returns the inventory pending sync ZSET key: product:inventory:pending
     */
    public String inventoryPending() {
        return join(KEY_PREFIX, "inventory", "pending");
    }

    /**
     * Returns the idempotency key: product:idemp:{type}:{id}
     */
    public String idempotencyKey(String type, String id) {
        return join(KEY_PREFIX, "idemp", type, id);
    }

    /**
     * Returns the distributed lock key: product:lock:{resource}
     */
    public String distributedLock(String resource) {
        return join(KEY_PREFIX, "lock", resource);
    }

    /**
     * Returns the distributed lock key with instance ID: product:lock:{resource}:{instanceId}
     */
    public String distributedLock(String resource, String instanceId) {
        return join(KEY_PREFIX, "lock", resource, instanceId);
    }

    /**
     * Returns the markdown start gate ZSET key: product:markdown:gate:{ruleId}
     * ZSET for managing markdown start-time gates.
     */
    public String markdownStartGate(String ruleId) {
        return join(KEY_PREFIX, "markdown", "gate", ruleId);
    }

    /**
     * Returns the ZSET member format for inventory sync: {skuId}:{timestamp}
     */
    public String pendingMember(String skuId, long timestamp) {
        return skuId + ":" + timestamp;
    }
}
