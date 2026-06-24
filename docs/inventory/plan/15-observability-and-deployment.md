# Module 15: Observability & Deployment

> **Depends on:** all modules (cross-cutting)
> **Depended by:** (none)

---

## 1. Goal

Triển khai:

1. **Metrics**: Micrometer + Prometheus, business + system metrics.
2. **Tracing**: OpenTelemetry, propagation xuyên qua HTTP, Kafka, Redis, PostgreSQL.
3. **Logging**: structured JSON logs với correlation ID.
4. **Health checks**: liveness / readiness / startup probes.
5. **Alerting rules**: Prometheus alert rules cho các tình huống bất thường.
6. **Docker & deployment**: Dockerfile, docker-compose cho dev.
7. **Integration testing**: TestContainers setup với Postgres + Redis Cluster + Kafka + Debezium.

---

## 2. Package Layout

```
src/main/java/com/platform/inventoryservice/
├── observability/
│   ├── InventoryMetrics.java        # @Component tập trung tất cả metrics
│   ├── Meters.java                  # Meter names + tag keys constants
│   └── HealthIndicators.java
└── tracing/
    └── TracingConfiguration.java

src/main/resources/
├── alertmanager/
│   └── inventory-alerts.yml
├── prometheus/
│   └── inventory-recording-rules.yml
├── docker/
│   ├── Dockerfile
│   ├── docker-compose.dev.yml
│   └── docker-compose.test.yml

src/test/java/com/platform/inventoryservice/
├── IntegrationTestBase.java          # TestContainers base class
└── ...
```

---

## 3. InventoryMetrics (centralized)

> Single `@Component` chứa tất cả Counter / Timer / Gauge. Inject ở mọi nơi cần ghi metric.

```java
package com.platform.inventoryservice.observability;

@Component
@RequiredArgsConstructor
public class InventoryMetrics {

    private final MeterRegistry registry;

    // -- Lua script metrics --
    public void recordLuaInvocation(String op, String result) {
        registry.counter("inventory_lua_invocations_total",
            "operation", op, "result", result).increment();
    }

    public void recordLuaDuration(String op, long nanos) {
        registry.timer("inventory_lua_duration_seconds",
            "operation", op).record(Duration.ofNanos(nanos));
    }

    // -- Relay worker metrics --
    public void recordRelayForwarded(String eventType) {
        registry.counter("inventory_relay_forwarded_total", "event_type", eventType).increment();
    }

    public void recordKafkaPublishError(String eventType, String errorType) {
        registry.counter("inventory_kafka_publish_errors_total",
            "event_type", eventType, "error_type", errorType).increment();
    }

    public void recordKafkaPublish(String eventType) {
        registry.counter("inventory_kafka_publish_total", "event_type", eventType).increment();
    }

    public void recordRelayError(String streamKey, String errorType) {
        registry.counter("inventory_relay_errors_total",
            "stream_key_prefix", streamKey.split(":")[1], "error_type", errorType).increment();
    }

    // -- Ledger worker metrics --
    public void recordMutationProcessed(String changeType) {
        registry.counter("inventory_ledger_consumed_total",
            "event_type", "INVENTORY_STOCK_MUTATION",
            "change_type", changeType).increment();
    }

    public void recordLedgerWritten(String referenceType) {
        registry.counter("inventory_ledger_written_total",
            "reference_type", referenceType).increment();
    }

    public void recordLedgerDedupSkipped() {
        registry.counter("inventory_ledger_dedup_skipped_total").increment();
    }

    public void recordLedgerProcessDuration(long nanos) {
        registry.timer("inventory_ledger_process_duration_seconds")
            .record(Duration.ofNanos(nanos));
    }

    // -- Outbox metrics --
    public void recordOutboxInsert(String eventType) {
        registry.counter("inventory_outbox_insert_total", "event_type", eventType).increment();
    }

    public void recordOutboxCleanup(int deleted, long durationMs) {
        registry.counter("inventory_outbox_cleanup_deleted_total").increment(deleted);
        registry.timer("inventory_outbox_cleanup_duration_seconds")
            .record(Duration.ofMillis(durationMs));
    }

    public void recordOutboxPending(long count) {
        registry.gauge("inventory_outbox_pending", count);
    }

    public void recordOldestAge(long seconds) {
        registry.gauge("inventory_outbox_oldest_age_seconds", seconds);
    }

    // -- Temporal metrics --
    public void recordReserveSuccess(int itemCount) {
        registry.counter("inventory_reserve_success_total").increment();
        registry.counter("inventory_reserve_items_total").increment(itemCount);
    }

    public void recordReserveFailure(String errorType) {
        registry.counter("inventory_reserve_failures_total", "error_type", errorType).increment();
    }

    public void recordDeductSuccess(int itemCount) {
        registry.counter("inventory_deduct_success_total").increment();
        registry.counter("inventory_deduct_items_total").increment(itemCount);
    }

    public void recordRollbackSuccess(int itemCount, String changeType) {
        registry.counter("inventory_rollback_success_total", "change_type", changeType).increment();
    }

    // -- Admin service metrics --
    public void recordRestock(String refType, int quantity) {
        registry.counter("inventory_restock_total", "reference_type", refType).increment();
        registry.counter("inventory_restock_quantity_total",
            "reference_type", refType).increment(quantity);
    }

    public void recordShip(int itemCount) {
        registry.counter("inventory_ship_to_customer_total").increment();
        registry.counter("inventory_ship_to_customer_items_total").increment(itemCount);
    }

    public void recordRefundExpectation(int markedCount) {
        registry.counter("inventory_refund_expectations_marked_total").increment(markedCount);
    }

    public void recordUnlock() {
        registry.counter("inventory_unlock_sku_total").increment();
    }

    // -- Suspension metric --
    public void recordSuspensionSet() {
        registry.counter("inventory_sku_suspended_total").increment();
    }

    // -- Routing metrics --
    public void recordRoutingRequest(int itemCount) {
        registry.counter("inventory_routing_requests_total").increment();
        registry.counter("inventory_routing_items_total").increment(itemCount);
    }
}
```

---

## 4. Health Indicators

### 4.1 PostgreSQL Health Indicator

Spring Boot tự động qua `spring-boot-starter-data-jpa` + `spring-boot-starter-actuator`. Custom nếu cần:

```java
@Component
@RequiredArgsConstructor
public class PostgresHealthIndicator implements HealthIndicator {

    private final DataSource dataSource;

    @Override
    public Health health() {
        try (Connection conn = dataSource.getConnection()) {
            if (conn.isValid(2)) {
                return Health.up().withDetail("database", "PostgreSQL").build();
            }
            return Health.down().withDetail("reason", "connection not valid").build();
        } catch (SQLException e) {
            return Health.down(e).build();
        }
    }
}
```

### 4.2 Redis Health Indicator

```java
@Component
@RequiredArgsConstructor
public class RedisHealthIndicator implements HealthIndicator {

    private final RedisConnectionFactory redisFactory;

    @Override
    public Health health() {
        try (RedisConnection conn = redisFactory.getConnection()) {
            String pong = conn.ping();
            if ("PONG".equalsIgnoreCase(pong)) {
                return Health.up().withDetail("ping", pong).build();
            }
            return Health.down().withDetail("ping", pong).build();
        } catch (Exception e) {
            return Health.down(e).build();
        }
    }
}
```

### 4.3 Kafka Health Indicator

Spring Kafka có sẵn `KafkaHealthIndicator` (auto-config).

### 4.4 Debezium / Outbox Liveness

> Custom indicator kiểm tra Debezium connector running + outbox không quá đầy:

```java
@Component
@RequiredArgsConstructor
public class OutboxHealthIndicator implements HealthIndicator {

    private final OutboxRepository outboxRepo;

    @Override
    public Health health() {
        long pending = outboxRepo.countAll();   // cheap with PK
        long threshold = 500_000;
        if (pending > threshold) {
            return Health.down()
                .withDetail("pending", pending)
                .withDetail("threshold", threshold)
                .withDetail("reason", "Outbox backlog too high")
                .build();
        }
        return Health.up().withDetail("pending", pending).build();
    }
}
```

---

## 5. Distributed Tracing

### 5.1 Configuration

```yaml
management:
  tracing:
    sampling:
      probability: 1.0      # 100% sampling cho dev; giảm xuống 0.1 trong prod
  otlp:
    tracing:
      endpoint: ${OTLP_ENDPOINT:http://otel-collector:4318/v1/traces}
```

### 5.2 Span Hierarchy

```
HTTP POST /v1/inventory/restock
  └─ RestockService.restock
       ├─ JPA UPSERT physical_inventory
       ├─ JPA UPDATE inventory_reservations
       ├─ JPA INSERT inventory_ledger
       └─ JPA INSERT inventory_outbox
```

```
Temporal Workflow ReserveInventory
  └─ ReserveActivityImpl.reserve
       └─ StockAtomicOps.reserve (Lua EVALSHA)
            └─ Redis HGET / HINCRBY / XADD
```

### 5.3 Trace ID Propagation

- HTTP: tự động qua Spring Boot OTEL integration (header `traceparent`).
- Kafka: producer inject `traceparent` header; consumer extract.
- Redis: tạm thời không propagate (Lua script không span); Redis client call tạo child span tự động.
- PostgreSQL: tự động qua `datasource-micrometer-spring-boot`.

---

## 6. Logging

### 6.1 Structured JSON Layout

`logback-spring.xml`:

```xml
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder">
            <providers>
                <timestamp>
                    <fieldName>timestamp</fieldName>
                    <timeZone>UTC</timeZone>
                    <pattern>yyyy-MM-dd'T'HH:mm:ss.SSSXXX</pattern>
                </timestamp>
                <logLevel/>
                <loggerName/>
                <threadName/>
                <message/>
                <mdc/>
                <arguments/>
                <stackTrace/>
                <pattern>
                    <pattern>
                        {
                          "service": "inventory-service",
                          "env": "${SPRING_PROFILES_ACTIVE:-dev}"
                        }
                    </pattern>
                </pattern>
            </providers>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="STDOUT"/>
    </root>
</configuration>
```

### 6.2 Correlation ID in MDC

> Tự động bởi `CorrelationIdFilter` (M01) — set MDC key `correlationId` từ `X-Correlation-Id` header.

### 6.3 Audit Logging

> Admin operations log với structured fields:

```java
log.info("Admin operation",
    kv("operation", "restock"),
    kv("actor", currentActor),
    kv("sku_id", cmd.skuId()),
    kv("warehouse_id", cmd.warehouseId()),
    kv("quantity", cmd.quantity()),
    kv("correlation_id", cmd.correlationId())
);
```

---

## 7. Alerting Rules

`src/main/resources/alertmanager/inventory-alerts.yml`:

```yaml
groups:
  - name: inventory.critical
    interval: 30s
    rules:
      - alert: InventoryInvariantDrift
        expr: rate(inventory_lua_invocations_total{result="invariant_drift"}[5m]) > 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "SKU invariant drift detected in Inventory Service"
          description: "Lua deduct detected invariant drift at {{ $labels.warehouse_id }}. SKU suspended. Manual reconcile required."

      - alert: InventoryOutboxBacklog
        expr: inventory_outbox_pending > 500000
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "Outbox backlog HIGH"
          description: "{{ $value }} outbox rows pending publication. Debezium CDC may be lagging."

      - alert: InventorySuspendedSku
        expr: inventory_sku_suspended_total > 0
        labels:
          severity: warning
        annotations:
          summary: "SKU suspended"
          description: "SKU {{ $labels.sku_id }} at warehouse {{ $labels.warehouse_id }} suspended. Awaiting admin unlock."

      - alert: InventoryLedgerWorkerDown
        expr: up{job="inventory-service"} == 0
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "Inventory Service down"

  - name: inventory.warning
    interval: 1m
    rules:
      - alert: InventoryLuaHighErrorRate
        expr: |
          rate(inventory_lua_invocations_total{result=~"out_of_stock|insufficient_reserved|release_underflow|sku_suspended"}[5m])
          /
          rate(inventory_lua_invocations_total[5m]) > 0.1
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "High business reject rate from Lua scripts"
          description: "{{ $value | humanizePercentage }} of Lua invocations returning business reject codes."

      - alert: InventoryRelayLag
        expr: inventory_outbox_oldest_age_seconds > 300
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Outbox oldest row older than 5 minutes"
          description: "Debezium CDC may be lagging. Check Kafka Connect cluster."
```

---

## 8. Recording Rules (pre-aggregation)

`src/main/resources/prometheus/inventory-recording-rules.yml`:

```yaml
groups:
  - name: inventory.recording
    interval: 30s
    rules:
      - record: inventory:lua_invocations:rate5m
        expr: sum by (operation, result) (rate(inventory_lua_invocations_total[5m]))

      - record: inventory:outbox_pending:max
        expr: max_over_time(inventory_outbox_pending[5m])

      - record: inventory:http_requests:rate5m
        expr: sum by (endpoint, method, status) (rate(inventory_http_requests_total[5m]))
```

---

## 9. Docker

### 9.1 Dockerfile (M01 đã scaffold)

```dockerfile
# Build stage
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY pom.xml .
COPY src src
RUN ./mvnw -B -ntp -DskipTests package

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=builder /app/target/inventory-service-*.jar app.jar
USER app
EXPOSE 8083
ENTRYPOINT ["java", \
    "-XX:+UseG1GC", \
    "-XX:MaxRAMPercentage=75", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-jar", "app.jar"]
```

### 9.2 docker-compose.dev.yml

```yaml
version: '3.8'
services:
  inventory-postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: inventory_db
      POSTGRES_USER: inventory
      POSTGRES_PASSWORD: inventory_dev
    ports: ["5432:5432"]
    volumes: [pgdata:/var/lib/postgresql/data]
    command: >
      postgres
      -c wal_level=logical
      -c max_replication_slots=4
      -c max_wal_senders=4

  inventory-redis:
    image: redis:7-alpine
    command: redis-server --cluster-enabled yes --cluster-config-file nodes.conf --cluster-node-timeout 5000 --appendonly yes
    ports: ["6379:6379"]
    volumes: [redisdata:/data]

  inventory-kafka:
    image: apache/kafka:3.7.0
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://:9092,CONTROLLER://:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@localhost:9093
    ports: ["9092:9092"]

  inventory-debezium:
    image: debezium/connect:2.5
    environment:
      BOOTSTRAP_SERVERS: inventory-kafka:9092
      GROUP_ID: 1
      CONFIG_STORAGE_TOPIC: debezium_config
      OFFSET_STORAGE_TOPIC: debezium_offset
      STATUS_STORAGE_TOPIC: debezium_status
    depends_on: [inventory-kafka]
    ports: ["8083:8083"]

  inventory-temporal:
    image: temporalio/auto-setup:1.20
    environment:
      DB: postgresql
      POSTGRES_HOST: inventory-postgres
      POSTGRES_USER: inventory
      POSTGRES_PASSWORD: inventory_dev
      POSTGRES_DB: temporal_db
    ports: ["7233:7233"]
    depends_on: [inventory-postgres]

  inventory-app:
    build: .
    environment:
      SPRING_PROFILES_ACTIVE: dev
      REDIS_CLUSTER_NODES: inventory-redis:6379
      KAFKA_BOOTSTRAP: inventory-kafka:9092
      TEMPORAL_TARGET: inventory-temporal:7233
      INVENTORY_DB_URL: jdbc:postgresql://inventory-postgres:5432/inventory_db
      INVENTORY_DB_USER: inventory
      INVENTORY_DB_PASSWORD: inventory_dev
    ports: ["8083:8083"]
    depends_on:
      - inventory-postgres
      - inventory-redis
      - inventory-kafka
      - inventory-temporal

volumes:
  pgdata: {}
  redisdata: {}
```

---

## 10. Integration Testing (TestContainers)

### 10.1 Base Class

```java
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
public abstract class IntegrationTestBase {

    @Container
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("inventory_db")
        .withUsername("inventory")
        .withPassword("inventory_test")
        .withCommand("postgres", "-c", "wal_level=logical");

    @Container
    static GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine")
        .withCommand("redis-server", "--cluster-enabled", "yes")
        .withExposedPorts(6379);

    @Container
    static KafkaContainer KAFKA = new KafkaContainer(
        DockerImageName.parse("apache/kafka:3.7.0"));

    @Container
    static DebeziumContainer DEBEZIUM = new DebeziumContainer("debezium/connect:2.5")
        .withKafka(KAFKA)
        .withDatabase(POSTGRES);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("spring.data.redis.cluster.nodes", () ->
            REDIS.getHost() + ":" + REDIS.getMappedPort(6379));

        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);

        registry.add("inventory.temporal.target", () -> "localhost:7233");
        registry.add("debezium.skip", () -> "true");
    }
}
```

### 10.2 End-to-End Test

```java
class ReserveToLedgerE2ETest extends IntegrationTestBase {

    @Autowired RestockService restockService;
    @Autowired StockAtomicOps stockOps;
    @Autowired KafkaConsumer<String, String> testConsumer;
    @Autowired InventoryLedgerRepository ledgerRepo;

    @Test
    void endToEnd_reserveEmitsMutationAndLedger() throws Exception {
        // 1. Restock
        restockService.restock(new RestockCommand(
            1L, "sku-test", "prod-test", 100,
            ReferenceType.ADMIN_RESTOCK, null, null, null, "test-correlation"
        ));

        // 2. Reserve via Lua
        SkuWarehouseKey key = new SkuWarehouseKey("sku-test", 1L);
        StockMutationResult result = stockOps.reserve(
            key, 9999L, 1L, "prod-test", 5, "wf-9999");
        assertThat(result.returnCode()).isEqualTo(LuaReturnCode.APPLIED);

        // 3. Wait for Kafka publish + ledger worker consume
        await().atMost(10, SECONDS).untilAsserted(() -> {
            assertThat(ledgerRepo.findByEventId(any())).isPresent();
        });
    }
}
```

---

## 12. Performance Testing

| Test | Tool | Target |
|:---|:---|:---|
| Concurrent reserve | JMeter / k6 | 1000 RPS, p95 < 50ms |
| Ledger worker throughput | k6 + Kafka load | 5000 msg/s, lag < 1s |
| Outbox insert latency | pgbench | 10K ops/s |
| End-to-end mutation → ledger → CDC | Custom test | < 200ms p95 |

---

## 13. Operational Runbook

### 13.1 Drift Detected (Lua trả INVARIANT_DRIFT)

1. Check alert `InventoryInvariantDrift`.
2. Inspect Redis hash `inventory:physic:{sku:wh}:stock` so với DB `physical_inventory`.
3. Nếu drift xác nhận → manual fix tại Redis HOẶC DB.
4. Gọi `POST /v1/inventory/admin/unlock-sku` với reason = drift reconciliation.
5. Verify subsequent operations pass Lua script check.

### 13.2 Debezium CDC Lag

1. Check `inventory_outbox_pending` và `inventory_outbox_oldest_age_seconds`.
2. Restart Kafka Connect cluster nếu connector failed.
3. Check PostgreSQL WAL retention (`pg_wal_lsn_diff`).
4. Nếu backlog > 1M rows → manual `VACUUM` + tăng partition.

### 13.3 Temporal Worker Down

1. Check `inventory-service` container status.
2. Restart container → Temporal Worker reconnect.
3. In-flight Temporal Activities sẽ timeout & retry sau khi worker online.

---

## 14. Files Created

```
src/main/java/com/platform/inventoryservice/observability/
├── InventoryMetrics.java
├── Meters.java
└── HealthIndicators.java (PostgresHealthIndicator, RedisHealthIndicator, OutboxHealthIndicator)

src/main/java/com/platform/inventoryservice/tracing/
└── TracingConfiguration.java

src/main/resources/
├── alertmanager/inventory-alerts.yml
├── prometheus/inventory-recording-rules.yml
└── docker/
    ├── Dockerfile
    ├── docker-compose.dev.yml
    └── docker-compose.test.yml

src/test/java/com/platform/inventoryservice/
└── IntegrationTestBase.java
```