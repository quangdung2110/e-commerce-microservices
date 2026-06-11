# Module 09: Outbox Pattern and CDC

> **Status:** Module 9 of 15
> **Prerequisites:** Module 01 (foundation), Module 03 (repository layer)
> **Dependents:** Module 10 (event handler), Module 11 (markdown worker), Module 12 (inventory sync)

---

## 1. Overview

This module implements the Outbox Pattern for reliable event publishing. Instead of publishing events directly to Kafka, every domain write inserts an outbox record into `product_outbox_events` within the same database transaction. Debezium CDC connector then reads from the PostgreSQL WAL and publishes events to Kafka topics with < 5ms latency.

---

## 2. Outbox Integration Design

### 2.1 Transactional Outbox Pattern

Every business operation that needs to emit an event follows this pattern:

```java
@Transactional
public void createProduct(Product product) {
    Product saved = productRepository.save(product);
    // ... save SKUs, etc.
    outboxEventPublisher.publish(new ProductCreatedEvent(saved));
}
```

The `DomainEventPublisher.publish()` method inserts into `product_outbox_events` within the same transaction. Debezium CDC reads WAL and publishes to Kafka.

### 2.2 Partition Key Rules

| Event Type | Partition Key | Reason |
|---|---|---|
| PRODUCT_CREATED, PRODUCT_UPDATED, PRODUCT_STATUS_CHANGED, PRODUCT_DELETED | `product_id` | Group all product events by product for ordering |
| SKU_CREATED, SKU_UPDATED, SKU_DELETED | `sku_id` | SKU events are partitioned by SKU |
| CATEGORY_CREATED, CATEGORY_UPDATED, CATEGORY_DELETED | `category_id` | Category events partitioned by category |

---

## 3. CloudEvents Wrapper

### 3.1 File: `infrastructure/kafka/deserializer/CloudEvent.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/kafka/deserializer/CloudEvent.java`

- [ ] Create `@Data`, `@Builder` POJO matching CloudEvents spec 1.0:
  - `eventId (String UUID)`
  - `eventType (String)`
  - `aggregateType (String)`
  - `aggregateId (String)`
  - `partitionKey (String)`
  - `correlationId (String)`
  - `timestamp (String ISO 8601)`
  - `source (String = "product-service")`
  - `schemaVersion (String = "1")`
  - `payload (Object)`

### 3.2 File: `infrastructure/kafka/deserializer/CloudEventDeserializer.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/kafka/deserializer/CloudEventDeserializer.java`

- [ ] Implement `org.apache.kafka.common.serialization.Deserializer<CloudEvent>`
- [ ] Configure `addHeaders` for `correlation_id`, `partition_key`
- [ ] Handle deserialization errors gracefully (log and return null)

### 3.3 File: `infrastructure/kafka/deserializer/CloudEventSerializer.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/kafka/deserializer/CloudEventSerializer.java`

- [ ] Implement `org.apache.kafka.common.serialization.Serializer<CloudEvent>`
- [ ] Serialize to JSON with `ObjectMapper`
- [ ] Add headers for metadata

---

## 4. Outbox Event Publisher

### 4.1 File: `infrastructure/kafka/producer/OutboxEventProducer.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/kafka/producer/OutboxEventProducer.java`

**Note: This is for direct Kafka publishing (alternative to Debezium). The primary path uses Debezium CDC.**

- [ ] Create `@Service` class implementing `DomainEventPublisher`
- [ ] Inject `KafkaTemplate<String, String>`, `ObjectMapper`
- [ ] Topic routing: `t.product.events` for product/SKU events, `t.category.events` for category events
- [ ] `publish(DomainEvent)`: serialize to CloudEvents JSON, send to Kafka with partition key
- [ ] `publishAsync(DomainEvent)`: non-blocking send with callbacks
- [ ] `publishAll(List<DomainEvent>)`: batch send
- [ ] Configure `KafkaTemplate` with `String` serializer for both key and value

### 4.2 File: `infrastructure/kafka/producer/TopicRouter.java`

**Path:** `product-service/src/main/java/com/platform/productservice/infrastructure/kafka/producer/TopicRouter.java`

- [ ] Create `@Component` class
- [ ] Method `String routeTopic(DomainEvent event)`:
  - If `eventType` contains CATEGORY → `t.category.events`
  - Otherwise → `t.product.events`
- [ ] Method `String getPartitionKey(DomainEvent event)`:
  - Return `partitionKey` from domain event

---

## 5. Debezium CDC Connector Configuration

### 5.1 File: `resources/db/migration/V3__add_debezium_metadata.sql`

**Path:** `product-service/src/main/resources/db/migration/V3__add_debezium_metadata.sql`

- [ ] Add `__source` column to outbox table if needed for Debezium
- [ ] Ensure proper WAL configuration for PostgreSQL

### 5.2 File: `resources/kafka/debezium-connector-product-outbox.json`

**Path:** `product-service/src/main/resources/kafka/debezium-connector-product-outbox.json`

**Reference configuration for DevOps team to set up Debezium connector.**

- [ ] Connector type: `io.debezium.connector.postgresql.SourceConnector`
- [ ] Database connection: hostname, port, user, password, database name
- [ ] Table: `product_outbox_events`
- [ ] Publication name: `dbz_publication`
- [ ] Slot name: `product_outbox_slot`
- [ ] Snapshot mode: `initial`
- [ ] Topic routing: map `product_outbox_events` table to topics:
  - Transform: `outbox` single message transform (SMT)
  - Route by field: `event_type` field
  - Route to `t.product.events` for product/SKU events
  - Route to `t.category.events` for category events
- [ ] Key: `aggregate_id` field
- [ ] Value: `payload` column as JSON
- [ ] Headers: `event_type`, `aggregate_type`, `partition_key`
- [ ] Consumer group offset tracking: enable

---

## 6. Outbox Retention Management

### 6.1 File: `worker/scheduler/OutboxRetentionJob.java`

**Path:** `product-service/src/main/java/com/platform/productservice/worker/scheduler/OutboxRetentionJob.java`

- [ ] Create `@Component` class
- [ ] Cron schedule: `0 0 3 * * SUN` (weekly, Sunday 3 AM)
- [ ] Inject `OutboxRepository`, `DistributedLockRunner`
- [ ] Logic: chunked deletion — `DELETE WHERE created_at < NOW() - 30 days LIMIT 10000`
- [ ] Loop until no more records to delete
- [ ] Wrap in distributed lock: key `product:lock:outbox-retention`
- [ ] Log: total records deleted, number of batches

---

## 7. Outbox Transaction Boundary

### 7.1 File: `service/TransactionalOutboxHelper.java`

**Path:** `product-service/src/main/java/com/platform/productservice/service/TransactionalOutboxHelper.java`

- [ ] Create `@Service` class
- [ ] Method `void saveEventInTransaction(DomainEvent event, String aggregateId, String partitionKey)`:
  - This method should be called within an existing `@Transactional` context
  - It does NOT start a new transaction
  - It inserts into `product_outbox_events` table
- [ ] Method `void saveEventsInTransaction(List<DomainEvent> events, List<String> aggregateIds, List<String> partitionKeys)`:
  - Batch insert for efficiency
  - All events are inserted in the same transaction as the domain operation

---

## 8. Kafka Configuration

### 8.1 File: `config/KafkaConfig.java`

**Path:** `product-service/src/main/java/com/platform/productservice/config/KafkaConfig.java`

- [ ] Configure `ConsumerFactory<String, String>` for generic String deserialization
- [ ] Configure `ProducerFactory<String, String>` for generic String serialization
- [ ] Configure `KafkaTemplate<String, String>`
- [ ] Configure `ConcurrentKafkaListenerContainerFactory` with:
  - `AckMode.RECORD` for event consumers
  - `Concurrency = 3` per consumer group
- [ ] Configure Dead Letter Queue (DLQ) properties:
  - Error handler with `DefaultErrorHandler`
  - Retry: 3 attempts with exponential backoff (1s, 2s, 4s)
  - After retries exhausted: send to `t.product.events.DLQ` topic
- [ ] Configure topic auto-creation and retention settings
- [ ] Configure interceptors: `MicrometerConsumerInterceptor`, `MicrometerProducerInterceptor`
- [ ] Configure `NewTopic` beans:
  - `t.product.events` (partitions: 12, replicas: 1)
  - `t.category.events` (partitions: 6, replicas: 1)
  - `t.product.events.DLQ` (partitions: 3, replicas: 1)

---

## 9. Acceptance Criteria

- [ ] Every domain write operation inserts outbox record in the same transaction
- [ ] Outbox events contain correct CloudEvents format with partition key
- [ ] Debezium CDC configuration routes events to correct topics based on event type
- [ ] Outbox retention job deletes records older than 30 days in chunks
- [ ] Kafka producer uses correct partition key for ordering
- [ ] Dead Letter Queue is configured for failed messages
- [ ] Kafka consumer factory is configured with correct concurrency and error handling
