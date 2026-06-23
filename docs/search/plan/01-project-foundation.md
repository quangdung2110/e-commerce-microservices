# Module 01: Project Foundation

> **Status:** Module 1 of 14 - **✅ COMPLETED** (June 23, 2026)
> **Prerequisites:** None (this is the first module)
> **Dependents:** All other modules
> **Estimated Effort:** 0.5 person-days (actual: ~0.3 days)

---

## 1. Overview

This module establishes the project scaffold: Maven build configuration, Spring Boot application entry point, base infrastructure configurations (Elasticsearch client, Kafka consumer, security, observability).

Search Service sử dụng **Java 21** + **Spring Boot 3.3.x** (đồng bộ với Product Service).

---

## 2. Maven Configuration

### 2.1 File: `pom.xml`

**Path:** `java/services/search-service/pom.xml`

Create the Maven project object model with all required dependencies.

- [x] Create `<project>` root với:
  - `<groupId>com.ecommerce</groupId>`
  - `<artifactId>search-service</artifactId>`
  - `<version>1.0.0-SNAPSHOT</version>`
- [x] Set `<packaging>jar</packaging>`
- [x] Set `<java.version>21</java.version>`
- [x] Add parent POM: `org.springframework.boot:spring-boot-starter-parent:3.3.0`
- [x] Add **Web**: `spring-boot-starter-web`, `spring-boot-starter-validation`
- [x] Add **Spring Security**: `spring-boot-starter-security`
- [x] Add **Kafka**: `spring-kafka`
- [x] Add **Actuator**: `spring-boot-starter-actuator`
- [x] Add **AOP**: `spring-boot-starter-aop` (for `@Retryable`)
- [x] Add **Lombok**: `org.projectlombok:lombok`
- [x] Add **ES Client**:
  - `co.elastic.clients:elasticsearch-java:8.11.0`
  - `jakarta.json:jakarta.json-api:2.1.3`
- [x] Add **Micrometer Prometheus**: `io.micrometer:micrometer-registry-prometheus`
- [x] Add **Logback JSON**: `net.logstash.logback:logstash-logback-encoder:7.4`
- [x] Add **OpenAPI** (optional): `org.springdoc:springdoc-openapi-starter-webmvc-ui:2.3.0`
- [x] Add **Testing**:
  - `spring-boot-starter-test`
  - `spring-kafka-test`
  - `org.testcontainers:elasticsearch:1.19.7`
  - `org.testcontainers:kafka:1.19.7`
  - `org.testcontainers:junit-jupiter:1.19.7`
  - `org.mockito:mockito-core`
  - `org.assertj:assertj-core`
- [x] Add **Build Plugins**:
  - `maven-compiler-plugin` (target 21, annotation processing cho Lombok)
  - `spring-boot-maven-plugin`
- [x] Add **ArchUnit** (optional): `com.tngtech.archunit:archunit-junit5`

---

## 3. Application Entry Point

### 3.1 File: `SearchServiceApplication.java`

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/SearchServiceApplication.java`

- [x] Create `@SpringBootApplication` class
- [x] Add `@EnableScheduling` cho cron jobs (Suggestions Rebuilder, Draft Cleanup)
- [x] Add `@ConfigurationPropertiesScan` cho application properties
- [x] Main method với `SpringApplication.run(SearchServiceApplication.class, args)`
- [x] Add OpenAPI dependency (springdoc auto-configuration)

```java
package com.ecommerce.search;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class SearchServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(SearchServiceApplication.class, args);
    }
}
```

---

## 4. Configuration Files

### 4.1 File: `application.yml`

**Path:** `java/services/search-service/src/main/resources/application.yml`

- [x] Configure `spring.application.name: search-service`
- [x] Configure `server.port: 8080` and `server.shutdown: graceful`
- [x] Configure **Elasticsearch** client:
  - `spring.elasticsearch.uris: ${ES_URIS:http://localhost:9200}`
  - `spring.elasticsearch.connection-timeout: 5s`
  - `spring.elasticsearch.socket-timeout: 30s`
  - `spring.elasticsearch.username: ${ES_USERNAME:}` (optional, for prod)
  - `spring.elasticsearch.password: ${ES_PASSWORD:}` (optional, for prod)
- [x] Configure **Kafka** consumer:
  - `spring.kafka.bootstrap-servers: ${KAFKA_BOOTSTRAP:localhost:9092}`
  - `spring.kafka.consumer.group-id: search-product-indexer`
  - `spring.kafka.consumer.auto-offset-reset: earliest`
  - `spring.kafka.consumer.max-poll-records: 500`
  - `spring.kafka.consumer.concurrency: 5` (1 thread per partition × 5 partitions)
  - `spring.kafka.consumer.key-deserializer: org.apache.kafka.common.serialization.StringDeserializer`
  - `spring.kafka.consumer.value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer`
  - `spring.kafka.consumer.properties.spring.json.trusted.packages: "com.ecommerce.search.domain.event"`
  - `spring.kafka.consumer.properties.spring.json.value.default.type: com.ecommerce.search.domain.event.CloudEventEnvelope`
  - `spring.kafka.consumer.properties.max.poll.interval.ms: 600000` (10 phút)
- [x] Configure **Lifecycle**:
  - `spring.lifecycle.timeout-per-shutdown-phase: 30s` (graceful shutdown)
- [x] Configure **Actuator**:
  - `management.endpoints.web.exposure.include: health,info,prometheus`
  - `management.endpoint.health.probes.enabled: true`
  - `management.endpoint.health.show-details: when_authorized`
  - `management.health.elasticsearch.enabled: true`
  - `management.health.kafka.enabled: true`
  - `management.metrics.distribution.percentiles-histogram.http.server.requests: true`
  - `management.metrics.distribution.percentiles-histogram.es.query.duration: true`
- [x] Configure **Custom Search Properties** (loaded từ `search.*` prefix):

```yaml
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
    allowed-origins: "*"  # dev only
  pagination:
    default-size: 20
    max-size: 100
  reindex:
    checkpoint-interval-docs: 5000
    build-chunk-size: 2000
  suggestions:
    rebuild-cron: "0 0 3 * * *"  # 03:00 daily
  draft-cleanup:
    cron: "0 0 2 * * *"  # 02:00 daily off-peak
    grace-period-hours: 24
    tombstone-retention-days: 30
```

- [ ] Configure **Logging**:

```yaml
logging:
  level:
    root: INFO
    com.ecommerce.search: DEBUG
    org.elasticsearch.client: INFO
    org.springframework.kafka: INFO
```

### 4.2 File: `application-dev.yml` ✅

**Path:** `java/services/search-service/src/main/resources/application-dev.yml`

- [x] Override `spring.elasticsearch.uris: http://localhost:9200`
- [x] Override `spring.kafka.bootstrap-servers: localhost:9092`
- [x] Set `search.cors.allowed-origins: "*"` (dev only)
- [x] Set `logging.level.com.ecommerce.search: DEBUG`

### 4.3 File: `application-prod.yml` ✅

**Path:** `java/services/search-service/src/main/resources/application-prod.yml`

- [x] Configure ES URI từ environment (no default)
- [x] Configure Kafka SASL (nếu production yêu cầu)
- [x] Set `search.cors.allowed-origins: ${CORS_ALLOWED_ORIGINS:https://shop.example.com}`
- [x] Set `logging.level.com.ecommerce.search: INFO`
- [x] Enable JSON log encoder (override logging config)

### 4.4 File: `logback-spring.xml` ✅

**Path:** `java/services/search-service/src/main/resources/logback-spring.xml`

- [x] Create Logback config với JSON encoder cho prod profile
- [x] Define MDC keys: `request_id`, `user_id`, `topic`, `partition`, `offset`, `event_type`
- [x] Plain text cho dev profile, JSON cho prod profile

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <springProperty scope="context" name="appName" source="spring.application.name" defaultValue="search-service"/>

    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} [request_id=%X{request_id}, user_id=%X{user_id}, topic=%X{topic}] - %msg%n</pattern>
        </encoder>
    </appender>

    <springProfile name="prod">
        <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
            <encoder class="net.logstash.logback.encoder.LogstashEncoder">
                <includeMdcKeyName>request_id</includeMdcKeyName>
                <includeMdcKeyName>user_id</includeMdcKeyName>
                <includeMdcKeyName>topic</includeMdcKeyName>
                <includeMdcKeyName>partition</includeMdcKeyName>
                <includeMdcKeyName>offset</includeMdcKeyName>
                <includeMdcKeyName>event_type</includeMdcKeyName>
            </encoder>
        </appender>
        <root level="INFO">
            <appender-ref ref="JSON"/>
        </root>
    </springProfile>

    <springProfile name="!prod">
        <root level="INFO">
            <appender-ref ref="CONSOLE"/>
        </root>
    </springProfile>
</configuration>
```

---

## 5. Spring Configuration Classes

### 5.1 File: `config/SearchProperties.java` ✅

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/config/SearchProperties.java`

- [x] Create `@ConfigurationProperties(prefix = "search")` class
- [x] Define nested classes: `Jwt`, `Es`, `Cors`, `Pagination`, `Reindex`, `Suggestions`, `DraftCleanup`
- [x] Add `@Validated` annotation
- [x] Use Jakarta Validation annotations: `@NotBlank`, `@Min`, `@Max`, `@Pattern`

```java
package com.ecommerce.search.config;

import jakarta.validation.constraints.*;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Data
@Validated
@ConfigurationProperties(prefix = "search")
public class SearchProperties {
    @NotNull
    private Jwt jwt = new Jwt();
    @NotNull
    private Es es = new Es();
    @NotNull
    private Cors cors = new Cors();
    @NotNull
    private Pagination pagination = new Pagination();
    private Reindex reindex = new Reindex();
    private Suggestions suggestions = new Suggestions();
    private DraftCleanup draftCleanup = new DraftCleanup();

    @Data
    public static class Jwt {
        private boolean trustGateway = true;
        @NotBlank
        private String claimsHeader = "X-User-Claims";
    }

    @Data
    public static class Es {
        @Min(1000)
        private int queryTimeoutProductMs = 5000;
        @Min(500)
        private int queryTimeoutSuggestMs = 2000;
        @Min(1)
        private int updateByQueryThrottleRps = 300;
        @NotNull
        private IndexAliases indexAliases = new IndexAliases();
    }

    @Data
    public static class IndexAliases {
        @NotBlank private String products = "products";
        @NotBlank private String productsWrite = "products_write";
        @NotBlank private String categories = "categories";
        @NotBlank private String categoriesWrite = "categories_write";
        @NotBlank private String suggestions = "search_suggestions";
    }

    @Data
    public static class Cors {
        @NotBlank
        private String allowedOrigins = "*";
    }

    @Data
    public static class Pagination {
        @Min(1) private int defaultSize = 20;
        @Min(1) @Max(100) private int maxSize = 100;
    }

    @Data
    public static class Reindex {
        @Min(100) private int checkpointIntervalDocs = 5000;
        @Min(100) private int buildChunkSize = 2000;
    }

    @Data
    public static class Suggestions {
        @NotBlank
        private String rebuildCron = "0 0 3 * * *";
    }

    @Data
    public static class DraftCleanup {
        @NotBlank
        private String cron = "0 0 2 * * *";
        @Min(1) private int gracePeriodHours = 24;
        @Min(1) private int tombstoneRetentionDays = 30;
    }
}
```

### 5.2 File: `config/JacksonConfig.java` ✅

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/config/JacksonConfig.java`

- [x] Configure `ObjectMapper` với:
  - `JavaTimeModule` (cho Instant, LocalDateTime)
  - `PropertyNamingStrategies.SNAKE_CASE` cho JSON serialization
  - `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES = false`
  - `SerializationFeature.WRITE_DATES_AS_TIMESTAMPS = false`
- [x] Initialize `JsonUtils` static helper via `@PostConstruct`

### 5.3 File: `config/SecurityConfig.java` ✅

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/config/SecurityConfig.java`

- [x] Configure Spring Security cho stateless service
- [x] CORS config (từ `search.cors.allowed-origins`)
- [x] Security headers (X-Content-Type-Options, X-Frame-Options, HSTS, Referrer-Policy)
- [x] Disable CSRF (vì stateless + JWT trust)
- [x] Permit all ở FilterSecurity level (Gateway đã verify JWT)
- [x] Add JWT claim filter từ `JwtClaimUtil` (header `X-User-Claims`) để populate MDC

### 5.4 File: `config/WebConfig.java` ✅

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/config/WebConfig.java`

- [x] Configure CORS via `WebMvcConfigurer`

---

## 6. Common Constants & Error Codes

### 6.1 File: `common/ServiceConstants.java` ✅

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/common/ServiceConstants.java`

- [x] Define constants:
  - `SERVICE_NAME = "search-service"`
  - `DEFAULT_PAGE_SIZE = 20`
  - `MAX_PAGE_SIZE = 100`
  - `MAX_SUGGEST_SIZE = 20`
- [x] Define metric names, Kafka topics, consumer group IDs

### 6.2 File: `common/ErrorCode.java` ✅

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/common/ErrorCode.java`

- [x] Create enum `ErrorCode` với values:
  - `INVALID_QUERY_PARAMETER` (400)
  - `INVALID_CURSOR` (400)
  - `UNAUTHORIZED` (401)
  - `FORBIDDEN` (403)
  - `CATEGORY_NOT_FOUND` (404)
  - `INVALID_LANGUAGE` (422)
  - `INTERNAL_ERROR` (500)
  - `REINDEX_ALREADY_RUNNING` (409)
  - `ELASTICSEARCH_UNAVAILABLE` (503)
  - `SEARCH_TIMEOUT` (504)
- [x] Each enum value có `code` (String), `httpStatus` (int), `message` (String)

### 6.3 File: `common/EventType.java` ✅

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/common/EventType.java`

- [x] Create enum `EventType` với values:
  - `PRODUCT_CREATED`, `PRODUCT_UPDATED`
  - `SKU_UPDATED`, `SKU_CREATED`, `PRODUCT_DELETED`, `SKU_DELETED`
  - `CATEGORY_CREATED`, `CATEGORY_UPDATED`, `CATEGORY_DELETED`
  - `REVIEW_METRICS_UPDATED`
- [x] For each event: `eventType` (String), `topic` (String)

### 6.4 File: `dto/ErrorResponse.java` ✅

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/dto/ErrorResponse.java`

- [x] Create DTO với fields: `error_code`, `message`, `details` (Map<String, Object>), `request_id`
- [x] Use Lombok `@Data` + `@Builder`
- [x] JSON serialization snake_case (via Jackson global config)

---

## 7. Global Exception Handler

### 7.1 File: `exception/GlobalExceptionHandler.java` ✅

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/exception/GlobalExceptionHandler.java`

- [x] Create `@RestControllerAdvice` class
- [x] Handle các exception types:
  - `InvalidCursorException` → 400 `INVALID_CURSOR`
  - `MethodArgumentNotValidException` (validation) → 400 `INVALID_QUERY_PARAMETER`
  - `ConstraintViolationException` (param validation) → 400
  - `SearchException` → use exception's `ErrorCode`
  - `Exception` (catch-all) → 500 `INTERNAL_ERROR`
- [x] Auto-include `request_id` từ MDC context vào response
- [x] Log error với full context (MDC, exception stack)

```java
package com.ecommerce.search.exception;

import com.ecommerce.search.common.ErrorCode;
import com.ecommerce.search.dto.ErrorResponse;
import com.ecommerce.search.util.JwtClaimUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidCursorException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCursor(InvalidCursorException ex, HttpServletRequest req) {
        log.warn("Invalid cursor: {}", ex.getMessage());
        return buildResponse(ErrorCode.INVALID_CURSOR, ex.getMessage(), null, req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        Map<String, Object> details = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(err ->
            details.put(err.getField(), err.getDefaultMessage())
        );
        return buildResponse(ErrorCode.INVALID_QUERY_PARAMETER, "Validation failed", details, req);
    }

    @ExceptionHandler(SearchException.class)
    public ResponseEntity<ErrorResponse> handleSearch(SearchException ex, HttpServletRequest req) {
        log.error("Search error: {}", ex.getMessage(), ex);
        return buildResponse(ex.getErrorCode(), ex.getMessage(), null, req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest req) {
        log.error("Unexpected error", ex);
        return buildResponse(ErrorCode.INTERNAL_ERROR, "Internal server error", null, req);
    }

    private ResponseEntity<ErrorResponse> buildResponse(ErrorCode code, String message, Map<String, Object> details, HttpServletRequest req) {
        String requestId = MDC.get("request_id");
        if (requestId == null) {
            requestId = req.getHeader("X-Request-Id");
        }
        ErrorResponse body = ErrorResponse.builder()
            .errorCode(code.getCode())
            .message(message)
            .details(details)
            .requestId(requestId)
            .build();
        return ResponseEntity.status(HttpStatus.valueOf(code.getHttpStatus())).body(body);
    }
}
```

### 7.2 File: `domain/exception/SearchException.java` ✅

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/domain/exception/SearchException.java`

- [x] Create `SearchException extends RuntimeException`
- [x] Fields: `errorCode` (ErrorCode)
- [x] Constructor: `(ErrorCode, String message)`, `(ErrorCode, String message, Throwable cause)`

### 7.3 File: `domain/exception/InvalidCursorException.java` ✅

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/domain/exception/InvalidCursorException.java`

- [x] Create `InvalidCursorException extends RuntimeException`
- [x] Constructor: `(String message)`, `(String message, Throwable cause)`

---

## 8. Utilities

### 8.1 File: `util/JwtClaimUtil.java` ✅

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/util/JwtClaimUtil.java`

- [x] Create utility class đọc JWT claims từ Gateway-injected header `X-User-Claims`
- [x] Methods:
  - `String getUserId(HttpServletRequest)` - extract user_id
  - `String getRole(HttpServletRequest)` - extract role (BUYER | ADMIN)
  - `String getRequestId(HttpServletRequest)` - extract request_id
  - `String getSessionId(HttpServletRequest)` - extract session_id
- [x] Parse JSON header value (use Jackson ObjectMapper)
- [x] Add request_id to MDC context sau khi parse (centralized)

```java
package com.ecommerce.search.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtClaimUtil {

    private final ObjectMapper objectMapper;
    private final com.ecommerce.search.config.SearchProperties properties;

    public String getUserId(HttpServletRequest req) {
        return extractClaim(req, "user_id");
    }

    public String getRole(HttpServletRequest req) {
        return extractClaim(req, "role");
    }

    public String getRequestId(HttpServletRequest req) {
        return extractClaim(req, "request_id");
    }

    public String getSessionId(HttpServletRequest req) {
        return extractClaim(req, "session_id");
    }

    public void populateMdcContext(HttpServletRequest req) {
        String requestId = getRequestId(req);
        if (requestId != null) {
            MDC.put("request_id", requestId);
        }
        String userId = getUserId(req);
        if (userId != null) {
            MDC.put("user_id", userId);
        }
    }

    private String extractClaim(HttpServletRequest req, String claimName) {
        try {
            String header = req.getHeader(properties.getJwt().getClaimsHeader());
            if (header == null) return null;
            Map<String, Object> claims = objectMapper.readValue(header, Map.class);
            Object value = claims.get(claimName);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            log.warn("Failed to parse JWT claim: {}", claimName, e);
            return null;
        }
    }
}
```

### 8.2 File: `util/TimeUtils.java` ✅

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/util/TimeUtils.java`

- [x] Create utility class cho time conversions:
  - `Instant toInstant(String iso8601)` (parse)
  - `long toEpochMillis(Instant)`
  - `Instant fromEpochMillis(long)`
  - `String toIsoString(Instant)`

### 8.3 File: `util/JsonUtils.java` ✅

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/util/JsonUtils.java`

- [x] Create utility class với static methods:
  - `String toJson(Object)`
  - `<T> T fromJson(String, Class<T>)`
  - `<T> T fromJson(String, TypeReference<T>)`
- [x] Methods delegate to injected `ObjectMapper` (initialized via `JacksonConfig.initJsonUtils()`)

---

## 9. Security Headers Filter

### 9.1 File: `config/SecurityHeadersFilter.java` ✅

**Path:** `java/services/search-service/src/main/java/com/ecommerce/search/config/SecurityHeadersFilter.java`

- [x] Create `OncePerRequestFilter` add security headers vào mọi response:
  - `X-Content-Type-Options: nosniff`
  - `X-Frame-Options: DENY`
  - `Strict-Transport-Security: max-age=31536000; includeSubDomains`
  - `Referrer-Policy: no-referrer`
- [x] Register filter cho cả public + internal API
- [x] Skip filter cho Actuator endpoints (để health probes work)

---

## 10. Acceptance Criteria

- [x] `mvn compile` succeeds without errors
- [ ] `SearchServiceApplication` starts và connect được tới ES + Kafka (cần ES/Kafka running)
- [x] `application.yml` is valid YAML với tất cả properties
- [ ] Health check endpoints return 200 (cần ES/Kafka running)
- [x] All custom properties (search.*) load successfully với validation
- [x] Security headers present trong mọi response
- [x] CORS configured đúng
- [x] Global exception handler trả về ErrorResponse format chuẩn

---

## 11. Implementation File Index

| # | File | Status |
|---|------|--------|
| 1 | `pom.xml` | ✅ Completed |
| 2 | `SearchServiceApplication.java` | ✅ Completed |
| 3 | `application.yml` | ✅ Completed |
| 4 | `application-dev.yml` | ✅ Completed |
| 5 | `application-prod.yml` | ✅ Completed |
| 6 | `logback-spring.xml` | ✅ Completed |
| 7 | `SearchProperties.java` | ✅ Completed |
| 8 | `JacksonConfig.java` | ✅ Completed |
| 9 | `SecurityConfig.java` | ✅ Completed |
| 10 | `WebConfig.java` | ✅ Completed |
| 11 | `ServiceConstants.java` | ✅ Completed |
| 12 | `ErrorCode.java` | ✅ Completed |
| 13 | `EventType.java` | ✅ Completed |
| 14 | `ErrorResponse.java` | ✅ Completed |
| 15 | `GlobalExceptionHandler.java` | ✅ Completed |
| 16 | `SearchException.java` | ✅ Completed |
| 17 | `InvalidCursorException.java` | ✅ Completed |
| 18 | `JwtClaimUtil.java` | ✅ Completed |
| 19 | `TimeUtils.java` | ✅ Completed |
| 20 | `JsonUtils.java` | ✅ Completed |
| 21 | `SecurityHeadersFilter.java` | ✅ Completed |
| 22 | `SearchServiceApplicationTests.java` | ✅ Completed |
| 23 | `CloudEventEnvelope.java` | ✅ Completed (added for Kafka deserialization) |
