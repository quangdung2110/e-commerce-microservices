# Module 04: Redis Caching Layer

> **Status:** Module 4 of 15 - **COMPLETED**
> **Implementation Date:** June 11, 2026
> **Prerequisites:** Module 01 (foundation), Module 03 (repository layer)
> **Dependents:** Module 06 (business services), Module 08 (gRPC API), Module 10 (event handler), Module 12 (inventory sync)
> **Last Updated:** June 11, 2026 (folder structure refactored to separate cache ports from database ports)

> ## Implementation Summary
> All items in Module 4 have been fully implemented in `java/services/product-service/`.

---

## 1. Overview

This module implements the Redis caching layer: configuration, cache data models, cache repository implementations, Redis key builder, Lua script management, and the distributed lock mechanism.

### 1.1 Folder Structure

The Redis layer is organized under `infrastructure/redis/` with clear separation between configuration, data models, and adapters. Cache ports are placed in `port/outbound/cache/` to distinguish them from database ports in `port/outbound/database/`.

```
productservice/
├── port/
│   └── outbound/
│       ├── database/              ← Database ports (Module 03)
│       │   ├── ProductRepository.java
│       │   ├── SkuRepository.java
│       │   └── ...
│       │
│       └── cache/                 ← Cache ports (Module 04) — TÁCH RIÊNG
│           ├── SkuCacheRepository.java
│           ├── MarkdownCacheRepository.java
│           ├── InventorySyncRepository.java
│           └── DistributedLockRepository.java
│
└── infrastructure/
    └── redis/
        ├── config/               ← Redis configuration
        │   └── RedisConfig.java
        │
        ├── cache/                ← Cache layer
        │   ├── model/           ← Cache data models
        │   │   ├── SkuCacheEntry.java
        │   │   ├── MarkdownCacheEntry.java
        │   │   └── PendingSyncItem.java
        │   │
        │   └── adapter/         ← Cache adapter implementations
        │       ├── SkuCacheRepositoryImpl.java
        │       └── MarkdownCacheRepositoryImpl.java
        │
        ├── lock/                 ← Distributed lock adapter
        │   └── DistributedLockRepositoryImpl.java
        │
        ├── sync/                 ← Inventory sync adapter + Lua executor
        │   ├── InventorySyncRepositoryImpl.java
        │   └── InventoryFastSyncLuaExecutor.java
        │
        ├── script/               ← Lua script loader
        │   └── LuaScriptLoader.java
        │
        ├── IdempotencyGuard.java
        └── RedisKeyBuilder.java

resources/lua/
├── inventory_fast_sync.lua       ← Phase 1 fast-path Lua script
└── (additional Lua scripts as needed)
```

**Layer separation:**
- `port/outbound/cache/` — defines contracts for cache access (no infrastructure imports)
- `infrastructure/redis/cache/adapter/` — implements cache port interfaces
- `infrastructure/redis/` — shared infrastructure components (key builder, idempotency, Lua scripts)

---

## 2. Redis Configuration

### 2.1 File: `infrastructure/redis/config/RedisConfig.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/redis/config/RedisConfig.java`

- [x] Create `@Configuration` class
- [x] Configure `LettuceConnectionFactory` as `@Bean` with `RedisStandaloneConfiguration`
- [x] Configure `RedisTemplate<String, String>` with `StringRedisSerializer` for both key and value
- [x] Configure `RedisTemplate<String, Object>` with `Jackson2JsonRedisSerializer` for generic objects
- [x] Configure `StringRedisTemplate` for convenience
- [x] Configure `LettucePoolingClientConfiguration` with connection pool (max-active: 8, max-idle: 8, min-idle: 2)
- [x] Enable Redis client metrics via Micrometer (via Lettuce built-in metrics)
- [x] Configure `@Bean` `RedisCommands<String, String>` for sync commands (ZSET, MGET, DEL operations)

### 2.2 File: `infrastructure/redis/RedisKeyBuilder.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/redis/RedisKeyBuilder.java`

- [x] Create `@Component` class
- [x] Define `private static final String KEY_PREFIX = "product"`
- [x] Method `sku(String skuId)`: returns `"product:sku:{skuId}"`
- [x] Method `markdownActive(String skuId)`: returns `"product:markdown:active:{skuId}"`
- [x] Method `inventoryPending()`: returns `"product:inventory:pending"` (ZSET key)
- [x] Method `idempotencyKey(String type, String id)`: returns `"product:idemp:{type}:{id}"`
- [x] Method `distributedLock(String resource)`: returns `"product:lock:{resource}"`
- [x] Method `distributedLock(String resource, String instanceId)`: returns `"product:lock:{resource}:{instanceId}"`
- [x] Method `markdownStartGate(String ruleId)`: returns `"product:markdown:gate:{ruleId}"` (ZSET for start-time gate)
- [x] Method `pendingMember(String skuId, long timestamp)`: returns `"{skuId}:{timestamp}"` (ZSET member format for inventory sync)

---

## 3. Cache Data Models

### 3.1 File: `infrastructure/redis/cache/model/SkuCacheEntry.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/redis/cache/model/SkuCacheEntry.java`

- [x] Create POJO with all fields matching `product_caching_schema.md`:
  - `skuId`, `productId`, `skuCode`, `productName`, `mainImageUrl`, `originalPrice`, `promotionalPrice`, `bestPrice`, `hasPromotion`, `skuStatus`, `productStatus`, `isAvailable`, `isDeleted`, `variantAttributes (Map<String, String>)`, `displayQuantity`, `lastInventorySyncAt (long epoch millis)`, `priceUpdatedAt (String ISO 8601)`
- [x] Add `@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor` (Lombok)
- [x] Add `@JsonNaming(PropertyNamingStrategies.SNAKE_CASE)` annotation
- [x] Create factory method `fromDomain(Product product, Sku sku)`: constructs entry from domain objects
- [x] Create `toJson()` method returning JSON string using `ObjectMapper`
- [x] Create static `fromJson(String json)` method for deserialization
- [x] Create `mergeInventoryUpdate(int displayQuantity, long eventTimestamp, String skuStatus)`: applies inventory update only if `eventTimestamp > lastInventorySyncAt`

### 3.2 File: `infrastructure/redis/cache/model/MarkdownCacheEntry.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/redis/cache/model/MarkdownCacheEntry.java`

- [x] Create POJO: `ruleId`, `discountType`, `discountValue`, `calculatedPrice`, `expiresAt (String ISO 8601)`
- [x] Add `@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`
- [x] Create `toJson()` and `fromJson(String)` methods
- [x] Create `remainingTtlSeconds()`: calculates TTL based on `expiresAt - now`

### 3.3 File: `infrastructure/redis/cache/model/PendingSyncItem.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/redis/cache/model/PendingSyncItem.java`

- [x] Create POJO for ZSET member: `skuId`, `productId`, `eventTimestamp (long)`, `availableQuantity (int)`, `skuStatus`
- [x] Add `@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`
- [x] Create `getMemberString()`: returns `"{skuId}:{eventTimestamp}"` format
- [x] Create static `fromMemberString(String member)`: parses SKU ID and timestamp from member string
- [x] Create `toJson()` and `fromJson(String)` for storing metadata in ZSET score+member

---

## 4. Cache Port Interfaces (`port/outbound/cache/`)

### 4.1 File: `port/outbound/cache/SkuCacheRepository.java`

**Path:** `product-service/src/main/java/com/platform/productservice/port/outbound/cache/SkuCacheRepository.java`

- [x] Define interface: `Optional<SkuCacheEntry> findById(String skuId)`
- [x] Define: `void save(String skuId, SkuCacheEntry entry, Duration ttl)`
- [x] Define: `void delete(String skuId)`
- [x] Define: `void deleteByPattern(String pattern)`
- [x] Define: `List<SkuCacheEntry> findByIds(List<String> skuIds)` (batch read using MGET)
- [x] Define: `Map<String, SkuCacheEntry> findByIdsAsMap(List<String> skuIds)`

### 4.2 File: `port/outbound/cache/MarkdownCacheRepository.java`

**Path:** `product-service/src/main/java/com/platform/productservice/port/outbound/cache/MarkdownCacheRepository.java`

- [x] Define interface: `Optional<MarkdownCacheEntry> findBySkuId(String skuId)`
- [x] Define: `void save(String skuId, MarkdownCacheEntry entry, Duration ttl)`
- [x] Define: `void delete(String skuId)`
- [x] Define: `void deleteBySkuIds(List<String> skuIds)`

### 4.3 File: `port/outbound/cache/InventorySyncRepository.java`

**Path:** `product-service/src/main/java/com/platform/productservice/port/outbound/cache/InventorySyncRepository.java`

- [x] Define interface: `void addPendingSync(String skuId, long eventTimestamp, int quantity, String skuStatus)` (ZADD)
- [x] Define: `List<PendingSyncItem> drainPendingSync(int limit)` (ZPOPMIN with limit)
- [x] Define: `void acknowledgePendingSync(String member)` (ZREM)
- [x] Define: `void acknowledgePendingSync(List<String> members)` (ZREM batch)
- [x] Define: `long getPendingSyncCount()`
- [x] Define: `void requeuePendingSync(List<PendingSyncItem> items)` (ZADD batch for retry)

### 4.4 File: `port/outbound/cache/DistributedLockRepository.java`

**Path:** `product-service/src/main/java/com/platform/productservice/port/outbound/cache/DistributedLockRepository.java`

- [x] Define interface: `boolean acquireLock(String lockKey, Duration ttl)`
- [x] Define: `void releaseLock(String lockKey)`
- [x] Define: `boolean isLocked(String lockKey)`
- [x] Define: `<T> T executeWithLock(String lockKey, Duration ttl, Callable<T> task)`: acquire, execute, release

---

## 5. Cache Adapter Implementations (`infrastructure/redis/cache/adapter/`)

### 5.1 File: `infrastructure/redis/cache/adapter/SkuCacheRepositoryImpl.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/redis/cache/adapter/SkuCacheRepositoryImpl.java`

- [x] Implement `SkuCacheRepository` with `@Repository`
- [x] Inject `RedisTemplate<String, String>`, `RedisKeyBuilder`, `ObjectMapper`
- [x] Implement `findById`: `GET product:sku:{skuId}`
- [x] Implement `save`: `SET product:sku:{skuId}` with TTL
- [x] Implement `delete`: `DEL product:sku:{skuId}`
- [x] Implement `findByIds`: use `MGET` for batch reads in single round-trip
- [x] Implement `findByIdsAsMap`: return `Map<skuId, SkuCacheEntry>`
- [x] Handle `RedisException` gracefully (log and return empty)

### 5.2 File: `infrastructure/redis/cache/adapter/MarkdownCacheRepositoryImpl.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/redis/cache/adapter/MarkdownCacheRepositoryImpl.java`

- [x] Implement `MarkdownCacheRepository`
- [x] Use `RedisTemplate<String, String>` with `StringRedisSerializer`
- [x] Implement `findBySkuId`: `GET product:markdown:active:{skuId}`, parse JSON
- [x] Implement `save`: `SET product:markdown:active:{skuId}` with dynamic TTL from entry
- [x] Implement `delete`: `DEL product:markdown:active:{skuId}`
- [x] Implement `deleteBySkuIds`: Redis pipeline `DEL` for batch

### 5.3 File: `infrastructure/redis/lock/DistributedLockRepositoryImpl.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/redis/lock/DistributedLockRepositoryImpl.java`

- [x] Implement `DistributedLockRepository`
- [x] Implement `acquireLock`: `SETNX` with TTL (use `setIfAbsent`)
- [x] Implement `releaseLock`: `DEL` (only if value matches to prevent releasing another holder's lock)
- [x] Implement `isLocked`: `EXISTS`
- [x] Implement `executeWithLock`: try-acquire, execute task in try block, release in finally block

### 5.4 File: `infrastructure/redis/sync/InventorySyncRepositoryImpl.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/redis/sync/InventorySyncRepositoryImpl.java`

- [x] Implement `InventorySyncRepository`
- [x] Use `RedisTemplate<String, String>` for ZSET operations
- [x] Implement `addPendingSync`: `ZADD product:inventory:pending {eventTimestamp} "{skuId}:{eventTimestamp}"`
- [x] Implement `drainPendingSync`: `ZPOPMIN product:inventory:pending limit` (use `RedisTemplate.opsForZSet().popMin(key, count)`)
- [x] Implement `acknowledgePendingSync`: `ZREM product:inventory:pending member`
- [x] Implement `acknowledgePendingSync(List)`: pipeline ZREM
- [x] Implement `getPendingSyncCount`: `ZCARD product:inventory:pending`
- [x] Implement `requeuePendingSync`: `ZADD` batch with same scores

---

## 6. Lua Scripts

### 6.1 File: `resources/lua/inventory_fast_sync.lua`

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

- [x] Create Lua script file at `src/main/resources/lua/inventory_fast_sync.lua`
- [x] Implement staleness guard: `eventTimestamp <= cachedTs` returns 0 (stale)
- [x] Implement product ID guard: verify `data.product_id` matches `ARGV[3]`
- [x] Apply update: set `displayQuantity`, `lastInventorySyncAt`, `skuStatus`, `isAvailable`
- [x] Preserve existing fields (skuCode, productName, prices, etc.)
- [x] ZADD to pending queue with score = `eventTimestamp`
- [x] Return -1 on cache miss (ZADD only, do not write partial cache)

### 6.2 File: `infrastructure/redis/script/LuaScriptLoader.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/redis/script/LuaScriptLoader.java`

- [x] Create `@Component` class
- [x] Load script content from classpath: `lua/inventory_fast_sync.lua` and `lua/distributed_lock.lua`
- [x] Method `getScript(String name)`: returns loaded script by name
- [x] Method `getScriptNames()`: returns all loaded script names
- [x] Method `getScriptSha(String name)` / `setScriptSha(String name, String sha)`: SHA caching support
- [x] Add `@PostConstruct` to log loaded scripts

### 6.3 File: `infrastructure/redis/sync/InventoryFastSyncLuaExecutor.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/redis/sync/InventoryFastSyncLuaExecutor.java`

- [x] Create `@Component` class
- [x] Inject `RedisTemplate<String, String>`, `RedisKeyBuilder`
- [x] Method `execute(...)`: executes Lua script, returns `LuaSyncResult`
- [x] Method `executeBatch(List<InventoryUpdate>)`: pipeline multiple Lua executions
- [x] Create inner class `InventoryUpdate`: `skuId`, `eventTimestamp`, `availableQuantity`, `productId`, `skuStatus`, `skuJson`
- [x] Create inner class `LuaSyncResult`: `success`, `applied`, `stale`, `cacheMiss`, `message`, `skuId`, `eventTimestamp`
- [x] Use `RedisTemplate.executePipelined()` for batch execution
- [x] Handle connection errors gracefully (log and return failure result)

---

## 7. Idempotency Support

### 7.1 File: `infrastructure/redis/IdempotencyGuard.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/redis/IdempotencyGuard.java`

- [x] Create `@Component` class
- [x] Inject `RedisTemplate<String, String>`, `RedisKeyBuilder`
- [x] Method `boolean tryAcquire(String type, String id, Duration ttl)`: SETNX with TTL
- [x] Method `void release(String type, String id)`: DEL
- [x] Method `boolean isProcessed(String type, String id)`: EXISTS

---

## 8. Acceptance Criteria

- [x] `inventory_fast_sync.lua` compiles and executes correctly with Redis
- [x] Staleness guard correctly rejects events where `eventTimestamp <= lastInventorySyncAt`
- [x] Cache miss handling correctly ZADDs without writing partial cache data
- [x] Batch `MGET` for SKU point-read returns results in single round-trip
- [x] ZSET pending queue correctly tracks all inventory sync events
- [x] Distributed lock correctly prevents concurrent execution across pods
- [x] All Redis operations handle `RedisException` without crashing the service
