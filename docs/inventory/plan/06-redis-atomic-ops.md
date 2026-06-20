# Module 06: Redis Atomic Operations

> **Depends on:** M03 (Redis Keys & Lua Scripts), M04 (Domain Model)
> **Depended by:** M09 (Relay Worker), M10 (Routing Engine), M11 (Temporal Activities), M12 (Admin Services)

---

## 1. Goal

Triển khai Redis adapter layer:

- **Lua script loader**: load 3 scripts 1 lần lúc startup, cache SHA1 qua `DefaultRedisScript`.
- **Key builder**: tập trung construction cho mọi key, đảm bảo hash-tag invariant (`{sku:wh}`).
- **Stock atomic ops**: invoke Lua scripts qua EVALSHA, map return code → domain result/exception.
- **Idempotency key builder**: build `{op}:{sku}:{wh}:{order}` cho cả Redis và DB column.
- **Suspension flag manager**: set/del `inventory:suspended:{sku:wh}` với TTL 24h.
- **Route plan cache**: SET/GET `route_plan:{id}` với TTL.

> Triển khai `port/outbound/cache/` interfaces (định nghĩa ở M04 surface, M06 implement).

---

## 2. Package Layout

```
com.platform.inventoryservice.infrastructure.redis/
├── config/
│   └── RedisConfig.java                # M01 đã có; M06 dùng các bean từ đó
├── script/
│   ├── LuaScriptLoader.java            # Load .lua files từ classpath
│   └── LuaScriptRegistry.java          # Cache DefaultRedisScript + SHA1
├── key/
│   └── RedisKeyBuilder.java
├── adapter/
│   ├── StockAtomicOpsImpl.java
│   ├── SuspensionFlagManagerImpl.java
│   └── RoutePlanCacheImpl.java
└── IdempotencyKeyBuilderImpl.java
```

---

## 3. Lua Script Loader

### 3.1 LuaScriptLoader

```java
@Component
public class LuaScriptLoader {

    private final ResourcePatternResolver resolver;

    public LuaScriptLoader(ResourcePatternResolver resolver) {
        this.resolver = resolver;
    }

    public String load(String classpathPath) {
        try {
            Resource resource = resolver.getResource("classpath:" + classpathPath);
            try (var in = resource.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load Lua script: " + classpathPath, e);
        }
    }
}
```

### 3.2 LuaScriptRegistry

```java
@Component
public class LuaScriptRegistry {

    private final RedisTemplate<String, String> redisTemplate;
    private final LuaScriptLoader loader;

    private DefaultRedisScript<List> reserveScript;
    private DefaultRedisScript<List> releaseScript;
    private DefaultRedisScript<List> deductScript;

    @PostConstruct
    void init() {
        reserveScript = load("lua/reserve_inventory.lua");
        releaseScript = load("lua/release_inventory.lua");
        deductScript  = load("lua/deduct_inventory.lua");
    }

    private DefaultRedisScript<List> load(String path) {
        String body = loader.load(path);
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptText(body);
        script.setResultType(List.class);
        return script;
    }

    public DefaultRedisScript<List> reserve() { return reserveScript; }
    public DefaultRedisScript<List> release() { return releaseScript; }
    public DefaultRedisScript<List> deduct()  { return deductScript; }
}
```

> `DefaultRedisScript` Spring Data Redis tự xử lý EVALSHA + fallback EVAL nếu NOSCRIPT.

---

## 4. RedisKeyBuilder

```java
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
```

> Mọi key construction **BẮT BUỘC** đi qua class này. KHÔNG dùng string concat trực tiếp.

---

## 5. IdempotencyKeyBuilder

```java
@Component
public class IdempotencyKeyBuilderImpl {

    public static final String OP_RESERVE = "reserve";
    public static final String OP_RELEASE = "release";
    public static final String OP_DEDUCT  = "deduct";

    /** Build full key dùng cho cả Redis (key) và DB column. */
    public String build(String op, String skuId, long warehouseId, long orderId) {
        if (!op.equals(OP_RESERVE) && !op.equals(OP_RELEASE) && !op.equals(OP_DEDUCT)) {
            throw new IllegalArgumentException("Unknown op: " + op);
        }
        return op + ":" + skuId + ":" + warehouseId + ":" + orderId;
    }
}
```

> Format khớp với `inventory_reservations.idempotency_key` column (M02): `{op}:{sku_id}:{warehouse_id}:{order_id}`.

---

## 6. StockAtomicOps

### 6.1 Port Interface

```java
package com.platform.inventoryservice.port.outbound.cache;

import com.platform.inventoryservice.domain.enums.LuaReturnCode;
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

public record StockMutationResult(
    LuaReturnCode returnCode,
    long availableAfter,
    long reservedAfter
) {}
```

### 6.2 Implementation

```java
@Component
@RequiredArgsConstructor
public class StockAtomicOpsImpl implements StockAtomicOps {

    private static final long IDEMP_TTL_SEC = 30L * 24 * 60 * 60;   // 30 days

    private final StringRedisTemplate redis;
    private final LuaScriptRegistry scripts;
    private final RedisKeyBuilder keys;
    private final StockMutationPayloadBuilder payloadBuilder;   // M07
    private final InventoryMetrics metrics;                     // M15

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
            String.valueOf(IDEMP_TTL_SEC),
            payload.toJsonString()
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
            String.valueOf(IDEMP_TTL_SEC),
            payload.toJsonString(),
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
            String.valueOf(IDEMP_TTL_SEC),
            payload.toJsonString()
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
            throw new InventoryUnavailableException("Redis unavailable", ex);
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
```

> `payloadBuilder.forMutation(...)` ở M07 — sinh JSON string cho `ARGV[4]`.

### 6.3 Result Mapping (caller layer)

> Caller (M11 Temporal / M12 Admin) chịu trách nhiệm map `LuaReturnCode` → exception / response:

| LuaReturnCode | Caller hành động |
|:---|:---|
| `APPLIED` | Tiếp tục saga / trả success |
| `IDEMPOTENT_HIT` | Tiếp tục saga / trả idempotent success, KHÔNG emit thêm event |
| `OUT_OF_STOCK` | Throw `OutOfStockException` → Temporal compensation cho items đã apply |
| `INSUFFICIENT_RESERVED` | Throw `InsufficientReservedException` |
| `RELEASE_UNDERFLOW` | Throw `ReleaseUnderflowException` |
| `SKU_SUSPENDED` | Throw `SkuSuspendedException` |
| `INVARIANT_DRIFT` | Suspend SKU (gọi `SuspensionFlagManager`), throw `InvariantDriftException` |

---

## 7. SuspensionFlagManager

### 7.1 Port Interface

```java
public interface SuspensionFlagManager {
    void suspend(SkuWarehouseKey key, String reason);
    void unsuspend(SkuWarehouseKey key);
    boolean isSuspended(SkuWarehouseKey key);
    Optional<SuspensionInfo> getInfo(SkuWarehouseKey key);
}

public record SuspensionInfo(String reason, long lockedAtMs, String status) {}
```

### 7.2 Implementation

```java
@Component
@RequiredArgsConstructor
public class SuspensionFlagManagerImpl implements SuspensionFlagManager {

    private static final long SUSPENSION_TTL_SEC = 24L * 60 * 60;  // 24 hours

    private final StringRedisTemplate redis;
    private final RedisKeyBuilder keys;

    @Override
    public void suspend(SkuWarehouseKey key, String reason) {
        String suspendKey = keys.suspensionKey(key);
        Map<String, String> hash = Map.of(
            "lock_reason", reason,
            "locked_at", String.valueOf(System.currentTimeMillis()),
            "status", "SUSPENDED"
        );
        redis.opsForHash().putAll(suspendKey, hash);
        redis.expire(suspendKey, Duration.ofSeconds(SUSPENSION_TTL_SEC));
    }

    @Override
    public boolean unsuspend(SkuWarehouseKey key) {
        return Boolean.TRUE.equals(redis.delete(keys.suspensionKey(key)));
    }

    @Override
    public boolean isSuspended(SkuWarehouseKey key) {
        return Boolean.TRUE.equals(redis.hasKey(keys.suspensionKey(key)));
    }

    @Override
    public Optional<SuspensionInfo> getInfo(SkuWarehouseKey key) {
        Map<Object, Object> entries = redis.opsForHash().entries(keys.suspensionKey(key));
        if (entries.isEmpty()) return Optional.empty();
        return Optional.of(new SuspensionInfo(
            String.valueOf(entries.get("lock_reason")),
            parseLong(entries.get("locked_at")),
            String.valueOf(entries.get("status"))
        ));
    }

    private long parseLong(Object v) {
        if (v == null) return 0;
        try { return Long.parseLong(v.toString()); } catch (NumberFormatException e) { return 0; }
    }
}
```

> TTL 24h tự động cleanup nếu admin không can thiệp. Manual unlock từ Admin API sẽ `DEL` thẳng.

---

## 8. RoutePlanCache

### 8.1 Port Interface

```java
public interface RoutePlanCache {
    void save(UUID routePlanId, String planJson, Duration ttl);
    Optional<String> find(UUID routePlanId);
    boolean exists(UUID routePlanId);
}
```

### 8.2 Implementation

```java
@Component
@RequiredArgsConstructor
public class RoutePlanCacheImpl implements RoutePlanCache {

    private final StringRedisTemplate redis;
    private final RedisKeyBuilder keys;

    @Override
    public void save(UUID routePlanId, String planJson, Duration ttl) {
        redis.opsForValue().set(keys.routePlanKey(routePlanId), planJson, ttl);
    }

    @Override
    public Optional<String> find(UUID routePlanId) {
        return Optional.ofNullable(redis.opsForValue().get(keys.routePlanKey(routePlanId)));
    }

    @Override
    public boolean exists(UUID routePlanId) {
        return Boolean.TRUE.equals(redis.hasKey(keys.routePlanKey(routePlanId)));
    }
}
```

> JSON serialization do caller (M10) handle — adapter chỉ nhận string.

---

## 9. Stock Payload Builder (delegated to M07)

> `StockMutationPayloadBuilder` được inject từ M07 (Event Definitions). M06 chỉ gọi `payloadBuilder.forMutation(...)` để build JSON cho ARGV[4].

---

## 10. Error Handling

| Exception | Khi nào | Caller hành động |
|:---|:---|:---|
| `RedisConnectionFailureException` | Redis cluster down / network | Throw `InventoryUnavailableException` → HTTP 503 |
| `QueryTimeoutException` | Lua script timeout | Throw `InventoryUnavailableException` → HTTP 503 |
| `IllegalArgumentException` | Caller vi phạm pre-condition (quantity <= 0) | Reject ngay tại Java, không tới Redis |

> KHÔNG retry Lua script tự động — Lua scripts là idempotent (qua `idempotency_key`), nhưng retry sẽ trả về sai return code cho caller (IDEMPOTENT_HIT thay vì APPLIED). Caller phải tự quyết định retry ở Activity level (Temporal).

---

## 11. Cluster Hash-Tag Verification Test

Một unit test quan trọng:

```java
@Test
void allKeysForSameSkuWarehouse_shareSameClusterSlot() {
    SkuWarehouseKey k = new SkuWarehouseKey("sku-001", 5L);
    String stock = keys.stockKey(k);
    String idemp = keys.idempotencyKey("reserve", k, 10001);
    String stream = keys.streamKey(k);
    String suspend = keys.suspensionKey(k);

    assertThat(stock).contains("{sku-001:5}");
    assertThat(idemp).contains("{sku-001:5}");
    assertThat(stream).contains("{sku-001:5}");
    assertThat(suspend).contains("{sku-001:5}");
}
```

> Tất cả keys có hash-tag giống nhau → cùng slot khi chạy trên Redis Cluster.

---

## 12. Configuration

`InventoryProperties` (M01) đã có:

```yaml
inventory:
  redis:
    cluster:
      nodes: localhost:6379
      max-redirects: 3
    timeout-ms: 2000
```

Thêm (nếu cần override):

```yaml
inventory:
  redis:
    idempotency-ttl-days: 30
    suspension-ttl-hours: 24
```

---

## 13. Verification

| Check | Cách verify |
|:---|:---|
| Lua scripts load | `@PostConstruct` log "Loaded 3 Lua scripts" |
| EVALSHA fallback | Stop Redis, restart, gọi script → Spring Data Redis tự EVAL nếu NOSCRIPT |
| Hash-tag invariant | Integration test với Redis Cluster 3 nodes |
| Idempotency | Reserve → reserve lại cùng `(sku, wh, order)` → return code 2 |
| Suspension | Suspend → reserve → return `{0, 'SKU_SUSPENDED'}` |
| TTL | Set suspend → sau 24h Redis tự DEL |

---

## 14. Files Created

```
src/main/java/com/platform/inventoryservice/infrastructure/redis/
├── script/
│   ├── LuaScriptLoader.java
│   └── LuaScriptRegistry.java
├── key/
│   └── RedisKeyBuilder.java
├── adapter/
│   ├── StockAtomicOpsImpl.java
│   ├── SuspensionFlagManagerImpl.java
│   └── RoutePlanCacheImpl.java
└── IdempotencyKeyBuilderImpl.java

src/main/java/com/platform/inventoryservice/port/outbound/cache/
└── (4 interfaces + 2 records)
```