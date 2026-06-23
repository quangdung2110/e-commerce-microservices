# Module 13: Observability & Tests

> **Status:** Module 13 of 14 - **NOT YET IMPLEMENTED**
> **Prerequisites:** All previous modules
> **Dependents:** Module 14 (Deployment)
> **Estimated Effort:** 2 person-days

> ## Implementation Note
> Chưa implement. Plan chi tiết bên dưới. Khi implement, đánh dấu `[x]` cho từng task.

---

## 1. Overview

This module configures:
- **Metrics (Bộ 5 essential metrics):** HTTP, ES query, Kafka lag, indexing, errors
- **Logging (Logback + JSON encoder + MDC):** Structured logs với `request_id`, `user_id`, `topic`, `partition`, `offset`, `event_type`
- **Health checks:** Spring Boot Actuator liveness + readiness
- **Testcontainers integration tests:** Real ES + Kafka containers cho end-to-end verification
- **Unit tests:** Pure Java (no Spring context) với Mockito cho ES client

---

## 2. Metrics Configuration

### 2.1 Bộ 5 Metrics Thiết Yếu

| Metric | Type | Labels | Description | Alert Threshold |
|---|---|---|---|---|
| `http_server_requests_seconds` | histogram | uri, method, status | HTTP request latency | p99 > 100ms (5xx), p99 > 200ms (search) |
| `es_query_duration_seconds` | histogram | index, query_type | ES query latency | p99 > 1s (search), p99 > 100ms (suggest) |
| `kafka_consumer_lag` | gauge | topic, partition, group | Kafka consumer lag | lag > 10000 records / 5 min |
| `search_indexing_total` | counter | event_type, status (success/fail) | Indexing success/fail count | fail rate > 1% trong 5 phút |
| `es_error_total` | counter | error_type (timeout/5xx/4xx) | ES error count | > 10 errors / phút |

### 2.2 Update: `application.yml`

```yaml
management:
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true
        es.query.duration: true
    tags:
      application: search-service
```

### 2.3 File: `config/MetricsConfig.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/config/MetricsConfig.java**

- [ ] Register custom `Timer` for ES query duration
- [ ] Register custom `Counter` for indexing success/fail
- [ ] Register custom `Counter` for ES errors
- [ ] Register custom `Gauge` for Kafka consumer lag (via Spring Kafka metrics)

```java
package com.ecommerce.search.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> commonTags() {
        return registry -> registry.config().commonTags("service", "search-service");
    }

    @Bean
    public MeterFilter meterFilter() {
        return MeterFilter.acceptNameStartsWith("search.");
    }
}
```

### 2.4 File: `util/MetricsRecorder.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/util/MetricsRecorder.java`

- [ ] Create @Component class wrapping Micrometer
- [ ] Methods: `recordEsQueryDuration(String index, String queryType, long millis)`
- [ ] Methods: `incrementIndexingSuccess(String eventType)`
- [ ] Methods: `incrementIndexingFailure(String eventType, String errorType)`
- [ ] Methods: `incrementEsError(String errorType)`
- [ ] Methods: `incrementEventIgnored(String topic, String eventType)`
- [ ] Methods: `incrementDocumentMissing(String topic, String eventType, String docId)`
- [ ] Methods: `incrementPayloadInvalid(String topic, String eventType)`
- [ ] Methods: `incrementCategoryEnrichmentMiss(String topic, String eventType, String categoryId)`

```java
package com.ecommerce.search.util;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class MetricsRecorder {

    private final MeterRegistry meterRegistry;

    public void recordEsQueryDuration(String index, String queryType, long millis) {
        Timer.builder("es.query.duration")
            .tag("index", index)
            .tag("query_type", queryType)
            .register(meterRegistry)
            .record(millis, TimeUnit.MILLISECONDS);
    }

    public void incrementIndexingSuccess(String eventType) {
        Counter.builder("search.indexing.total")
            .tag("event_type", eventType)
            .tag("status", "success")
            .register(meterRegistry)
            .increment();
    }

    public void incrementIndexingFailure(String eventType, String errorType) {
        Counter.builder("search.indexing.total")
            .tag("event_type", eventType)
            .tag("status", "fail")
            .tag("error_type", errorType)
            .register(meterRegistry)
            .increment();
    }

    public void incrementEsError(String errorType) {
        Counter.builder("es.error.total")
            .tag("error_type", errorType)
            .register(meterRegistry)
            .increment();
    }

    public void incrementEventIgnored(String topic, String eventType) {
        Counter.builder("search.event.ignored.total")
            .tag("topic", topic)
            .tag("event_type", eventType)
            .register(meterRegistry)
            .increment();
    }

    public void incrementDocumentMissing(String topic, String eventType, String docId) {
        Counter.builder("search.document.missing.total")
            .tag("topic", topic)
            .tag("event_type", eventType)
            .tag("doc_id", docId)
            .register(meterRegistry)
            .increment();
    }

    public void incrementPayloadInvalid(String topic, String eventType) {
        Counter.builder("search.payload.invalid.total")
            .tag("topic", topic)
            .tag("event_type", eventType)
            .register(meterRegistry)
            .increment();
    }

    public void incrementCategoryEnrichmentMiss(String topic, String eventType, String categoryId) {
        Counter.builder("search.category.enrichment.miss.total")
            .tag("topic", topic)
            .tag("event_type", eventType)
            .tag("category_id", categoryId)
            .register(meterRegistry)
            .increment();
    }
}
```

### 2.5 Integrate MetricsRecorder in Event Handlers

Update `ProductEventHandlers` to call `metricsRecorder.incrementIndexingSuccess(eventType)` after each successful index, etc.

---

## 3. Logging Configuration

### 3.1 Update: `logback-spring.xml` (Created in Module 01)

Already defined. Verify MDC keys: `request_id`, `user_id`, `topic`, `partition`, `offset`, `event_type`.

### 3.2 Logback Async Appender (Optional)

For performance, wrap console appender in async appender to avoid blocking:

```xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} [request_id=%X{request_id}, user_id=%X{user_id}, topic=%X{topic}, partition=%X{partition}, offset=%X{offset}, event_type=%X{event_type}] - %msg%n</pattern>
        </encoder>
    </appender>

    <appender name="ASYNC_STDOUT" class="ch.qos.logback.classic.AsyncAppender">
        <queueSize>2048</queueSize>
        <discardingThreshold>0</discardingThreshold>
        <neverBlock>false</neverBlock>
        <appender-ref ref="STDOUT"/>
    </appender>

    <root level="INFO">
        <appender-ref ref="ASYNC_STDOUT"/>
    </root>
</configuration>
```

---

## 4. Health Checks

### 4.1 Custom Health Indicators (Optional)

Spring Boot Actuator tự động check ES và Kafka health. Custom health indicators nếu cần:

```java
@Component
public class SearchReadinessHealthIndicator implements HealthIndicator {
    @Override
    public Health health() {
        // Check if ES is reachable
        // Check if Kafka consumer is subscribed
        // Check if recent events processed (no lag > 1 min)
        return Health.up().build();
    }
}
```

**Decision:** Use default Spring Boot Actuator health checks (ES + Kafka). Custom health indicators optional.

---

## 5. Testing Strategy

### 5.1 Unit Tests

**Pattern:** Pure Java + Mockito. Không load Spring context. Coverage target: >80% cho `service/`, `util/`, `worker/`, `repository/`.

#### Example: `SortMapperTest.java`

```java
class SortMapperTest {
    private final SortMapper sortMapper = new SortMapper();

    @Test
    void priceAscHasTieBreak() {
        List<SortOptions> result = sortMapper.map("price_asc");
        assertThat(result).hasSize(2);
        // First: best_price asc, Second: sku_id asc
    }
}
```

### 5.2 Integration Tests (Testcontainers)

**Pattern:** `@SpringBootTest` + `@Testcontainers` với real ES + Kafka containers.

#### File: `ProductSearchIT.java`

**Path:** `java/services/search-service/src/test/java/com/ecommerce/search/integration/ProductSearchIT.java`

- [ ] @SpringBootTest
- [ ] @Testcontainers
- [ ] ElasticsearchContainer (8.11.0)
- [ ] KafkaContainer (cp-kafka 7.5.0)
- [ ] Test: `GET /api/v1/search/products?q=iphone` returns correct results
- [ ] Test: cursor pagination works
- [ ] Test: suggest API works
- [ ] Test: facets API works

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class ProductSearchIT {

    @Container
    static ElasticsearchContainer ES = new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:8.11.0")
        .withEnv("xpack.security.enabled", "false");

    @Container
    static KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.elasticsearch.uris", ES::getHttpHostAddress);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private KafkaTemplate<String, CloudEventEnvelope> kafkaTemplate;

    @Test
    void productSearchWithTextQuery() {
        // Index a product first
        ProductDocument doc = ...;
        productRepository.index(doc);

        // Call API
        ResponseEntity<ProductSearchResponse> response = restTemplate.getForEntity(
            "/api/v1/search/products?q=iphone", ProductSearchResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().items()).isNotEmpty();
    }

    @Test
    void cursorPaginationWorks() {
        // Index 50 products
        for (int i = 0; i < 50; i++) {
            productRepository.index(...);
        }

        // First page
        ResponseEntity<ProductSearchResponse> firstPage = restTemplate.getForEntity(
            "/api/v1/search/products?size=20", ProductSearchResponse.class);
        String cursor = firstPage.getBody().nextCursor();
        assertThat(cursor).isNotNull();

        // Second page
        ResponseEntity<ProductSearchResponse> secondPage = restTemplate.getForEntity(
            "/api/v1/search/products?size=20&cursor=" + cursor, ProductSearchResponse.class);
        assertThat(secondPage.getBody().items()).isNotEmpty();
    }
}
```

### 5.3 Worker Integration Tests

#### File: `ProductIndexingConsumerIT.java`

- [ ] Test: produce PRODUCT_CREATED event → verify ES doc indexed
- [ ] Test: produce 100 events in batch → verify all processed
- [ ] Test: invalid event_type → verify metric incremented
- [ ] Test: invalid JSON → verify offset committed (poison skipped)
- [ ] Test: ES down → verify retry with exponential backoff

```java
@SpringBootTest
@Testcontainers
class ProductIndexingConsumerIT {
    @Container static KafkaContainer KAFKA = ...;

    @Autowired private KafkaTemplate<String, CloudEventEnvelope> kafkaTemplate;

    @Autowired private ElasticsearchClient esClient;

    @Test
    void productCreatedEventIsIndexed() throws Exception {
        // 1. Send PRODUCT_CREATED event
        kafkaTemplate.send("t.product.events", "prod-1", buildProductCreatedEvent());

        // 2. Wait for processing
        await().atMost(10, SECONDS).untilAsserted(() -> {
            ProductDocument doc = esClient.get(b -> b.index("products").id("sku-1"), ProductDocument.class).source();
            assertThat(doc).isNotNull();
        });
    }
}
```

### 5.4 Test Configuration

#### File: `src/test/resources/application-test.yml`

```yaml
spring:
  elasticsearch:
    uris: http://localhost:9200
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      auto-offset-reset: earliest
      group-id: search-product-indexer-test

search:
  jwt:
    trust-gateway: true
    claims-header: X-User-Claims
  cors:
    allowed-origins: "*"
```

---

## 6. Acceptance Criteria

- [ ] Prometheus metrics exposed at `/actuator/prometheus`
- [ ] 5 essential metrics work: HTTP, ES query, Kafka lag, indexing, errors
- [ ] Logs are JSON format in prod profile với MDC context
- [ ] Health check `/actuator/health/liveness` returns 200 when JVM alive
- [ ] Health check `/actuator/health/readiness` returns 200 when ES + Kafka OK
- [ ] Unit tests pass: SortMapper, CursorCodec, DiscountCalculator, EventHandlers
- [ ] Integration tests pass: ProductSearchIT, ProductIndexingConsumerIT
- [ ] Test coverage >80% for service/, util/, worker/, repository/

---

## 7. Implementation File Index

| # | File | Status |
|---|------|--------|
| 1 | `config/MetricsConfig.java` | ⏳ Not yet |
| 2 | `util/MetricsRecorder.java` | ⏳ Not yet |
| 3 | `application.yml` (add metrics config) | ⏳ Not yet |
| 4 | `application-test.yml` | ⏳ Not yet |
| 5 | `integration/ProductSearchIT.java` | ⏳ Not yet |
| 6 | `integration/ProductIndexingConsumerIT.java` | ⏳ Not yet |
| 7 | `integration/CategoryServiceIT.java` | ⏳ Not yet |
| 8 | `integration/ReindexControllerIT.java` | ⏳ Not yet |
| 9 | Various unit tests (SortMapperTest, CursorCodecTest, etc.) | ⏳ Not yet |
