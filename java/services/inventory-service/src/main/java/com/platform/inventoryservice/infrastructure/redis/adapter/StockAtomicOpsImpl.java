package com.platform.inventoryservice.infrastructure.redis.adapter;

import com.platform.inventoryservice.domain.enums.ChangeType;
import com.platform.inventoryservice.domain.enums.LuaReturnCode;
import com.platform.inventoryservice.domain.event.StockMutationPayload;
import com.platform.inventoryservice.domain.event.StockMutationPayloadBuilder;
import com.platform.inventoryservice.domain.vo.SkuWarehouseKey;
import com.platform.inventoryservice.domain.vo.StockHash;
import com.platform.inventoryservice.infrastructure.redis.IdempotencyKeyBuilderImpl;
import com.platform.inventoryservice.infrastructure.event.EventSerializer;
import com.platform.inventoryservice.infrastructure.redis.key.RedisKeyBuilder;
import com.platform.inventoryservice.infrastructure.redis.script.LuaScriptRegistry;
import com.platform.inventoryservice.port.outbound.cache.StockAtomicOps;
import com.platform.inventoryservice.port.outbound.cache.StockMutationResult;
import com.platform.inventoryservice.port.outbound.metrics.InventoryMetrics;
import com.platform.inventoryservice.config.InventoryProperties;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class StockAtomicOpsImpl implements StockAtomicOps {

    private final StringRedisTemplate redis;
    private final LuaScriptRegistry scripts;
    private final RedisKeyBuilder keys;
    private final StockMutationPayloadBuilder payloadBuilder;
    private final InventoryMetrics metrics;
    private final long idempTtlSec;

    public StockAtomicOpsImpl(StringRedisTemplate redis, LuaScriptRegistry scripts, 
                              RedisKeyBuilder keys, StockMutationPayloadBuilder payloadBuilder, 
                              InventoryMetrics metrics, InventoryProperties props) {
        this.redis = redis;
        this.scripts = scripts;
        this.keys = keys;
        this.payloadBuilder = payloadBuilder;
        this.metrics = metrics;
        this.idempTtlSec = props.getRedis().getIdempotencyTtlDays() * 24L * 60 * 60;
    }

    @Override
    public StockMutationResult reserve(SkuWarehouseKey key, long orderId, long orderItemId,
                                       String productId, int quantity, String correlationId) {
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");

        StockMutationPayload payload = payloadBuilder.forMutation(
            key.skuId(), productId, key.warehouseId(),
            "ORDER_RESERVED", quantity, orderId, orderItemId, correlationId);

        List<String> scriptKeys = List.of(
            keys.stockKey(key),
            keys.idempotencyKey(IdempotencyKeyBuilderImpl.OP_RESERVE, key, orderId),
            keys.streamKey(key),
            keys.suspensionKey(key)
        );
        Object[] args = {
            String.valueOf(quantity),
            String.valueOf(System.currentTimeMillis()),
            String.valueOf(idempTtlSec),
            EventSerializer.toJson(payload)
        };

        List<Object> raw = executeScript(scripts.reserve(), scriptKeys, args, "reserve");

        LuaReturnCode code = LuaReturnCode.fromArray(raw);
        StockHash stock = readStockHash(key);
        return new StockMutationResult(code, stock.available(), stock.reserved());
    }

    @Override
    public StockMutationResult release(SkuWarehouseKey key, long orderId, long orderItemId,
                                       String productId, int quantity, String correlationId,
                                       ChangeType changeType) {
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");

        StockMutationPayload payload = payloadBuilder.forMutation(
            key.skuId(), productId, key.warehouseId(),
            changeType.name(), quantity, orderId, orderItemId, correlationId);

        List<String> scriptKeys = List.of(
            keys.stockKey(key),
            keys.idempotencyKey(IdempotencyKeyBuilderImpl.OP_RELEASE, key, orderId),
            keys.streamKey(key),
            keys.suspensionKey(key)
        );
        Object[] args = {
            String.valueOf(quantity),
            String.valueOf(System.currentTimeMillis()),
            String.valueOf(idempTtlSec),
            EventSerializer.toJson(payload),
            changeType.name()                       // ARGV[5] for release script
        };

        List<Object> raw = executeScript(scripts.release(), scriptKeys, args, "release");

        LuaReturnCode code = LuaReturnCode.fromArray(raw);
        StockHash stock = readStockHash(key);
        return new StockMutationResult(code, stock.available(), stock.reserved());
    }

    @Override
    public StockMutationResult deduct(SkuWarehouseKey key, long orderId, long orderItemId,
                                      String productId, int quantity, String correlationId) {
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");

        StockMutationPayload payload = payloadBuilder.forMutation(
            key.skuId(), productId, key.warehouseId(),
            "ORDER_DEDUCTED", quantity, orderId, orderItemId, correlationId);

        List<String> scriptKeys = List.of(
            keys.stockKey(key),
            keys.idempotencyKey(IdempotencyKeyBuilderImpl.OP_DEDUCT, key, orderId),
            keys.streamKey(key),
            keys.suspensionKey(key)
        );
        Object[] args = {
            String.valueOf(quantity),
            String.valueOf(System.currentTimeMillis()),
            String.valueOf(idempTtlSec),
            EventSerializer.toJson(payload)
        };

        List<Object> raw = executeScript(scripts.deduct(), scriptKeys, args, "deduct");

        LuaReturnCode code = LuaReturnCode.fromArray(raw);
        StockHash stock = readStockHash(key);
        return new StockMutationResult(code, stock.available(), stock.reserved());
    }

    // -- helpers --

    private List<Object> executeScript(
        DefaultRedisScript<List> script,
        List<String> scriptKeys,
        Object[] args,
        String operationTag
    ) {
        long start = System.nanoTime();
        try {
            @SuppressWarnings("unchecked")
            List<Object> result = redis.execute(script, scriptKeys, args);
            metrics.recordLuaInvocation(operationTag, "success");
            return result;
        } catch (RedisConnectionFailureException ex) {
            metrics.recordLuaInvocation(operationTag, "connection_error");
            throw new RuntimeException("Redis unavailable", ex); // InventoryUnavailableException chưa có, dùng RuntimeException tạm
        } finally {
            metrics.recordLuaDuration(operationTag, System.nanoTime() - start);
        }
    }

    private StockHash readStockHash(SkuWarehouseKey key) {
        Map<Object, Object> entries = redis.opsForHash().entries(keys.stockKey(key));
        long total = parseLong(entries.get("total"));
        long reserved = parseLong(entries.get("reserved"));
        long available = parseLong(entries.get("available"));
        long updatedAt = parseLong(entries.get("updated_at_ms"));
        return new StockHash(total, reserved, available, updatedAt);
    }

    private long parseLong(Object v) {
        if (v == null) return 0;
        try { return Long.parseLong(v.toString()); } catch (NumberFormatException e) { return 0; }
    }
}
