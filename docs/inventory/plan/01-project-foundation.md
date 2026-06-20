# Module 01: Project Foundation

> **Depends on:** none (root module)
> **Depended by:** every other module

---

## 1. Goal

Khởi tạo Maven project skeleton cho `inventory-service`, khai báo toàn bộ dependencies, scaffold folder structure theo Hexagonal layout, và cấu hình `application.yml` cho 3 profiles (dev/test/prod).

---

## 2. Maven Project Skeleton

### 2.1 Coordinates

```xml
<groupId>com.platform</groupId>
<artifactId>inventory-service</artifactId>
<version>1.0.0-SNAPSHOT</version>
<packaging>jar</packaging>
<name>inventory-service</name>
```

Base package: `com.platform.inventoryservice`

### 2.2 Properties

```xml
<properties>
    <java.version>21</java.version>
    <maven.compiler.source>21</maven.compiler.source>
    <maven.compiler.target>21</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>

    <spring-boot.version>3.3.x</spring-boot.version>
    <postgres.version>16.x</postgres.version>
    <lettuce.version>6.3.x</lettuce.version>
    <kafka-clients.version>3.7.x</kafka-clients.version>
    <debezium.version>2.5.x</debezium.version>
    <temporal.version>1.x</temporal.version>
    <flyway.version>10.x</flyway.version>
    <resilience4j.version>2.x</resilience4j.version>
    <micrometer.version>1.13.x</micrometer.version>
    <testcontainers.version>1.19.x</testcontainers.version>
</properties>
```

---

## 3. Dependencies (pom.xml)

### 3.1 Core Spring Boot Starters

| Dependency | Scope | Mục đích |
|:---|:---:|:---|
| `spring-boot-starter-web` | compile | REST controllers (M13) |
| `spring-boot-starter-validation` | compile | Bean Validation cho DTO |
| `spring-boot-starter-actuator` | compile | Health, metrics, Prometheus scrape endpoint |
| `spring-boot-starter-data-jpa` | compile | Spring Data repositories (M05) |
| `spring-boot-starter-data-redis` | compile | Lettuce client (M06) |

### 3.2 Database & Migration

| Dependency | Scope | Mục đích |
|:---|:---:|:---|
| `org.postgresql:postgresql` | runtime | PostgreSQL JDBC driver |
| `org.flywaydb:flyway-core` | compile | Schema migration (M02) |
| `org.flywaydb:flyway-database-postgresql` | compile | Flyway Postgres module (bắt buộc từ v10) |

### 3.3 Redis

| Dependency | Scope | Mục đích |
|:---|:---:|:---|
| `org.apache.commons:commons-pool2` | compile | Lettuce connection pool |

> Spring Boot tự include Lettuce qua `spring-boot-starter-data-redis`. Không cần thêm explicit.

### 3.4 Kafka

| Dependency | Scope | Mục đích |
|:---|:---:|:---|
| `spring-kafka` | compile | KafkaTemplate, @KafkaListener |
| `org.apache.kafka:kafka-clients` | compile | (transitive, expose để dùng AdminClient nếu cần) |

> Debezium không nằm trong app — chạy như Kafka Connect cluster riêng (xem M08). Không add `debezium-core` vào pom.

### 3.5 Temporal

| Dependency | Scope | Mục đích |
|:---|:---:|:---|
| `io.temporal:temporal-sdk` | compile | Activity implementation + Worker bootstrap (M11) |
| `io.temporal:temporal-service-client` | compile | Connect tới Temporal Service |

> Order Service host Temporal Workflow. Inventory Service chỉ host **Worker** để nhận Activity task. Cùng Temporal namespace `inventory` (hoặc namespace riêng cho Inventory — quyết định theo convention repo, mặc định dùng `inventory`).

### 3.6 Security

| Dependency | Scope | Mục đích |
|:---|:---:|:---|
| `spring-boot-starter-oauth2-resource-server` | compile | JWT validation cho REST API (M13) |
| `spring-boot-starter-security` | compile | Security filter chain |

> JWT issuer config qua `spring.security.oauth2.resourceserver.jwt.issuer-uri`.


### 3.7 Resilience

| Dependency | Scope | Mục đích |
|:---|:---:|:---|
| `io.github.resilience4j:resilience4j-spring-boot3` | compile | Circuit breaker cho Redis/Kafka |
| `io.github.resilience4j:resilience4j-reactor` | compile | (optional, nếu dùng WebFlux) |
| `io.github.resilience4j:resilience4j-micrometer` | compile | Metrics integration |

### 3.8 Testing

| Dependency | Scope | Mục đích |
|:---|:---:|:---|
| `spring-boot-starter-test` | test | JUnit 5, Mockito, AssertJ |
| `org.testcontainers:postgresql` | test | Integration test Postgres |
| `org.testcontainers:junit-jupiter` | test | TestContainers JUnit5 |
| `org.testcontainers:kafka` | test | Kafka broker cho test |
| `com.redis:testcontainers-redis` | test | Redis container (Cluster mode) |
| `org.awaitility:awaitility` | test | Async assertions cho worker tests |
| `io.temporal:temporal-testing` | test | Temporal test server |

---

## 4. Spring Boot Application Entry Point

```java
package com.platform.inventoryservice;

@SpringBootApplication
@EnableScheduling
@EnableJpaAuditing
@EnableKafka
@ComponentScan(basePackages = "com.platform.inventoryservice")
public class InventoryServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
```

---

## 5. Configuration Profiles

### 5.1 application.yml (common)

```yaml
spring:
  application:
    name: inventory-service
  profiles:
    active: dev
  jackson:
    default-property-inclusion: non_null
    serialization:
      write-dates-as-timestamps: false

server:
  port: 8083
  shutdown: graceful

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      probes:
        enabled: true
      show-details: when_authorized
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true
        inventory.lua.duration: true
  tracing:
    sampling:
      probability: 1.0

inventory:
  redis:
    cluster:
      nodes: ${REDIS_CLUSTER_NODES:localhost:6379}
      max-redirects: 3
    lettuce:
      pool:
        max-active: 32
        max-idle: 16
        min-idle: 4
    timeout-ms: 2000
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP:localhost:9092}
    topic-events: t.inventory.events
    consumer-group-relay: inventory-stream-relay
    consumer-group-ledger: inventory-ledger-writer
    poll-interval-ms: 100
  temporal:
    target: ${TEMPORAL_TARGET:localhost:7233}
    namespace: inventory
    task-queue: inventory-activities
    worker-identity: ${HOSTNAME:inventory-worker-1}
  restock:
    reservation-ttl-minutes: 30
  routing:
    plan-ttl-minutes: 20
  cleanup:
    cron: "0 0 2 * * *"
    watermark-hours: 24
    batch-size: 10000
```

### 5.2 application-dev.yml

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/inventory_db
    username: inventory
    password: inventory_dev
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        format_sql: true
        jdbc:
          batch_size: 50
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
  data:
    redis:
      cluster:
        nodes: localhost:6379
        max-redirects: 3

inventory:
  redis:
    cluster:
      nodes: localhost:6379
  kafka:
    bootstrap-servers: localhost:9092
  temporal:
    target: localhost:7233
```

### 5.3 application-test.yml

```yaml
spring:
  datasource:
    url: ${spring.datasource.url}  # override bởi TestContainers
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true
  data:
    redis:
      cluster:
        nodes: ${spring.data.redis.cluster.nodes}  # override bởi TestContainers

debezium:
  skip: true   # tests không chạy Debezium connector thật
```

### 5.4 application-prod.yml

```yaml
spring:
  datasource:
    url: ${INVENTORY_DB_URL}
    username: ${INVENTORY_DB_USER}
    password: ${INVENTORY_DB_PASSWORD}
    hikari:
      maximum-pool-size: 50
      minimum-idle: 10
      connection-timeout: 30000
      leak-detection-threshold: 60000
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        jdbc:
          batch_size: 100
        order_inserts: true
        order_updates: true
  flyway:
    enabled: true
    baseline-on-migrate: false
  data:
    redis:
      cluster:
        nodes: ${REDIS_CLUSTER_NODES}
        max-redirects: 3
        password: ${REDIS_PASSWORD}

inventory:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP}
  temporal:
    target: ${TEMPORAL_TARGET}
    namespace: ${TEMPORAL_NAMESPACE:inventory}
```

---

## 6. Base Config Classes (config/)

### 6.1 RedisConfig.java

```java
@Configuration
@EnableConfigurationProperties(InventoryProperties.class)
public class RedisConfig {

    @Bean
    public RedisConnectionFactory redisConnectionFactory(InventoryProperties props) {
        RedisClusterConfiguration clusterConfig = new RedisClusterConfiguration(
            props.getRedis().getCluster().getNodes());
        clusterConfig.setMaxRedirects(props.getRedis().getCluster().getMaxRedirects());
        if (props.getRedis().getCluster().getPassword() != null) {
            clusterConfig.setPassword(props.getRedis().getCluster().getPassword());
        }
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
            .commandTimeout(Duration.ofMillis(props.getRedis().getTimeoutMs()))
            .poolConfig(/* from props.getRedis().getLettuce().getPool() */)
            .build();
        return new LettuceConnectionFactory(clusterConfig, clientConfig);
    }

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory cf) {
        RedisTemplate<String, String> t = new RedisTemplate<>();
        t.setConnectionFactory(cf);
        t.setKeySerializer(new StringRedisSerializer());
        t.setValueSerializer(new StringRedisSerializer());
        t.setHashKeySerializer(new StringRedisSerializer());
        t.setHashValueSerializer(new StringRedisSerializer());
        return t;
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory cf) {
        return new StringRedisTemplate(cf);
    }
}
```

> Cluster hash-tag routing cho `{sku_id:warehouse_id}` được Spring Data Redis tự xử lý khi key chứa `{...}`.

### 6.2 KafkaConfig.java

```java
@Configuration
public class KafkaConfig {

    @Bean
    public ProducerFactory<String, String> kafkaProducerFactory(InventoryProperties props) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, props.getKafka().getBootstrapServers());
        cfg.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        cfg.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        cfg.put(ProducerConfig.ACKS_CONFIG, "all");
        cfg.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        cfg.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        cfg.put(ProducerConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
        cfg.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120_000);
        cfg.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        return new DefaultKafkaProducerFactory<>(cfg);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> pf) {
        return new KafkaTemplate<>(pf);
    }

    @Bean
    public ConsumerFactory<String, String> kafkaConsumerFactory(InventoryProperties props) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, props.getKafka().getBootstrapServers());
        cfg.put(ConsumerConfig.GROUP_ID_CONFIG, props.getKafka().getConsumerGroupLedger());
        cfg.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cfg.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cfg.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        cfg.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        cfg.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        cfg.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 45_000);
        cfg.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300_000);
        return new DefaultKafkaConsumerFactory<>(cfg);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> cf) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(cf);
        factory.setBatchListener(true);
        factory.setConcurrency(3);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);
        return factory;
    }
}
```

### 6.3 InventoryProperties.java

```java
@ConfigurationProperties(prefix = "inventory")
@Data
public class InventoryProperties {
    private Redis redis = new Redis();
    private Kafka kafka = new Kafka();
    private Temporal temporal = new Temporal();
    private Restock restock = new Restock();
    private Routing routing = new Routing();
    private Cleanup cleanup = new Cleanup();

    @Data public static class Redis {
        private Cluster cluster = new Cluster();
        private Lettuce lettuce = new Lettuce();
        private long timeoutMs;
        @Data public static class Cluster {
            private List<String> nodes;
            private int maxRedirects;
            private String password;
        }
        @Data public static class Lettuce {
            private Pool pool = new Pool();
            @Data public static class Pool {
                private int maxActive;
                private int maxIdle;
                private int minIdle;
            }
        }
    }
    @Data public static class Kafka {
        private String bootstrapServers;
        private String topicEvents;
        private String consumerGroupRelay;
        private String consumerGroupLedger;
        private long pollIntervalMs;
    }
    @Data public static class Temporal {
        private String target;
        private String namespace;
        private String taskQueue;
        private String workerIdentity;
    }
    @Data public static class Restock {
        private int reservationTtlMinutes;
    }
    @Data public static class Routing {
        private int planTtlMinutes;
    }
    @Data public static class Cleanup {
        private String cron;
        private int watermarkHours;
        private int batchSize;
    }
}
```

### 6.4 JpaConfig.java

```java
@Configuration
@EnableJpaAuditing
@EnableJpaRepositories(basePackages = "com.platform.inventoryservice.infrastructure.persistence.jpa.spring")
public class JpaConfig {
    @Bean
    public AuditorAware<String> auditorAware() {
        return () -> Optional.of("inventory-service");
    }
}
```

### 6.5 SecurityConfig.java (M13 sẽ mở rộng)

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                .requestMatchers("/v1/inventory/**").authenticated()
                .anyRequest().denyAll())
            .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()));
        return http.build();
    }
}
```

---

## 7. Common Utilities (common/)

### 7.1 CorrelationIdContext.java

Thread-local holder cho `X-Correlation-Id` (HTTP header) hoặc Temporal Workflow ID.

```java
public final class CorrelationIdContext {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    public static void set(String id) {
        CURRENT.set(id == null || id.isBlank() ? UUID.randomUUID().toString() : id);
    }

    public static String get() {
        String id = CURRENT.get();
        if (id == null) {
            id = UUID.randomUUID().toString();
            CURRENT.set(id);
        }
        return id;
    }

    public static void clear() {
        CURRENT.remove();
    }
}
```

### 7.2 CorrelationIdFilter.java

Servlet filter tự động set CorrelationIdContext từ header `X-Correlation-Id` (hoặc generate UUID v4 nếu không có). Áp dụng cho toàn bộ REST request.

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain) {
        String cid = req.getHeader(HEADER);
        if (cid == null || cid.isBlank()) cid = UUID.randomUUID().toString();
        try {
            CorrelationIdContext.set(cid);
            MDC.put(MDC_KEY, cid);
            resp.setHeader(HEADER, cid);
            chain.doFilter(req, resp);
        } finally {
            CorrelationIdContext.clear();
            MDC.remove(MDC_KEY);
        }
    }
}
```

### 7.3 ErrorCode.java

Enum tập trung tất cả error code trả về cho client (M13).

```java
public enum ErrorCode {
    INVALID_REQUEST(400),
    WAREHOUSE_NOT_FOUND(404),
    SKU_NOT_FOUND(404),
    RESERVATION_NOT_FOUND(404),
    SKU_NOT_SUSPENDED(404),
    ORDER_NOT_FOUND(404),
    INSUFFICIENT_STOCK(409),
    REFUND_NOT_EXPECTED(409),
    REFUND_SCOPE_MISMATCH(409),
    OUT_OF_STOCK(409),
    INSUFFICIENT_RESERVED(409),
    RELEASE_UNDERFLOW(409),
    SKU_SUSPENDED(409),
    INVARIANT_DRIFT(500),
    INTERNAL_ERROR(500);

    private final int httpStatus;
    ErrorCode(int httpStatus) { this.httpStatus = httpStatus; }
    public int httpStatus() { return httpStatus; }
}
```

### 7.4 IdempotencyInterceptor.java

Spring HandlerInterceptor đọc header `Idempotency-Key` (nếu có) cho Admin POST endpoints, gắn vào MDC.

> Lưu ý: idempotency chính nằm ở Redis Lua SETNX (hot path, 30 ngày TTL) và partial UNIQUE `inventory_reservations(order_id, sku_id, warehouse_id) WHERE status='RESERVED'` (cold path).

---

## 8. Logging Configuration

### 8.1 logback-spring.xml

```xml
<configuration>
    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>
    <springProperty scope="context" name="appName" source="spring.application.name"/>

    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{ISO8601} %-5level [%X{correlationId:-}] [%thread] %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <logger name="com.platform.inventoryservice" level="INFO"/>
    <logger name="org.hibernate.SQL" level="WARN"/>
    <logger name="org.springframework.kafka" level="WARN"/>
    <logger name="io.temporal" level="INFO"/>

    <root level="INFO">
        <appender-ref ref="STDOUT"/>
    </root>
</configuration>
```

---

## 9. Dockerfile (base)

```dockerfile
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY pom.xml .
COPY src src
RUN ./mvnw -B -ntp -DskipTests package

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

> Multi-stage build. Runtime image dùng JRE only.

---

## 10. Maven Build Profiles

```xml
<profiles>
    <profile>
        <id>dev</id>
        <activation><activeByDefault>true</activeByDefault></activation>
        <properties><spring.profiles.active>dev</spring.profiles.active></properties>
    </profile>
    <profile>
        <id>test</id>
        <properties><spring.profiles.active>test</spring.profiles.active></properties>
    </profile>
    <profile>
        <id>prod</id>
        <properties><spring.profiles.active>prod</spring.profiles.active></properties>
    </profile>
    <profile>
        <id>docker</id>
        <build><plugins>
            <plugin>
                <artifactId>maven-jar-plugin</artifactId>
                <configuration><excludes><exclude>**/logback-test.xml</exclude></excludes></configuration>
            </plugin>
        </plugins></build>
    </profile>
</profiles>
```

---

## 11. Verification

Sau khi hoàn thành module này:

| Check | Cách verify |
|:---|:---|
| Maven compile | `./mvnw -B -ntp compile` → BUILD SUCCESS |
| Maven test | `./mvnw -B -ntp test` → BUILD SUCCESS (chưa có test nào, nhưng context load OK) |
| App boot | `./mvnw spring-boot:run` → Started InventoryServiceApplication, port 8083 listening |
| Actuator | `curl localhost:8083/actuator/health` → `{"status":"UP"}` |
| Docker build | `docker build -t inventory-service:dev .` → success |

---

## 12. Files Created

```
inventory-service/
├── pom.xml
├── Dockerfile
├── .dockerignore
├── mvnw
├── mvnw.cmd
├── .mvn/wrapper/maven-wrapper.properties
└── src/
    ├── main/
    │   ├── java/com/platform/inventoryservice/
    │   │   ├── InventoryServiceApplication.java
    │   │   ├── config/
    │   │   │   ├── RedisConfig.java
    │   │   │   ├── KafkaConfig.java
    │   │   │   ├── JpaConfig.java
    │   │   │   ├── SecurityConfig.java
    │   │   │   └── InventoryProperties.java
    │   │   └── common/
    │   │       ├── CorrelationIdContext.java
    │   │       ├── CorrelationIdFilter.java
    │   │       ├── IdempotencyInterceptor.java
    │   │       └── ErrorCode.java
    │   └── resources/
    │       ├── application.yml
    │       ├── application-dev.yml
    │       ├── application-test.yml
    │       ├── application-prod.yml
    │       └── logback-spring.xml
    └── test/
        └── java/com/platform/inventoryservice/
            └── ContextLoadTest.java        # @SpringBootTest sanity check
```