# Module 01: Project Foundation

> **Status:** Module 1 of 15 - **COMPLETED**
> **Prerequisites:** None (this is the first module)
> **Dependents:** All other modules
> **Implementation Date:** June 11, 2026

> ## Implementation Summary
> All items in Module 1 have been fully implemented in `java/services/product-service/`.

---

## 1. Overview

This module establishes the project scaffold: Maven build configuration, Spring Boot application entry point, and all base infrastructure configurations (database, Redis, Kafka, gRPC, scheduling).

---

## 2. Maven Configuration

### 2.1 File: `pom.xml`

**Path:** `product-service/pom.xml`

Create the Maven project object model with all required dependencies.

- [x] Create `<project>` root with `<groupId>com.platform</groupId>`, `<artifactId>product-service</artifactId>`, `<version>1.0.0-SNAPSHOT</version>`
- [x] Add `<packaging>jar</packaging>` and `<java.version>21</java.version>`
- [x] Add parent POM: `org.springframework.boot:spring-boot-starter-parent:3.3.x`
- [x] Add **Web**: `spring-boot-starter-web`, `spring-boot-starter-validation`
- [x] Add **Data JPA**: `spring-boot-starter-data-jpa`, `hibernate-core`
- [x] Add **Database**: `org.postgresql:postgresql` (runtime), `org.flywaydb:flyway-core`, `org.flywaydb:flyway-database-postgresql`
- [x] Add **Redis**: `spring-boot-starter-data-redis`, `io.lettuce:lettuce-core`
- [x] Add **Kafka**: `spring-kafka`, `spring-boot-starter-aop` (for `@Retryable`)
- [x] Add **gRPC**: `net.devh:grpc-spring-boot-starter`
- [x] Add **State Machine**: `spring-statemachine-core`
- [x] Add **Resilience**: `resilience4j-spring-boot3`, `resilience4j-reactor`
- [x] Add **gRPC Protobuf**: `com.google.protobuf:protobuf-java`, `io.grpc:grpc-protobuf`, `io.grpc:grpc-stub`, `io.grpc:grpc-services`
- [x] Add **MapStruct**: `org.mapstruct:mapstruct`, `org.mapstruct:mapstruct-processor`, `org.projectlombok:lombok-mapstruct-binding`
- [x] Add **Lombok**: `org.projectlombok:lombok`
- [x] Add **Testing**: `spring-boot-starter-test`, `spring-kafka-test`, `testcontainers`, `junit-jupiter`, `mockito-core`, `assertj-core`
- [x] Add **Build Plugins**: `maven-compiler-plugin` (target 21, annotation processing), `protobuf-maven-plugin` (for gRPC code gen), `spring-boot-maven-plugin`
- [x] Add **ArchUnit**: `com.tngtech.archunit:archunit-junit5` (for layer testing)

---

## 3. Application Entry Point

### 3.1 File: `ProductServiceApplication.java`

**Path:** `product-service/src/main/java/com/platform/productservice/ProductServiceApplication.java`

- [x] Create `@SpringBootApplication` class
- [x] Add `@EnableScheduling` for cron jobs
- [x] Add `@EnableConfigurationProperties` for application properties
- [x] Main method with `SpringApplication.run()`

---

## 4. Configuration Files

### 4.1 File: `application.yml`

**Path:** `product-service/src/main/resources/application.yml`

- [x] Configure `server.port: 8080` and `server.shutdown: graceful`
- [x] Configure `spring.application.name: product-service`
- [x] Configure `spring.datasource` (PostgreSQL URL, username, password, HikariCP pool settings)
- [x] Configure `spring.jpa` (hibernate ddl-auto: validate, open-in-view: false, properties for PostgreSQL dialect)
- [x] Configure `spring.flyway` (enabled: true, locations: classpath:db/migration)
- [x] Configure `spring.data.redis` (host, port, timeout, Lettuce pool settings)
- [x] Configure `spring.kafka` (bootstrap-servers, consumer (auto-offset-reset: earliest, group-id, key/value deserializer), producer (key/value serializer), listener (ack-mode: record))
- [x] Configure gRPC server port (default 9090)
- [x] Configure `management.endpoints.web.exposure.include: health,info,metrics,prometheus`
- [x] Configure `spring.kafka.topics` with all topic names: `t.product.events`, `t.category.events`, `t.promotion.markdown.events`, `t.inventory.events`
- [x] Configure `spring.kafka.consumer.group-ids` with groups: `product-event-handler-group`, `product-markdown-group`, `product-inventory-fast-sync-group`
- [x] Configure custom app properties: markdown expiry cron, outbox retention days, inventory sync batch size, Lua script paths

### 4.2 File: `application-dev.yml`

**Path:** `product-service/src/main/resources/application-dev.yml`

- [x] Override database URL to use local PostgreSQL
- [x] Configure Redis host to localhost
- [x] Configure Kafka bootstrap-servers to localhost
- [x] Enable `spring.jpa.hibernate.ddl-auto: create-drop` for dev

### 4.3 File: `application-prod.yml`

**Path:** `product-service/src/main/resources/application-prod.yml`

- [x] Configure connection pool: HikariCP `maximum-pool-size: 20`, `minimum-idle: 5`
- [x] Configure Kafka consumer `concurrency: 3` per listener
- [x] Configure Redis `timeout: 2000ms`

---

## 5. Flyway Database Migrations

### 5.1 File: `V1__initial_schema.sql`

**Path:** `product-service/src/main/resources/db/migration/V1__initial_schema.sql`

Create all base tables:

- [x] Create `categories` table: `category_id VARCHAR(255) PK`, `parent_id VARCHAR(255) INDEX`, `name VARCHAR(255)`, `slug VARCHAR(255) UNIQUE`, `level INT`, `is_leaf BOOLEAN`, `created_at TIMESTAMPTZ`, `updated_at TIMESTAMPTZ`
- [x] Create `attributes` table: `attribute_id VARCHAR(255) PK`, `name VARCHAR(255)`, `type VARCHAR(50)`, `created_at TIMESTAMPTZ`
- [x] Create `attribute_values` table: `value_id VARCHAR(255) PK`, `attribute_id VARCHAR(255) INDEX`, `value VARCHAR(255)`
- [x] Create `category_attributes` table: `id VARCHAR(255) PK`, `category_id VARCHAR(255) INDEX`, `attribute_id VARCHAR(255) INDEX`, `is_required BOOLEAN`, `is_variant BOOLEAN`
- [x] Create `products` table: `product_id VARCHAR(255) PK`, `category_id VARCHAR(255) INDEX`, `name VARCHAR(255)`, `slug VARCHAR(255)`, `description TEXT`, `status VARCHAR(50) DEFAULT 'DRAFT'`, `is_deleted BOOLEAN DEFAULT FALSE`, `admin_hidden BOOLEAN DEFAULT FALSE`, `deleted_at TIMESTAMPTZ`, `attributes_json JSONB`, `created_at TIMESTAMPTZ`, `updated_at TIMESTAMPTZ`, `version BIGINT DEFAULT 0` (for optimistic locking)
- [x] Create `product_skus` table: `sku_id VARCHAR(255) PK`, `product_id VARCHAR(255) INDEX`, `sku_code VARCHAR(255) UNIQUE`, `original_price DECIMAL(15,2)`, `promotional_price DECIMAL(15,2)`, `status VARCHAR(50) DEFAULT 'ACTIVE'`, `is_deleted BOOLEAN DEFAULT FALSE`, `deleted_at TIMESTAMPTZ`, `display_quantity INT DEFAULT 0`, `last_inventory_sync_at BIGINT DEFAULT 0`, `variant_attributes_json JSONB`, `created_at TIMESTAMPTZ`, `updated_at TIMESTAMPTZ`, `version BIGINT DEFAULT 0`
- [x] Create `product_media` table: `media_id VARCHAR(255) PK`, `product_id VARCHAR(255) INDEX`, `sku_id VARCHAR(255) NULL INDEX`, `media_type VARCHAR(50)`, `url VARCHAR(255)`, `thumbnail_url VARCHAR(255)`, `sort_order INT DEFAULT 0`, `created_at TIMESTAMPTZ`, `updated_at TIMESTAMPTZ`
- [x] Create `product_markdown_applied` table: `id VARCHAR(255) PK`, `product_id VARCHAR(255) INDEX`, `rule_id VARCHAR(255) INDEX NOT NULL`, `sku_id VARCHAR(255) INDEX`, `discount_type VARCHAR(50)`, `discount_value DECIMAL(15,2)`, `max_discount_amount DECIMAL(15,2)`, `calculated_price DECIMAL(15,2)`, `applied_at TIMESTAMPTZ`, `expires_at TIMESTAMPTZ INDEX NOT NULL`; add UNIQUE INDEX on `(rule_id, sku_id)`
- [x] Create `product_outbox_events` table: `event_id UUID PK`, `event_type VARCHAR(100)`, `aggregate_type VARCHAR(50)`, `aggregate_id VARCHAR(255)`, `partition_key VARCHAR(255) NOT NULL`, `payload JSONB`, `created_at TIMESTAMPTZ`
- [x] Create indexes as defined in `product_db_design.md` (idx_products_category_id, idx_products_status, idx_skus_product_id, idx_skus_status, idx_markdown_expires)
- [x] Add check constraint for `products.status` values: `CHECK (status IN ('DRAFT', 'ACTIVE', 'HIDDEN'))`
- [x] Add check constraint for `product_skus.status` values: `CHECK (status IN ('ACTIVE', 'INACTIVE', 'OUT_OF_STOCK'))`

### 5.2 File: `V2__add_outbox_partition_index.sql`

**Path:** `product-service/src/main/resources/db/migration/V2__add_outbox_partition_index.sql`

- [x] Create B-tree index on `product_outbox_events(partition_key, created_at)`
- [x] Create B-tree index on `product_outbox_events(created_at)` for retention trimming

---

## 6. Spring Configuration Classes

### 6.1 File: `config/AppConfig.java`

**Path:** `product-service/src/main/java/com/platform/productservice/config/AppConfig.java`

- [x] Create `@Configuration` class with `@ConfigurationProperties(prefix = "app")`
- [x] Define nested class for custom application properties: `markdown-expiry-cron`, `outbox-retention-days`, `inventory-sync-batch-size`, `inventory-sync-interval-ms`
- [x] Create `@Bean` for `ObjectMapper` with Java 8 time module and snake_case naming strategy
- [x] Create `@Bean` for `Clock` returning system clock (for testability)

### 6.2 File: `config/SchedulerConfig.java`

**Path:** `product-service/src/main/java/com/platform/productservice/config/scheduling/SchedulerConfig.java`

- [x] Create `@Configuration` class with `@EnableScheduling`
- [x] Configure `SchedulingConfigurer` with custom thread pool (core size: 4, max size: 8)

### 6.3 File: `config/WebConfig.java`

**Path:** `product-service/src/main/java/com/platform/productservice/config/WebConfig.java`

- [x] Create `@Configuration` implementing `WebMvcConfigurer`
- [x] Configure CORS (allow origins from environment config)
- [x] Configure message converters (Jackson for JSON)
- [x] Register `HealthController` under `/actuator/health`

### 6.4 File: `config/JacksonConfig.java`

**Path:** `product-service/src/main/java/com/platform/productservice/config/JacksonConfig.java`

- [x] Configure `ObjectMapper` with `JavaTimeModule`, `Jdk8Module`
- [x] Configure `PropertyNamingStrategies.SNAKE_CASE` for JSON serialization
- [x] Configure `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES = false`
- [x] Register with `MappingJackson2HttpMessageConverter`

### 6.5 File: `config/TransactionConfig.java`

**Path:** `product-service/src/main/java/com/platform/productservice/config/TransactionConfig.java`

- [x] Configure `JpaTransactionManager`
- [x] Set `isolation = Isolation.READ_COMMITTED` for write transactions
- [x] Configure `transactionManager` with `proxyTargetClass = true`

### 6.6 File: `common/constants/ServiceConstants.java`

**Path:** `product-service/src/main/java/com/platform/productservice/common/constants/ServiceConstants.java`

- [x] Define constants: `SERVICE_NAME = "product-service"`, `DEFAULT_PAGE_SIZE = 20`, `MAX_PAGE_SIZE = 100`
- [x] Define Redis key prefixes: `KEY_PREFIX = "product"`, `SKU_KEY_PREFIX`, `MARKDOWN_KEY_PREFIX`, `INVENTORY_PENDING_KEY`
- [x] Define Kafka topic names: `TOPIC_PRODUCT_EVENTS`, `TOPIC_CATEGORY_EVENTS`, `TOPIC_MARKDOWN_EVENTS`, `TOPIC_INVENTORY_EVENTS`
- [x] Define consumer group IDs: `CG_EVENT_HANDLER`, `CG_MARKDOWN`, `CG_INVENTORY_SYNC`
- [x] Define lock timeout constants: `LOCK_TIMEOUT_SECONDS`, `DISTRIBUTED_LOCK_TTL_SECONDS`
- [x] Define cache TTLs: `SKU_CACHE_TTL_SECONDS`, `MARKDOWN_CACHE_TTL_BUFFER_SECONDS`

### 6.7 File: `common/errors/ErrorCode.java`

**Path:** `product-service/src/main/java/com/platform/productservice/common/errors/ErrorCode.java`

- [x] Create enum `ErrorCode` with values: `PRODUCT_NOT_FOUND`, `SKU_NOT_FOUND`, `CATEGORY_NOT_FOUND`, `INVALID_STATE_TRANSITION`, `DUPLICATE_SLUG`, `VALIDATION_ERROR`, `CONCURRENCY_CONFLICT`, `LOCK_ACQUISITION_FAILED`, `CACHE_ERROR`, `OUTBOX_ERROR`
- [x] Each enum value has `code` (String), `httpStatus` (int), `message` (String)
- [x] Create `ErrorCodeException` factory method on each enum value

### 6.8 File: `common/utils/JsonUtils.java`

**Path:** `product-service/src/main/java/com/platform/productservice/common/utils/JsonUtils.java`

- [x] Create utility class with static methods: `toJson(Object)`, `fromJson(String, Class)`, `fromJson(String, TypeReference)`, `toJsonBytes(Object)`
- [x] Methods delegate to injected `ObjectMapper`

### 6.9 File: `common/utils/DateTimeUtils.java`

**Path:** `product-service/src/main/java/com/platform/productservice/common/utils/DateTimeUtils.java`

- [x] Create utility class with static methods: `toEpochMillis(Instant)`, `fromEpochMillis(long)`, `toIsoString(Instant)`, `parseIsoString(String)`
- [x] Add method `isExpired(Timestamp, Instant)` for markdown expiry checks

---

## 7. Base Classes

### 7.1 File: `domain/exception/DomainException.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/exception/DomainException.java`

- [x] Create abstract `DomainException extends RuntimeException`
- [x] Constructor accepting `String message` and `Throwable cause`
- [x] Protected constructor for subclasses

### 7.2 File: `domain/event/DomainEvent.java`

**Path:** `product-service/src/main/java/com/platform/productservice/domain/event/DomainEvent.java`

- [x] Create abstract `DomainEvent` with fields: `eventId (UUID)`, `eventType (String)`, `aggregateType (String)`, `aggregateId (String)`, `partitionKey (String)`, `timestamp (Instant)`, `schemaVersion (int = 1)`, `source (String = "product-service")`
- [x] Create constructor that auto-generates `eventId`, sets `timestamp` and `schemaVersion`
- [x] Create `toCloudEventPayload()` method for serialization

---

## 8. Proto Definition

### 8.1 File: `product_service.proto`

**Path:** `product-service/proto/product_service.proto`

- [x] Define `ProductSkuRequest` message: `string sku_id`, `string product_id`
- [x] Define `BatchProductSkuRequest` message: `repeated string sku_ids`
- [x] Define `ProductSkuResponse` message: `sku_id`, `product_id`, `sku_code`, `product_name`, `main_image_url`, `original_price`, `promotional_price`, `best_price`, `has_promotion`, `sku_status`, `product_status`, `is_available`, `is_deleted`, `variant_attributes (map<string,string>)`, `display_quantity`, `last_inventory_sync_at`, `price_updated_at`
- [x] Define `BatchProductSkuResponse` message: `repeated ProductSkuResponse skus`
- [x] Define `GetProductSku` RPC: takes `ProductSkuRequest`, returns `ProductSkuResponse`
- [x] Define `GetBatchProductSkus` RPC: takes `BatchProductSkuRequest`, returns `BatchProductSkuResponse`
- [x] Define `CategoryTreeSnapshotRequest` and `CategoryTreeSnapshotResponse`
- [x] Define `GetCategoryTree` RPC: returns category tree
- [x] Configure `option java_package = "com.platform.productservice.proto"`, `option java_multiple_files = true`
- [x] Add `java_outer_classname` option

---

## 9. Acceptance Criteria

- [ ] `mvn compile` succeeds without errors
- [ ] All Flyway migrations run successfully against a local PostgreSQL instance
- [ ] `ProductServiceApplication` starts and passes health check
- [ ] `application.yml` is valid YAML with no missing properties
- [ ] ArchUnit layer dependency rules can be enforced from the start

---

## 10. Implementation File Index

| # | File | Status |
|---|------|--------|
| 1 | `pom.xml` | ✅ Created |
| 2 | `ProductServiceApplication.java` | ✅ Created |
| 3 | `application.yml` | ✅ Created |
| 4 | `application-dev.yml` | ✅ Created |
| 5 | `application-prod.yml` | ✅ Created |
| 6 | `application-test.yml` | ✅ Created (bonus) |
| 7 | `V1__initial_schema.sql` | ✅ Created |
| 8 | `V2__add_outbox_partition_index.sql` | ✅ Created |
| 9 | `AppConfig.java` | ✅ Created |
| 10 | `SchedulerConfig.java` | ✅ Created |
| 11 | `WebConfig.java` | ✅ Created |
| 12 | `JacksonConfig.java` | ✅ Created |
| 13 | `TransactionConfig.java` | ✅ Created |
| 14 | `ServiceConstants.java` | ✅ Created |
| 15 | `ErrorCode.java` | ✅ Created |
| 16 | `JsonUtils.java` | ✅ Created |
| 17 | `DateTimeUtils.java` | ✅ Created |
| 18 | `DomainException.java` | ✅ Created |
| 19 | `DomainEvent.java` | ✅ Created |
| 20 | `product_service.proto` | ✅ Created |
| 21 | `inventory_cas.lua` | ✅ Created |
| 22 | `distributed_lock.lua` | ✅ Created |
| 23 | `ProductServiceApplicationTests.java` | ✅ Created |
