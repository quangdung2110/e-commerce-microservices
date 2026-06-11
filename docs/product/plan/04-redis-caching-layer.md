# Module 04: Redis Caching Layer

> **Status:** Module 4 of 15
> **Prerequisites:** Module 01 (foundation), Module 03 (repository layer)
> **Dependents:** Module 06 (business services), Module 08 (gRPC API), Module 10 (event handler), Module 12 (inventory sync)

---

## 1. Overview

This module implements the Redis caching layer: configuration, cache data models, cache repository implementations, Redis key builder, Lua script management, and the distributed lock mechanism.

---

## 2. Redis Configuration

### 2.1 File: `config/RedisConfig.java`

**Path:** `product-service/src/main/java/com/platform/productservice/config/RedisConfig.java`

- [ ] Create `@Configuration` class
- [ ] Configure `LettuceConnectionFactory` as `@Bean` with `RedisStandaloneConfiguration`
- [ ] Configure `RedisTemplate<String, String>` with `StringRedisSerializer` for both key and value
- [ ] Configure `RedisTemplate<String, Object>` with `Jackson2JsonRedisSerializer` for generic objects
- [ ] Configure `StringRedisTemplate` for convenience
- [ ] Configure `LettucePoolingClientConfiguration` with connection pool (max-active: 8, max-idle: 8, min-idle: 2)
- [ ] Enable Redis client metrics via Micrometer
- [ ] Configure `@Bean` `RedisJSONCommands<String>` for RedisJSON commands (`JSON.GET`, `JSON.MGET`, `JSON.SET`)
- [ ] Configure `@Bean` `RedisCommands<String, String>` for ZSET operations

### 2.2 File: `infrastructure/redis/RedisKeyBuilder.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/redis/RedisKeyBuilder.java`

- [ ] Create `@Component` class
- [ ] Define `private static final String KEY_PREFIX = "product"`
- [ ] Method `sku(String skuId)`: returns `"product:sku:{skuId}"`
- [ ] Method `markdownActive(String skuId)`: returns `"product:markdown:active:{skuId}"`
- [ ] Method `inventoryPending()`: returns `"product:inventory:pending"` (ZSET key)
- [ ] Method `idempotencyKey(String type, String id)`: returns `"product:idemp:{type}:{id}"`
- [ ] Method `distributedLock(String resource)`: returns `"product:lock:{resource}"`
- [ ] Method `distributedLock(String resource, String instanceId)`: returns `"product:lock:{resource}:{instanceId}"`
- [ ] Method `markdownStartGate(String ruleId)`: returns `"product:markdown:gate:{ruleId}"` (ZSET for start-time gate)
- [ ] Method `pendingMember(String skuId, long timestamp)`: returns `"{skuId}:{timestamp}"` (ZSET member format for inventory sync)

---

## 3. Cache Data Models

### 3.1 File: `infrastructure/redis/model/SkuCacheEntry.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/redis/model/SkuCacheEntry.java`

- [ ] Create POJO with all fields matching `product_caching_schema.md`:
  - `skuId`, `productId`, `skuCode`, `productName`, `mainImageUrl`, `originalPrice`, `promotionalPrice`, `bestPrice`, `hasPromotion`, `skuStatus`, `productStatus`, `isAvailable`, `isDeleted`, `variantAttributes (Map<String, String>)`, `displayQuantity`, `lastInventorySyncAt (long epoch millis)`, `priceUpdatedAt (String ISO 8601)`
- [ ] Add `@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor` (Lombok)
- [ ] Add `@JsonNaming(PropertyNamingStrategies.SNAKE_CASE)` annotation
- [ ] Create factory method `fromDomain(Product product, Sku sku)`: constructs entry from domain objects
- [ ] Create `toJson()` method returning JSON string using `ObjectMapper`
- [ ] Create static `fromJson(String json)` method for deserialization
- [ ] Create `mergeInventoryUpdate(int displayQuantity, long eventTimestamp, String skuStatus)`: applies inventory update only if `eventTimestamp > lastInventorySyncAt`

### 3.2 File: `infrastructure/redis/model/MarkdownCacheEntry.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/redis/model/MarkdownCacheEntry.java`

- [ ] Create POJO: `ruleId`, `discountType`, `discountValue`, `calculatedPrice`, `expiresAt (String ISO 8601)`
- [ ] Add `@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`
- [ ] Create `toJson()` and `fromJson(String)` methods
- [ ] Create `remainingTtlSeconds()`: calculates TTL based on `expiresAt - now`

### 3.3 File: `infrastructure/redis/model/PendingSyncItem.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/redis/model/PendingSyncItem.java`

- [ ] Create POJO for ZSET member: `skuId`, `productId`, `eventTimestamp (long)`, `availableQuantity (int)`, `skuStatus`
- [ ] Add `@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`
- [ ] Create `getMemberString()`: returns `"{skuId}:{eventTimestamp}"` format
- [ ] Create static `fromMemberString(String member)`: parses SKU ID and timestamp from member string
- [ ] Create `toJson()` and `fromJson(String)` for storing metadata in ZSET score+member

---

## 4. Redis Cache Repository

### 4.1 File: `port/outbound/SkuCacheRepository.java`

**Path:** `product-service/src/main/java/com/platform/productservice/port/outbound/SkuCacheRepository.java`

- [ ] Define interface: `Optional<SkuCacheEntry> findById(String skuId)`
- [ ] Define: `void save(String skuId, SkuCacheEntry entry, Duration ttl)`
- [ ] Define: `void delete(String skuId)`
- [ ] Define: `void deleteByPattern(String pattern)`
- [ ] Define: `List<SkuCacheEntry> findByIds(List<String> skuIds)` (batch read using MGET)
- [ ] Define: `Map<String, SkuCacheEntry> findByIdsAsMap(List<String> skuIds)`

### 4.2 File: `port/outbound/MarkdownCacheRepository.java`

**Path:** `product-service/src/main/java/com/platform/productservice/port/outbound/MarkdownCacheRepository.java`

- [ ] Define interface: `Optional<MarkdownCacheEntry> findBySkuId(String skuId)`
- [ ] Define: `void save(String skuId, MarkdownCacheEntry entry, Duration ttl)`
- [ ] Define: `void delete(String skuId)`
- [ ] Define: `void deleteBySkuIds(List<String> skuIds)`

### 4.3 File: `port/outbound/InventorySyncRepository.java`

**Path:** `product-service/src/main/java/com/platform/productservice/port/outbound/InventorySyncRepository.java`

- [ ] Define interface: `void addPendingSync(String skuId, long eventTimestamp, int quantity, String skuStatus)` (ZADD)
- [ ] Define: `List<PendingSyncItem> drainPendingSync(int limit)` (ZPOPMIN with limit)
- [ ] Define: `void acknowledgePendingSync(String member)` (ZREM)
- [ ] Define: `void acknowledgePendingSync(List<String> members)` (ZREM batch)
- [ ] Define: `long getPendingSyncCount()`
- [ ] Define: `void requeuePendingSync(List<PendingSyncItem> items)` (ZADD batch for retry)

### 4.4 File: `port/outbound/DistributedLockRepository.java`

**Path:** `product-service/src/main/java/com/platform/productservice/port/outbound/DistributedLockRepository.java`

- [ ] Define interface: `boolean acquireLock(String lockKey, Duration ttl)`
- [ ] Define: `void releaseLock(String lockKey)`
- [ ] Define: `boolean isLocked(String lockKey)`
- [ ] Define: `<T> T executeWithLock(String lockKey, Duration ttl, Callable<T> task)`: acquire, execute, release

### 4.5 File: `infrastructure/redis/SkuCacheRepositoryImpl.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/redis/SkuCacheRepositoryImpl.java`

- [ ] Implement `SkuCacheRepository` with `@Repository`
- [ ] Inject `RedisTemplate<String, String>`, `RedisKeyBuilder`, `ObjectMapper`
- [ ] Implement `findById`: `JSON.GET product:sku:{skuId}`
- [ ] Implement `save`: `JSON.SET product:sku:{skuId}` with TTL
- [ ] Implement `delete`: `JSON.DEL product:sku:{skuId}`
- [ ] Implement `findByIds`: use `JSON.MGET` for batch reads in single round-trip
- [ ] Implement `findByIdsAsMap`: return `Map<skuId, SkuCacheEntry>`
- [ ] Handle `RedisConnectionException` gracefully (log and return empty)

### 4.6 File: `infrastructure/redis/MarkdownCacheRepositoryImpl.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/redis/MarkdownCacheRepositoryImpl.java`

- [ ] Implement `MarkdownCacheRepository`
- [ ] Use `RedisTemplate<String, String>` with `StringRedisSerializer`
- [ ] Implement `findBySkuId`: `GET product:markdown:active:{skuId}`, parse JSON
- [ ] Implement `save`: `SET product:markdown:active:{skuId}` with dynamic TTL from entry
- [ ] Implement `delete`: `DEL product:markdown:active:{skuId}`
- [ ] Implement `deleteBySkuIds`: Redis pipeline `DEL` for batch

### 4.7 File: `infrastructure/redis/InventorySyncRepositoryImpl.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/redis/InventorySyncRepositoryImpl.java`

- [ ] Implement `InventorySyncRepository`
- [ ] Use `RedisTemplate<String, String>` for ZSET operations
- [ ] Implement `addPendingSync`: `ZADD product:inventory:pending {eventTimestamp} "{skuId}:{eventTimestamp}"`
- [ ] Implement `drainPendingSync`: `ZPOPMIN product:inventory:pending limit` (use `RedisTemplate.opsForZSet().popMin(key, count)`)
- [ ] Implement `acknowledgePendingSync`: `ZREM product:inventory:pending member`
- [ ] Implement `acknowledgePendingSync(List)`: pipeline ZREM
- [ ] Implement `getPendingSyncCount`: `ZCARD product:inventory:pending`
- [ ] Implement `requeuePendingSync`: `ZADD` batch with same scores

### 4.8 File: `infrastructure/redis/DistributedLockRepositoryImpl.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/redis/DistributedLockRepositoryImpl.java`

- [ ] Implement `DistributedLockRepository`
- [ ] Implement `acquireLock`: `SETNX` with TTL (use `setIfAbsent`)
- [ ] Implement `releaseLock`: `DEL` (only if value matches to prevent releasing another holder's lock)
- [ ] Implement `isLocked`: `EXISTS`
- [ ] Implement `executeWithLock`: try-acquire, execute task in try block, release in finally block

---

## 5. Lua Scripts

### 5.1 File: `resources/lua/inventory_fast_sync.lua`

**Path:** `product-service/src/main/resources/lua/inventory_fast_sync.lua`

**Implementation of the Lua script from `inventory_sync_optimization.md`.**

```lua
-- inventory_fast_sync.lua
-- KEYS[1] = product:sku:{sku_id}
-- KEYS[2] = product:inventory:pending
-- ARGV[1] = event_timestamp (epoch millis)
-- ARGV[2] = available_quantity
-- ARGV[3] = product_id
-- ARGV[4] = sku_status
-- Returns: 1 = applied, 0 = stale, -1 = cache miss
```

- [ ] Create Lua script file at `src/main/resources/lua/inventory_fast_sync.lua`
- [ ] Implement staleness guard: `eventTimestamp <= cachedTs` returns 0 (stale)
- [ ] Implement product ID guard: verify `data.product_id` matches `ARGV[3]`
- [ ] Apply update: set `displayQuantity`, `lastInventorySyncAt`, `skuStatus`, `isAvailable`
- [ ] Preserve existing fields (skuCode, productName, prices, etc.)
- [ ] ZADD to pending queue with score = `eventTimestamp`
- [ ] Return -1 on cache miss (ZADD only, do not write partial cache)

### 5.2 File: `infrastructure/redis/LuaScriptLoader.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/redis/LuaScriptLoader.java`

- [ ] Create `@Component` class
- [ ] Define `DefaultRedisScript<Long>` bean for `inventory_fast_sync.lua`
- [ ] Method `loadScript(String scriptName)`: reads from `classpath:lua/{scriptName}.lua`, creates `DefaultRedisScript`
- [ ] Register all scripts as Spring beans
- [ ] Add `@PostConstruct` to log loaded script SHAs (SHA caching for EVALSHA)

### 5.3 File: `infrastructure/redis/InventoryFastSyncLuaExecutor.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/redis/InventoryFastSyncLuaExecutor.java`

- [ ] Create `@Component` class
- [ ] Inject `RedisTemplate<String, String>`, `RedisKeyBuilder`, `DefaultRedisScript<Long>` (inventory_fast_sync)
- [ ] Method `execute(String skuId, long eventTimestamp, int availableQuantity, String productId, String skuStatus)`: returns `LuaSyncResult`
- [ ] Method `executeBatch(List<InventoryUpdate> updates)`: pipeline multiple Lua executions
- [ ] Create inner class `InventoryUpdate`: `skuId`, `eventTimestamp`, `availableQuantity`, `productId`, `skuStatus`
- [ ] Create inner class `LuaSyncResult`: `success (boolean)`, `applied (boolean)`, `stale (boolean)`, `cacheMiss (boolean)`, `message (String)`
- [ ] Use `RedisTemplate.executePipelined()` for batch execution
- [ ] Handle connection errors gracefully (log and return failure result)

---

## 6. Idempotency Support

### 6.1 File: `infrastructure/redis/IdempotencyGuard.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/redis/IdempotencyGuard.java`

- [ ] Create `@Component` class
- [ ] Inject `RedisTemplate<String, String>`, `RedisKeyBuilder`
- [ ] Method `boolean tryAcquire(String type, String id, Duration ttl)`: SETNX with TTL
- [ ] Method `void release(String type, String id)`: DEL
- [ ] Method `boolean isProcessed(String type, String id)`: EXISTS

---

## 7. Acceptance Criteria

- [ ] `inventory_fast_sync.lua` compiles and executes correctly with Redis
- [ ] Staleness guard correctly rejects events where `eventTimestamp <= lastInventorySyncAt`
- [ ] Cache miss handling correctly ZADDs without writing partial cache data
- [ ] Batch `JSON.MGET` for SKU point-read returns results in single round-trip
- [ ] ZSET pending queue correctly tracks all inventory sync events
- [ ] Distributed lock correctly prevents concurrent execution across pods
- [ ] All Redis operations handle `RedisConnectionException` without crashing the service
