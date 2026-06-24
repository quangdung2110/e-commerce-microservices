# Search Service - Overview (Tech Stack, Deployment, Observability)

**Liên kết nhanh (Related Documents):**
*   [Elasticsearch Schema (Read Store)](search_elasticsearch_schema.md)
*   [Workflows (API & Data Flow)](search_workflow.md)
*   [Background Workers & Jobs](search_workers.md)
*   [Events Consumed & Kafka Topics](search_events.md)
*   [Architecture Recommendations](search_architecture_recommendations.md)

**Cross-Service References:**
*   [Service Contract Matrix (SSOT)](../_contracts/CONTRACT_MATRIX.md)
*   [Idempotency Guidelines (SSOT)](../_contracts/IDEMPOTENCY_GUIDELINES.md)
*   [CHANGELOG](../_contracts/CHANGELOG.md)

---

Tài liệu này tổng hợp các quyết định kỹ thuật (tech stack, deployment, observability) cho Search Service. Mục đích là cung cấp 1 file "entry point" ngắn gọn cho engineer mới onboard vào service, đồng thời là reference nhanh cho mọi quyết định vận hành.

Các quyết định kiến trúc business (CQRS, denormalization, consumer groups, etc.) xem tại [Architecture Recommendations](search_architecture_recommendations.md).

## 1. Tech Stack

| Layer | Technology | Version | Ghi chú |
|---|---|---|---|
| **Language** | Java | 17 (LTS) | Đồng nhất với Product/Review Service |
| **Framework** | Spring Boot | 3.x | Spring Boot 3 + Jakarta EE 9+ |
| **Build tool** | Maven | 3.9+ | Multi-module project structure |
| **ES client** | Elasticsearch Java API Client | 8.x (match ES version) | Official, async/reactive, future-proof |
| **Kafka client** | Spring Kafka | 3.x | Spring abstraction cho Kafka |
| **Boilerplate reduction** | Lombok | 1.18+ | POJO/DTO/Entity |
| **Logging** | Logback (Spring Boot default) | 1.4+ | JSON encoder cho production, MDC context |
| **Metrics** | Micrometer | 1.12+ | Prometheus registry |
| **Health check** | Spring Boot Actuator | bundled | Liveness + Readiness endpoints |
| **JSON** | Jackson (Spring Boot default) | 2.15+ | ISO 8601 dates, snake_case cho API response |
| **Testcontainers** | testcontainers-java | 1.19+ | ES + Kafka containers cho integration test |
| **OpenAPI** | springdoc-openapi | 2.x | OpenAPI 3.0 spec generation (no Swagger UI) |

**Lý do chọn Elasticsearch Java API Client (8.x):**
- Official, được Elastic maintain, async/reactive API.
- Hỗ trợ ES 8.x features (PIT, runtime fields, etc.).
- Spring Boot 3.x tích hợp tốt.
- Type-safe với Java records.

## 2. Project Structure

```
search-service/
├── pom.xml                                     # Maven config (Spring Boot 3, Java 17)
├── docker-compose.yml                          # Local dev stack (ES + Kafka)
├── Dockerfile                                  # Multi-stage build
├── README.md                                   # Quick start
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com.ecommerce.search/
│   │   │       ├── SearchServiceApplication.java
│   │   │       ├── config/
│   │   │       │   ├── ElasticsearchConfig.java        # ES client bean
│   │   │       │   ├── KafkaConsumerConfig.java        # consumer group + concurrency 5
│   │   │       │   ├── SecurityConfig.java             # CORS + headers
│   │   │       │   └── ObservabilityConfig.java        # metrics + logging
│   │   │       ├── controller/
│   │   │       │   ├── ProductSearchController.java    # /api/v1/search/products
│   │   │       │   ├── SuggestController.java          # /api/v1/search/suggest
│   │   │       │   ├── CategoryController.java         # /api/v1/search/categories
│   │   │       │   ├── FacetController.java            # /api/v1/search/facets/{id}
│   │   │       │   └── internal/
│   │   │       │       └── ReindexController.java      # /internal/v1/search/reindex
│   │   │       ├── service/
│   │   │       │   ├── ProductSearchService.java
│   │   │       │   ├── SuggestService.java
│   │   │       │   ├── CategoryService.java
│   │   │       │   └── FacetService.java
│   │   │       ├── worker/
│   │   │       │   ├── ProductIndexingWorker.java      # Kafka consumer (5 threads)
│   │   │       │   ├── EventHandler.java               # dispatch theo event_type
│   │   │       │   ├── SuggestionRebuilder.java        # daily cronjob
│   │   │       │   ├── DraftCleanupJob.java            # off-peak cronjob
│   │   │       │   └── ReindexJob.java                 # 3-pha zero-downtime
│   │   │       ├── repository/
│   │   │       │   ├── ProductSearchRepository.java    # ES query builder
│   │   │       │   ├── SuggestRepository.java
│   │   │       │   └── CategoryRepository.java
│   │   │       ├── domain/
│   │   │       │   ├── ProductDocument.java            # ES mapping record
│   │   │       │   ├── CategoryDocument.java
│   │   │       │   └── SuggestionDocument.java
│   │   │       ├── dto/
│   │   │       │   ├── ProductSearchResponse.java
│   │   │       │   ├── SuggestResponse.java
│   │   │       │   ├── CategoryTreeResponse.java
│   │   │       │   ├── FacetResponse.java
│   │   │       │   ├── ReindexTriggerResponse.java
│   │   │       │   ├── ReindexStatusResponse.java
│   │   │       │   └── ErrorResponse.java
│   │   │       ├── exception/
│   │   │       │   ├── GlobalExceptionHandler.java
│   │   │       │   ├── SearchException.java
│   │   │       │   └── InvalidCursorException.java
│   │   │       └── util/
│   │   │           ├── CursorCodec.java                # base64(JSON([sort_value, sku_id]))
│   │   │           └── JwtClaimUtil.java               # đọc X-User-Claims header
│   │   └── resources/
│   │       ├── application.yml                        # default config
│   │       ├── application-dev.yml                    # dev profile
│   │       ├── application-prod.yml                   # prod profile
│   │       ├── logback-spring.xml                     # JSON logging config
│   │       └── es-scripts/                            # painless scripts (init.d)
│   │           ├── product_patch.painless
│   │           ├── product_status_changed.painless
│   │           └── category_count_increment.painless
│   └── test/
│       ├── java/
│       │   └── com.ecommerce.search/
│       │       ├── integration/
│       │       │   ├── ProductSearchIT.java            # @Testcontainers ES + Kafka
│       │       │   ├── ProductIndexingWorkerIT.java
│       │       │   ├── SuggestControllerIT.java
│       │       │   └── ReindexControllerIT.java
│       │       └── unit/
│       │           ├── CursorCodecTest.java
│       │           ├── EventHandlerTest.java
│       │           └── JwtClaimUtilTest.java
│       └── resources/
│           └── application-test.yml
```

## 3. Configuration

### 3.1. application.yml (default)

```yaml
spring:
  application:
    name: search-service
  main:
    web-application-type: servlet
  elasticsearch:
    uris: ${ES_URIS:http://localhost:9200}
    connection-timeout: 5s
    socket-timeout: 30s
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP:localhost:9092}
    consumer:
      group-id: search-product-indexer
      auto-offset-reset: earliest
      max-poll-records: 500
      concurrency: 5
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: com.ecommerce.common.events
        spring.json.value.default.type: com.ecommerce.common.events.CloudEventEnvelope
  lifecycle:
    timeout-per-shutdown-phase: 30s

server:
  port: 8080
  shutdown: graceful

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    health:
      probes:
        enabled: true
      show-details: when_authorized
  health:
    elasticsearch:
      enabled: true
    kafka:
      enabled: true
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true
        es.query.duration: true

search:
  jwt:
    trust-gateway: true
    claims-header: X-User-Claims
  es:
    query-timeout-product-ms: 5000
    query-timeout-suggest-ms: 2000
    update-by-query-throttle-rps: 300
    index-aliases:
      products: products
      products-write: products_write
      categories: categories
      categories-write: categories_write
      suggestions: search_suggestions
  cors:
    allowed-origins: "*"
  pagination:
    default-size: 20
    max-size: 100

logging:
  level:
    root: INFO
    com.ecommerce.search: DEBUG
    org.elasticsearch.client: INFO
```

### 3.2. Environment Variables (Production)

```bash
# Elasticsearch
ES_URIS=https://es-cluster:9200
ES_USERNAME=search-service
ES_PASSWORD=<secret>

# Kafka
KAFKA_BOOTSTRAP=kafka:9092
KAFKA_SASL_USERNAME=search-service
KAFKA_SASL_PASSWORD=<secret>

# Spring profile
SPRING_PROFILES_ACTIVE=prod
```

## 4. Deployment (Docker)

### 4.1. Dockerfile (Multi-stage)

```dockerfile
# Build stage
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

# Runtime stage
FROM eclipse-temurin:17-jre-alpine
RUN adduser -D -u 1001 search && \
    apk add --no-cache curl
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
USER search
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["java", \
    "-XX:+UseG1GC", \
    "-XX:MaxRAMPercentage=75.0", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
```

### 4.2. Local Development (docker-compose.yml)

```yaml
version: '3.8'
services:
  elasticsearch:
    image: docker.elastic.co/elasticsearch/elasticsearch:8.11.0
    environment:
      - discovery.type=single-node
      - xpack.security.enabled=false
      - ES_JAVA_OPTS=-Xms512m -Xmx512m
    ports:
      - "9200:9200"
    healthcheck:
      test: ["CMD-SHELL", "curl -f http://localhost:9200/_cluster/health || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 10
    volumes:
      - es-data:/usr/share/elasticsearch/data

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    depends_on:
      - zookeeper
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
    ports:
      - "9092:9092"

  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
    ports:
      - "2181:2181"

  search-service:
    build: .
    depends_on:
      elasticsearch:
        condition: service_healthy
      kafka:
        condition: service_started
    environment:
      ES_URIS: http://elasticsearch:9200
      KAFKA_BOOTSTRAP: kafka:9092
      SPRING_PROFILES_ACTIVE: dev
    ports:
      - "8080:8080"

volumes:
  es-data:
```

### 4.3. Production Deployment (Docker)

Triển khai dạng Docker container với reverse proxy (Traefik/Nginx) phía trước:

```
[Internet] → [Traefik/Nginx] → [search-service:8080]
                                    ↓
                          [Elasticsearch cluster]
                          [Kafka cluster]
```

**Resource limits (single container):**
- CPU: 1-2 cores
- RAM: 1-2 GB
- JVM heap: 75% RAM (~750MB - 1.5GB)

**Scaling:** Stateless service, scale horizontal bằng cách chạy nhiều replicas. Kafka consumer group rebalance tự động phân phối partitions.

## 5. Observability

### 5.1. Metrics (Bộ 5 metrics thiết yếu)

| Metric Name | Type | Labels | Mô tả | Alert threshold |
|---|---|---|---|---|
| `http_server_requests_seconds` | histogram | `uri`, `method`, `status` | HTTP request latency | p99 > 100ms (5xx), p99 > 200ms (search) |
| `es_query_duration_seconds` | histogram | `index`, `query_type` | ES query latency | p99 > 1s (search), p99 > 100ms (suggest) |
| `kafka_consumer_lag` | gauge | `topic`, `partition`, `group` | Kafka consumer lag | lag > 10000 records / 5 phút |
| `search_indexing_total` | counter | `event_type`, `status` (success/fail) | Indexing success/fail count | fail rate > 1% trong 5 phút |
| `es_error_total` | counter | `error_type` (timeout/5xx/4xx) | ES error count | > 10 errors / phút |

**Expose:** `GET /actuator/prometheus` (scrape bởi Prometheus).

### 5.2. Logging

**Format:**
- **Dev:** Plain text (mặc định Spring Boot).
- **Prod:** JSON (qua `logback-spring.xml` + `logstash-logback-encoder`).

**MDC context (mỗi log line):**
- `request_id` (từ header `X-Request-Id` hoặc sinh UUID)
- `user_id` (từ JWT claim)
- `topic` (Kafka consumer log)
- `partition` (Kafka consumer log)
- `offset` (Kafka consumer log)
- `event_type` (worker log)

**Sample log line (JSON):**
```json
{
  "timestamp": "2026-06-22T15:30:01.123Z",
  "level": "INFO",
  "logger": "com.ecommerce.search.worker.EventHandler",
  "message": "Indexed SKU document",
  "request_id": "req-uuid-12345",
  "user_id": "550e8400-e29b-41d4-a716-446655440000",
  "topic": "t.product.events",
  "partition": 2,
  "offset": 12345,
  "event_type": "SKU_UPDATED",
  "sku_id": "sku-uuid-001",
  "duration_ms": 12
}
```

### 5.3. Health Checks (K8s liveness/readiness)

- **Liveness:** `GET /actuator/health/liveness` — 200 nếu process alive.
- **Readiness:** `GET /actuator/health/readiness` — 200 nếu ES connect OK + Kafka consumer subscribed.

Xem chi tiết tại [Search Workflow §0.4](search_workflow.md#04-health-check-endpoints).

### 5.4. Distributed Tracing

**Hiện tại:** Request-id only (đơn giản). Log correlation qua MDC `request_id`. Không có OpenTelemetry SDK.

**Future:** Nếu cần trace nội bộ (ES query span, Kafka consumer span), thêm OpenTelemetry SDK + OTLP exporter.

## 6. Capacity Planning

**Current scale (ước tính Phase 1):**
- Catalog: ~100K SKU, ~10K product
- Traffic: ~100 req/s peak (search + suggest)
- Index size: ~5GB total

**Production scale (Phase 2+):**
- Catalog: ~1M SKU
- Traffic: ~1K req/s peak
- Index size: ~50GB total
- Shard/Index config: 3 shards, 1 replica (xem [Elasticsearch Schema §5](search_elasticsearch_schema.md#5-scaling-elasticsearch-cluster))

## 7. Shutdown Strategy

**Spring Boot graceful shutdown (30s):**
- Tomcat dừng nhận request mới.
- Request in-flight có tối đa 30s để hoàn thành.
- Sau 30s → SIGKILL.

**Kafka consumer:**
- Consumer dừng poll batch mới.
- Commit offset cho batch hiện tại.
- Đóng Kafka client.

**ES client:**
- Đóng connection pool.

## 8. Error Handling

### 8.1. API Error Format (Canonical)

Mọi error response theo format [Search Workflow §0.3](search_workflow.md#03-error-response-format-canonical):

```json
{
  "error_code": "INVALID_QUERY_PARAMETER",
  "message": "...",
  "details": { ... },
  "request_id": "req-uuid-12345"
}
```

### 8.2. Worker Error Handling (Phase 22b — No DLQ)

Xem [Search Events §4.3](search_events.md#43-xử-lý-lỗi-phase-22b--không-có-dlq):
- Transient errors → nack + Kafka retry.
- `document_missing_exception` → log + metric + skip + ACK.
- Mapping conflict / schema poison → log + metric + skip + ACK.
- Category lookup miss → skip enrichment + log + metric + ACK.
- Cluster down → log + metric tăng theo thời gian (alert qua dashboard).

## 9. Development Workflow

### 9.1. Local Setup

```bash
# 1. Start infra (ES + Kafka + Zookeeper)
docker-compose up -d elasticsearch kafka zookeeper

# 2. Wait for ES ready
curl -f http://localhost:9200/_cluster/health

# 3. Run service (dev profile)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev

# 4. Test endpoints
curl "http://localhost:8080/api/v1/search/products?q=iphone"
curl "http://localhost:8080/api/v1/search/suggest?q=iph"
```

### 9.2. Test Strategy

**Unit test:** Pure Java (no Spring context). Mockito cho ES client mock. Coverage target: >80% cho `service/`, `util/`, `exception/`.

**Integration test:** Testcontainers (ES 8.x + Kafka). Test toàn bộ flow từ Kafka consumer → ES indexing → API query.

```java
@SpringBootTest
@Testcontainers
class ProductSearchIT {
    @Container
    static ElasticsearchContainer es = new ElasticsearchContainer("docker.elastic.co/elasticsearch/elasticsearch:8.11.0");
    
    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));
    
    // Tests...
}
```

**Contract test:** Verify Search Service consume đúng schema từ Product Service / Review Service events. Có thể dùng Pact hoặc custom JSON schema validation.

## 10. Cross-Service Notes

- **Read-only projection** của Product Service + Review Service. Không gọi ngược sang các service khác.
- **JWT trust:** API Gateway verify, Search Service đọc claims từ `X-User-Claims` header.
- **Internal API:** Chỉ accept JWT role=ADMIN (qua Gateway). Không có shared secret fallback.
- **Kafka consumer:** 1 group chính + 1 group tạm (reindex). Không có DLQ.
- **Outbox:** Search Service KHÔNG phát event. Read model thuần túy.

## Tổng Kết

Search Service là một service đơn giản về kiến trúc (1 consumer group + 3 cronjob + REST API), nhưng phức tạp về indexing logic (denormalize, field collapsing, realtime count update). Tech stack chuẩn Java Spring Boot giúp dễ onboard engineer đã quen với Product/Review Service.

**Điểm mạnh:**
- Stateless, scale horizontal dễ dàng.
- ES internal query cache đủ cho hầu hết use case (không cần Redis).
- Cursor pagination ổn định với `search_after`.
- Async reindex qua internal API.

**Trade-off chấp nhận được:**
- Eventual consistency (1-3s) so với PostgreSQL.
- Sai số ±1-2 trên `categories.product_count` (acceptable).
- Không có DLQ (manual recovery qua log + Full Reindex Job).
