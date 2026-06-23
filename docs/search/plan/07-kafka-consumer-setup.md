# Module 07: Kafka Consumer Setup

> **Status:** Module 7 of 14 - **NOT YET IMPLEMENTED**
> **Prerequisites:** Module 02 (Domain Model — CloudEventEnvelope, event records)
> **Dependents:** Module 08 (Event Handler — uses this consumer)
> **Estimated Effort:** 1 person-day

> ## Implementation Note
> Chưa implement. Plan chi tiết bên dưới. Khi implement, đánh dấu `[x]` cho từng task.

---

## 1. Overview

This module configures the Kafka consumer infrastructure:
- **KafkaConsumerConfig:** Spring Kafka config (consumer factory, listener container factory, concurrency 5)
- **ProductIndexingConsumer:** Long-running consumer worker, poll batch 200-500 records, dispatch to `EventHandler`
- **Error Handling Policy:** Phase 22b — no DLQ. Transient → nack + Kafka retry. Non-recoverable → log + metric + skip + ACK.

---

## 2. Kafka Consumer Configuration

### 2.1 File: `worker/KafkaConsumerConfig.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/worker/KafkaConsumerConfig.java`

- [ ] Create `@Configuration` class
- [ ] Configure `ConsumerFactory<String, CloudEventEnvelope>` bean
  - Bootstrap servers from `spring.kafka.bootstrap-servers`
  - Group id = `search-product-indexer`
  - Auto offset reset = `earliest`
  - Key deserializer = StringDeserializer
  - Value deserializer = JsonDeserializer<CloudEventEnvelope>
  - Trusted packages: `com.ecommerce.common.events,com.ecommerce.search.domain.event`
  - Default type: `com.ecommerce.common.events.CloudEventEnvelope`
  - Max poll records: 500
- [ ] Configure `ConcurrentKafkaListenerContainerFactory<String, CloudEventEnvelope>` bean
  - Concurrency = 5 (1 thread per partition; 3 partitions product + 1 category + 1 review)
  - AckMode = MANUAL_IMMEDIATE
  - Enable batch listener
  - Custom error handler (transient → retry, non-recoverable → skip)

```java
package com.ecommerce.search.worker;

import com.ecommerce.common.events.CloudEventEnvelope;
import com.ecommerce.search.config.SearchProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Configuration
@EnableKafka
@RequiredArgsConstructor
public class KafkaConsumerConfig {

    private final KafkaProperties kafkaProperties;
    private final SearchProperties searchProperties;

    public static final String CONSUMER_GROUP_ID = "search-product-indexer";
    public static final int CONCURRENCY = 5;  // 1 thread per partition × 5 partitions

    @Bean
    public ConsumerFactory<String, CloudEventEnvelope> consumerFactory() {
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties(null));

        props.put(ConsumerConfig.GROUP_ID, CONSUMER_GROUP_ID);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT, false);
        props.put(ConsumerConfig.MAX_POLL_RECORDS, 500);
        props.put(ConsumerConfig.KEY_DESERIALIZER, org.apache.kafka.common.serialization.StringDeserializer.class);

        // Use ErrorHandlingDeserializer wrapping JsonDeserializer for resilience
        props.put(ConsumerConfig.VALUE_DESERIALIZER, ErrorHandlingDeserializer.class);
        props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.ecommerce.common.events,com.ecommerce.search.domain.event");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, "com.ecommerce.common.events.CloudEventEnvelope");
        props.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);

        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, CloudEventEnvelope> kafkaListenerContainerFactory(
            ConsumerFactory<String, CloudEventEnvelope> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, CloudEventEnvelope> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(CONCURRENCY);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setBatchListener(true);

        // Error handler: retry transient errors, give up on non-recoverable
        // Phase 22b: No DLQ. Log + metric + skip + ACK for non-recoverable.
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
            (rec, ex) -> {
                // Non-recoverable error → log + skip + ACK
                log.error("Non-recoverable error processing event, skipping. topic={}, partition={}, offset={}, key={}",
                    rec.topic(), rec.partition(), rec.offset(), rec.key(), ex);
                // TODO: increment search_event_ignored_total or similar metric
            },
            new ExponentialBackOff(1000L, 2.0)  // 1s, 2s, 4s, 8s, 16s, 32s, 64s, 128s, 256s, 300s (max 5 min)
        );
        errorHandler.setCommitRecovered(true);  // commit offset after error handler runs
        errorHandler.setLogLevel(org.springframework.kafka.KafkaException.Level.WARN);

        factory.setCommonErrorHandler(errorHandler);

        return factory;
    }
}
```

> **Quan trọng:** `ErrorHandlingDeserializer` wrapping `JsonDeserializer` để handle payload poison (invalid JSON). Phase 22b: no DLQ — non-recoverable errors are logged + skipped + ACK.

---

## 3. Reindex Consumer Group (Temporary)

### 3.1 File: `worker/ReindexConsumerConfig.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/worker/ReindexConsumerConfig.java`

- [ ] Create `@Configuration` class cho temporary consumer group khi reindex
- [ ] Bean: `ConsumerFactory` với group id = `search-reindex-{run_id}` (dynamic, tạo runtime)
- [ ] Bean: `ConcurrentKafkaListenerContainerFactory` riêng cho reindex (không dùng chung với main)
- [ ] Bean: `KafkaListenerEndpointRegistry` quản lý lifecycle (start/stop theo run_id)

---

## 4. Product Indexing Consumer

### 4.1 File: `worker/ProductIndexingConsumer.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/worker/ProductIndexingConsumer.java`

- [ ] Create `@Component` class
- [ ] Inject `EventHandler` (Module 08)
- [ ] Method: `@KafkaListener` cho batch processing
- [ ] Poll batch 200-500 records (config)
- [ ] Validate `event_type` nằm trong allowlist
- [ ] Nếu `event_type` ngoài allowlist → ignore + ACK + ghi metric `search_event_ignored_total{topic,event_type}`
- [ ] Dispatch theo `event_type` qua `EventHandler.handle(envelope)`
- [ ] Manual ACK sau khi batch processed thành công

```java
package com.ecommerce.search.worker;

import com.ecommerce.common.events.CloudEventEnvelope;
import com.ecommerce.search.common.EventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProductIndexingConsumer {

    private final EventHandler eventHandler;

    @KafkaListener(
        topics = {"${spring.kafka.topics.product:product-events}",
                  "${spring.kafka.topics.category:category-events}",
                  "${spring.kafka.topics.review:review-events}"},
        containerFactory = "kafkaListenerContainerFactory",
        groupId = KafkaConsumerConfig.CONSUMER_GROUP_ID
    )
    public void onBatch(List<ConsumerRecord<String, CloudEventEnvelope>> records, Acknowledgment ack) {
        log.debug("Received batch of {} records", records.size());

        int successCount = 0;
        int errorCount = 0;
        int ignoredCount = 0;

        for (ConsumerRecord<String, CloudEventEnvelope> record : records) {
            try {
                CloudEventEnvelope envelope = record.value();
                if (envelope == null) {
                    log.warn("Null envelope at offset={}, skipping", record.offset());
                    ignoredCount++;
                    continue;
                }

                String eventType = envelope.eventType();
                EventType type = EventType.fromString(eventType);

                if (type == null) {
                    log.warn("Event type not in allowlist, ignoring. topic={}, eventType={}, offset={}",
                        record.topic(), eventType, record.offset());
                    // TODO: increment search_event_ignored_total{topic, eventType} metric
                    ignoredCount++;
                    continue;
                }

                // Dispatch to EventHandler (Module 08)
                eventHandler.handle(envelope);
                successCount++;

            } catch (Exception e) {
                // Transient error → throw to let Spring Kafka retry policy handle
                // Non-recoverable error → caught by error handler in KafkaConsumerConfig
                log.error("Failed to process record at offset={}, partition={}",
                    record.offset(), record.partition(), e);
                errorCount++;
                // Don't rethrow — let error handler decide
            }
        }

        // Manual ACK after batch
        ack.acknowledge();

        log.info("Batch processed: total={}, success={}, error={}, ignored={}",
            records.size(), successCount, errorCount, ignoredCount);
    }
}
```

> **Lưu ý:** Không throw exception cho individual record errors. Error handler của `KafkaListenerContainerFactory` sẽ quyết định retry hoặc skip.

---

## 5. EventType Allowlist (Helper)

### 5.1 Update: `common/EventType.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/common/EventType.java`

- [ ] Add method: `static EventType fromString(String name)` — parse từ string, return null nếu không hợp lệ
- [ ] Add method: `boolean isAllowedInConsumerGroup()` — check if event type nằm trong allowlist

```java
package com.ecommerce.search.common;

import java.util.Arrays;

public enum EventType {
    PRODUCT_CREATED("t.product.events", true),
    PRODUCT_UPDATED("t.product.events", true),
    PRODUCT_STATUS_CHANGED("t.product.events", false),  // Phase 19: no longer emitted, kept for legacy
    SKU_UPDATED("t.product.events", true),
    SKU_CREATED("t.product.events", true),
    PRODUCT_DELETED("t.product.events", true),
    SKU_DELETED("t.product.events", true),
    CATEGORY_CREATED("t.category.events", true),
    CATEGORY_UPDATED("t.category.events", true),
    CATEGORY_DELETED("t.category.events", true),
    REVIEW_METRICS_UPDATED("t.review.events", true);

    private final String topic;
    private final boolean allowedInConsumerGroup;

    EventType(String topic, boolean allowedInConsumerGroup) {
        this.topic = topic;
        this.allowedInConsumerGroup = allowedInConsumerGroup;
    }

    public String topic() { return topic; }

    public boolean isAllowedInConsumerGroup() {
        return allowedInConsumerGroup;
    }

    public static EventType fromString(String name) {
        if (name == null) return null;
        return Arrays.stream(values())
            .filter(e -> e.name().equals(name))
            .findFirst()
            .orElse(null);
    }
}
```

---

## 6. Configuration Properties Update

### 6.1 Update: `config/SearchProperties.java`

Add Kafka topic names config:

```java
@Data
@Validated
@ConfigurationProperties(prefix = "search")
public class SearchProperties {
    // ... existing fields ...
    @NotNull
    private Topics topics = new Topics();

    @Data
    public static class Topics {
        @NotBlank private String product = "t.product.events";
        @NotBlank private String category = "t.category.events";
        @NotBlank private String review = "t.review.events";
    }
}
```

### 6.2 Update: `application.yml`

```yaml
search:
  topics:
    product: t.product.events
    category: t.category.events
    review: t.review.events
```

---

## 7. Health Check Integration

### 7.1 Verify Health Endpoint

Kafka consumer health được Spring Boot Actuator tự động check:
- `management.health.kafka.enabled: true` (đã config trong Module 01)
- `/actuator/health/readiness` sẽ trả 503 nếu Kafka connection fail

---

## 8. Error Handling Policy (Phase 22b - No DLQ)

| Error Type | Handling | Result |
|---|---|---|
| **Transient** (ES timeout, 429, 503, network) | Throw → Spring Kafka retry with exponential backoff (1s, 2s, 4s, ..., max 5 phút) → re-process record | NACK → Kafka redelivers after retry |
| **Payload poison** (invalid JSON, missing required field) | `ErrorHandlingDeserializer` catches → log + metric + skip + ACK | Commit offset, move on |
| **Mapping conflict** (ES schema mismatch) | `EventHandler` catches → log + metric `search_payload_invalid_total{topic,event_type}` + skip + ACK | Commit offset, move on |
| **`document_missing_exception`** (partial update miss) | `EventHandler` catches → log + metric `search_document_missing_total{topic,event_type,doc_id}` + skip + ACK | Commit offset, move on |
| **Category lookup miss** | `EventHandler` catches → log + metric `search_category_enrichment_miss_total{topic,event_type,category_id}` + skip enrichment (write doc without category_name/path) | Commit offset, move on |
| **Unknown `event_type`** (not in allowlist) | `ProductIndexingConsumer` catches → log + metric `search_event_ignored_total{topic,event_type}` + ignore + ACK | Commit offset, move on |
| **Cluster ES down** | All operations fail with timeout | Log liên tục + metric tăng theo thời gian (alert via dashboard) |

> **Tại sao KHÔNG có DLQ (Phase 22b):** Xem entry [CHANGELOG §Phase 22b Search](../../_contracts/CHANGELOG.md#phase-22b--search-service--remove-dlq). Tóm tắt: single-admin system, ít downstream consumers, manual recovery via log + Full Reindex Job.

---

## 9. Acceptance Criteria

- [ ] Kafka consumer connects tới Kafka cluster on startup
- [ ] 5 threads poll batches concurrently (1 per partition)
- [ ] Each batch processes 200-500 records
- [ ] Event type allowlist: 10 types (PRODUCT_*, SKU_*, CATEGORY_*, REVIEW_METRICS_UPDATED)
- [ ] Manual ACK after successful batch
- [ ] Transient errors trigger exponential backoff retry
- [ ] Non-recoverable errors are logged + metric + skipped + ACK (no infinite loop)
- [ ] `document_missing_exception` doesn't crash consumer
- [ ] Health check `/actuator/health/readiness` returns 200 when Kafka + ES OK
- [ ] Concurrent batch processing doesn't break ordering per partition
- [ ] Metrics: `search_event_ignored_total`, `search_payload_invalid_total`, etc.

---

## 10. Integration Test

### 10.1 File: `worker/ProductIndexingConsumerIT.java`

**Path:** `java/services/search-service/src/test/java/com/ecommerce/search/worker/ProductIndexingConsumerIT.java`

- [ ] `@SpringBootTest` + `@Testcontainers` (Kafka + ES)
- [ ] Test: produce `PRODUCT_CREATED` event → verify ES doc indexed
- [ ] Test: produce event với invalid `event_type` → verify metric incremented
- [ ] Test: produce event với invalid JSON → verify offset committed (poison skipped)
- [ ] Test: produce `REVIEW_METRICS_UPDATED` → verify doc updated via `_update_by_query`
- [ ] Test: produce 100 events in batch → verify all processed

---

## 11. Implementation File Index

| # | File | Status |
|---|------|--------|
| 1 | `worker/KafkaConsumerConfig.java` | ⏳ Not yet |
| 2 | `worker/ReindexConsumerConfig.java` | ⏳ Not yet |
| 3 | `worker/ProductIndexingConsumer.java` | ⏳ Not yet |
| 4 | `common/EventType.java` (update with `fromString`) | ⏳ Not yet |
| 5 | `config/SearchProperties.java` (add Topics nested class) | ⏳ Not yet |
| 6 | `application.yml` (add search.topics) | ⏳ Not yet |
| 7 | `worker/ProductIndexingConsumerIT.java` | ⏳ Not yet |
