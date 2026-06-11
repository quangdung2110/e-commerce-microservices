# Module 14: Observability and Tests

> **Status:** Module 14 of 15
> **Prerequisites:** Module 01 (foundation)
> **Dependents:** None (leaf module)

---

## 1. Overview

This module implements observability infrastructure (logging, metrics, health checks) and comprehensive test coverage (unit tests, integration tests, contract tests).

---

## 2. Logging Configuration

### 2.1 File: `config/LoggingConfig.java`

**Path:** `product-service/src/main/java/com/platform/productservice/config/LoggingConfig.java`

- [ ] Configure `logback-spring.xml` with:
  - JSON format for production (for Logstash/ELK ingestion)
  - Plain text format for development
  - Structured logging fields: `timestamp`, `level`, `logger`, `message`, `thread`, `service`, `traceId`, `spanId`, `productId`, `skuId`
  - Log levels: INFO for business operations, DEBUG for query execution, WARN for retry attempts, ERROR for failures
- [ ] Configure `MDC` (Mapped Diagnostic Context) for request tracking
- [ ] Add `requestId`, `productId`, `skuId` to MDC for each request

### 2.2 File: `common/tracing/TracingUtils.java`

**Path:** `product-service/src/main/java/com/platform/productservice/common/tracing/TracingUtils.java`

- [ ] Create `@Component` class
- [ ] Method `String getTraceId()`: extract from MDC or generate
- [ ] Method `void addToMdc(String key, String value)`: add context
- [ ] Method `void clearMdc()`: cleanup
- [ ] Method `void logWithContext(Supplier<String> messageSupplier)`: log with MDC context

---

## 3. Health Checks

### 3.1 File: `controller/HealthController.java`

**Path:** `product-service/src/main/java/com/platform/productservice/controller/HealthController.java`

- [ ] Create `@RestController` at `/actuator/health`
- [ ] Default Spring Boot Actuator health endpoints
- [ ] Custom health indicators:

### 3.2 File: `common/health/DatabaseHealthIndicator.java`

**Path:** `product-service/src/main/java/com/platform/productservice/common/health/DatabaseHealthIndicator.java`

- [ ] Implement `HealthIndicator`
- [ ] Query `SELECT 1` from PostgreSQL
- [ ] Return DOWN if query fails

### 3.3 File: `common/health/RedisHealthIndicator.java`

**Path:** `product-service/src/main/java/com/platform/productservice/common/health/RedisHealthIndicator.java`

- [ ] Implement `HealthIndicator`
- [ ] Ping Redis
- [ ] Return DOWN if ping fails

### 3.4 File: `common/health/KafkaHealthIndicator.java`

**Path:** `product-service/src/main/java/com/platform/productservice/common/health/KafkaHealthIndicator.java`

- [ ] Implement `HealthIndicator`
- [ ] Check Kafka admin client connectivity
- [ ] Return DOWN if unreachable

---

## 4. Metrics

### 4.1 File: `config/MetricsConfig.java`

**Path:** `product-service/src/main/java/com/platform/productservice/config/MetricsConfig.java`

- [ ] Configure Micrometer with Prometheus registry
- [ ] Configure `MetricsProperties` from `application.yml`
- [ ] Register custom meters: JPA query duration, Redis operation duration, Kafka consumer lag

### 4.2 File: `common/metrics/ProductServiceMetrics.java`

**Path:** `product-service/src/main/java/com/platform/productservice/common/metrics/ProductServiceMetrics.java`

- [ ] Create `@Component` with micrometer `MeterRegistry`
- [ ] Define counters:
  - `product.created`, `product.updated`, `product.deleted`
  - `sku.created`, `sku.updated`, `sku.deleted`
  - `category.created`, `category.updated`, `category.deleted`
  - `product.state.transition.{from}.{to}`
- [ ] Define timers:
  - `product.create.duration`, `product.update.duration`
  - `sku.create.duration`, `sku.update.duration`
  - `inventory.sync.phase2.duration`
  - `markdown.calculator.duration`
- [ ] Define gauges:
  - `inventory.sync.pending_queue.size`
  - `markdown.active.count`

---

## 5. Unit Tests

### 5.1 Domain Model Tests

#### 5.1.1 File: `src/test/java/com/platform/productservice/domain/entity/ProductTest.java`

**Path:** `product-service/src/test/java/com/platform/productservice/domain/entity/ProductTest.java`

- [ ] Test `initialize()`: sets DRAFT status, generates ID
- [ ] Test `submit()` with valid SKU: transitions to ACTIVE
- [ ] Test `submit()` with no SKU: throws DomainException
- [ ] Test `submit()` with no price: throws DomainException
- [ ] Test `submit()` from non-DRAFT state: throws InvalidStateTransitionException
- [ ] Test `hide()`: transitions to HIDDEN, sets adminHidden = true
- [ ] Test `hide()` idempotent: no exception
- [ ] Test `show()` with stock: transitions to ACTIVE
- [ ] Test `show()` without stock: throws DomainException
- [ ] Test `autoHideOutOfStock()`: hides when all SKUs out of stock
- [ ] Test `autoReactivateInStock()`: reactivates when any SKU in stock
- [ ] Test `markDeleted()`: sets deleted flag and timestamp
- [ ] Test `canBeDeleted()`: true for DRAFT and HIDDEN, false for ACTIVE

#### 5.1.2 File: `src/test/java/com/platform/productservice/domain/service/MarkdownCalculatorTest.java`

**Path:** `product-service/src/test/java/com/platform/productservice/domain/service/MarkdownCalculatorTest.java`

- [ ] Test PERCENTAGE discount: original 34990, 15%, result 29741.50
- [ ] Test PERCENTAGE with max cap: original 100000, 50%, cap 10000, result 90000
- [ ] Test FIXED_AMOUNT discount: original 34990, 5000, result 29990
- [ ] Test FIXED_AMOUNT goes negative: original 3000, discount 5000, result 0
- [ ] Test null max_discount_amount: no cap applied
- [ ] Test zero original_price: throws DomainException
- [ ] Test negative discount_value: throws DomainException

### 5.2 Service Layer Tests

#### 5.2.1 File: `src/test/java/com/platform/productservice/service/impl/ProductServiceImplTest.java`

**Path:** `product-service/src/test/java/com/platform/productservice/service/impl/ProductServiceImplTest.java`

- [ ] Test `create`: saves product and SKUs, publishes outbox event, warms cache
- [ ] Test `create` with duplicate slug: throws DuplicateSlugException
- [ ] Test `create` with invalid category: throws CategoryNotFoundException
- [ ] Test `update`: acquires lock, updates fields, publishes outbox
- [ ] Test `delete` on ACTIVE product: throws DomainException
- [ ] Test `delete` on DRAFT product: soft deletes, publishes event
- [ ] Mock `ProductRepository`, `SkuCacheRepository`, `OutboxRepository`

### 5.3 Redis Layer Tests

#### 5.3.1 File: `src/test/java/com/platform/productservice/infrastructure/redis/LuaScriptTest.java`

**Path:** `product-service/src/test/java/com/platform/productservice/infrastructure/redis/LuaScriptTest.java`

- [ ] Test stale event rejection: eventTimestamp <= cachedTs → returns 0
- [ ] Test product ID mismatch: returns 0
- [ ] Test successful update: returns 1, cache updated, ZADD to pending
- [ ] Test cache miss: returns -1, ZADD to pending, no cache write
- [ ] Use embedded Redis or Testcontainers for Redis integration

---

## 6. Integration Tests

### 6.1 File: `src/test/java/com/platform/productservice/integration/ProductRepositoryIntegrationTest.java`

**Path:** `product-service/src/test/java/com/platform/productservice/integration/ProductRepositoryIntegrationTest.java`

- [ ] Use `@DataJpaTest` with `@AutoConfigureTestDatabase`
- [ ] Use H2 in-memory database with PostgreSQL dialect
- [ ] Test pessimistic locking: concurrent updates trigger OptimisticLockException
- [ ] Test soft delete
- [ ] Test recursive CTE for category descendants

### 6.2 File: `src/test/java/com/platform/productservice/integration/RedisCacheIntegrationTest.java`

**Path:** `product-service/src/test/java/com/platform/productservice/integration/RedisCacheIntegrationTest.java`

- [ ] Use Testcontainers for Redis
- [ ] Test JSON.MGET batch read
- [ ] Test ZSET pending queue operations
- [ ] Test distributed lock acquisition and release

### 6.3 File: `src/test/java/com/platform/productservice/integration/KafkaConsumerIntegrationTest.java`

**Path:** `product-service/src/test/java/com/platform/productservice/integration/KafkaConsumerIntegrationTest.java`

- [ ] Use Testcontainers for Kafka
- [ ] Test `ProductEventHandlerWorker` processes SKU_UPDATED and updates Redis
- [ ] Test `InventorySyncFastPathConsumer` processes inventory event and writes to Redis
- [ ] Test DLQ receives failed messages after max retries

### 6.4 File: `src/test/java/com/platform/productservice/integration/StateMachineIntegrationTest.java`

**Path:** `product-service/src/test/java/com/platform/productservice/integration/StateMachineIntegrationTest.java`

- [ ] Test full FSM flow: DRAFT → SUBMIT → ACTIVE → HIDE → SHOW
- [ ] Test guard rejection: SUBMIT guard fails when no valid SKU
- [ ] Test AUTO_HIDE: product auto-hides when inventory goes to zero
- [ ] Test AUTO_REACTIVATE: product reactivates when inventory restored

---

## 7. Contract Tests

### 7.1 File: `src/test/java/com/platform/productservice/contract/EventContractTest.java`

**Path:** `product-service/src/test/java/com/platform/productservice/contract/EventContractTest.java`

- [ ] Test all CloudEvents conform to schema version 1
- [ ] Test SKU_UPDATED contains all required fields: `changedFields[]`, `changeSource`, `updatedAt`
- [ ] Test partition key is `sku_id` for SKU events, `product_id` for product events
- [ ] Use JSON Schema validation for event payloads

### 7.2 File: `src/test/java/com/platform/productservice/contract/ApiContractTest.java`

**Path:** `product-service/src/test/java/com/platform/productservice/contract/ApiContractTest.java`

- [ ] Use Spring Cloud Contract or REST Assured
- [ ] Define API contracts for all admin REST endpoints
- [ ] Generate and run WireMock stubs for consumer services

---

## 8. ArchUnit Layer Tests

### 8.1 File: `src/test/java/com/platform/productservice/arch/LayerDependencyTest.java`

**Path:** `product-service/src/test/java/com/platform/productservice/arch/LayerDependencyTest.java`

- [ ] Verify `domain/` has no dependencies on `infrastructure/`
- [ ] Verify `domain/` has no dependencies on `controller/`
- [ ] Verify `domain/` has no dependencies on Spring annotations (`@Service`, `@Repository`)
- [ ] Verify `controller/` does not depend on `infrastructure/` directly
- [ ] Verify `worker/` only depends on `domain/`, `infrastructure/`, and `port/`
- [ ] All ArchUnit tests must pass

---

## 9. Acceptance Criteria

- [ ] All domain entity tests pass with 100% coverage of business rules
- [ ] `MarkdownCalculator` tests cover all edge cases (zero price, negative discount, max cap)
- [ ] Service layer tests use mocks and cover happy path and error paths
- [ ] Integration tests use Testcontainers for PostgreSQL, Redis, Kafka
- [ ] FSM integration tests cover all transitions and guards
- [ ] ArchUnit layer dependency rules are enforced
- [ ] Prometheus metrics endpoint exposes all custom metrics
- [ ] Health endpoint reports DOWN when any dependency is unreachable
- [ ] Contract tests validate all CloudEvent payloads
