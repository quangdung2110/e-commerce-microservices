# Module 14: Deployment & Operations

> **Status:** Module 14 of 14 - **NOT YET IMPLEMENTED**
> **Prerequisites:** All previous modules
> **Dependents:** None
> **Estimated Effort:** 1 person-day

> ## Implementation Note
> Chưa implement. Plan chi tiết bên dưới. Khi implement, đánh dấu `[x]` cho từng task.

---

## 1. Overview

This module configures deployment:
- **Dockerfile** (multi-stage build) - Production runtime image
- **docker-compose.yml** - Local dev stack (ES + Kafka + Zookeeper + Search Service)
- **Environment configuration** - Production env vars
- **CI/CD considerations** - Build + test pipeline
- **Operational runbook** - Common operations (reindex, rollback, monitoring)

---

## 2. Dockerfile

### 2.1 File: `Dockerfile`

**Path:** `java/services/search-service/Dockerfile`

- [ ] Multi-stage build (Maven build + JRE runtime)
- [ ] Use Eclipse Temurin 17
- [ ] Non-root user
- [ ] Health check via curl

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

---

## 3. Docker Compose (Local Development)

### 3.1 File: `docker-compose.yml`

**Path:** `java/services/search-service/docker-compose.yml`

- [ ] Elasticsearch 8.11.0 (single-node, security disabled for dev)
- [ ] Kafka 7.5.0 + Zookeeper
- [ ] Search Service (build from local Dockerfile)
- [ ] Health checks cho ES + Kafka

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

---

## 4. Environment Configuration

### 4.1 Production Environment Variables

```bash
# Elasticsearch
ES_URIS=https://es-cluster:9200
ES_USERNAME=search-service
ES_PASSWORD=<secret-from-vault>

# Kafka
KAFKA_BOOTSTRAP=kafka-1:9092,kafka-2:9092,kafka-3:9092
KAFKA_SASL_USERNAME=search-service
KAFKA_SASL_PASSWORD=<secret-from-vault>

# Spring profile
SPRING_PROFILES_ACTIVE=prod

# Logging
LOGGING_LEVEL_COM_ECOMMERCE_SEARCH=INFO
LOGGING_LEVEL_ORG_ELASTICSEARCH_CLIENT=WARN

# Management
MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE=health,info,prometheus
```

### 4.2 Secrets Management

Use Kubernetes Secrets hoặc HashiCorp Vault. **KHÔNG BAO GIỜ** hardcode credentials trong:
- `application.yml`
- `Dockerfile`
- `docker-compose.yml`
- Source code

Production credentials load qua env vars từ secrets manager.

---

## 5. CI/CD Pipeline

### 5.1 Build Pipeline (GitHub Actions / GitLab CI)

```yaml
# .github/workflows/build.yml
name: Build and Test

on: [push, pull_request]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: 17
          distribution: temurin
      - name: Cache Maven dependencies
        uses: actions/cache@v3
        with:
          path: ~/.m2
          key: ${{ runner.os }}-m2-${{ hashFiles('**/pom.xml') }}
      - name: Build
        run: mvn clean compile
      - name: Unit tests
        run: mvn test
      - name: Integration tests (Testcontainers)
        run: mvn verify -Pintegration
      - name: Package
        run: mvn package -DskipTests
      - name: Build Docker image
        run: docker build -t search-service:${{ github.sha }} .
      - name: Push to registry
        if: github.ref == 'refs/heads/main'
        run: docker push search-service:${{ github.sha }}
```

### 5.2 Deployment (Docker Compose cho personal project)

```bash
# 1. Build image
cd java/services/search-service
docker build -t search-service:latest .

# 2. Start stack
docker-compose up -d

# 3. Check status
docker-compose ps
docker-compose logs -f search-service

# 4. Verify
curl http://localhost:8080/actuator/health/liveness
curl "http://localhost:8080/api/v1/search/products?q=iphone"
```

---

## 6. Monitoring & Alerting

### 6.1 Prometheus Scrape Config

```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'search-service'
    metrics_path: '/actuator/prometheus'
    scrape_interval: 15s
    static_configs:
      - targets: ['search-service:8080']
```

### 6.2 Alert Rules

```yaml
# search-service-alerts.yml
groups:
  - name: search-service
    rules:
      - alert: SearchHighLatency
        expr: histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket[5m])) by (le, uri)) > 0.2
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Search API p99 > 200ms"

      - alert: KafkaConsumerLag
        expr: kafka_consumer_lag_records > 10000
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "Kafka consumer lag > 10K records"

      - alert: IndexingFailureRate
        expr: |
          rate(search_indexing_total{status="fail"}[5m])
          / rate(search_indexing_total[5m]) > 0.01
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "Indexing failure rate > 1%"

      - alert: ESUnavailable
        expr: rate(es_error_total[5m]) > 0.1
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "ES error rate > 10/min"
```

### 6.3 Grafana Dashboard

Recommended panels:
- HTTP request latency (p50, p95, p99)
- Kafka consumer lag (per topic, partition)
- Indexing success/fail rate
- ES query latency
- ES error rate
- Search service memory + CPU usage

---

## 7. Operational Runbook

### 7.1 Common Operations

#### Restart Service
```bash
docker-compose restart search-service
# or in k8s:
kubectl rollout restart deployment/search-service
```

#### Check Health
```bash
curl http://search-service:8080/actuator/health/readiness
curl http://search-service:8080/actuator/prometheus | head
```

#### View Logs
```bash
docker-compose logs -f search-service --tail 100
# or in k8s:
kubectl logs -f deployment/search-service --tail 100
```

### 7.2 Trigger Full Reindex

```bash
# 1. Get admin JWT token (from Auth Service)
ADMIN_JWT="eyJ..."

# 2. Trigger reindex
curl -X POST http://search-service:8080/internal/v1/search/reindex \
  -H "Authorization: Bearer $ADMIN_JWT" \
  -H "Content-Type: application/json" \
  -d '{"reason":"Quarterly mapping refresh","triggered_by":"admin@company.com"}'

# Response:
# {"run_id":"reindex-2026-06-22T15-30-00-abc123","status":"STARTED",...}

# 3. Poll status
curl http://search-service:8080/internal/v1/search/reindex/reindex-2026-06-22T15-30-00-abc123 \
  -H "Authorization: Bearer $ADMIN_JWT"

# 4. Monitor phases: BUILDING → CATCHING_UP → CUTOVER → COMPLETED
```

### 7.3 Rollback Reindex (if CUTOVER fails)

```bash
# In ES, manually swap alias back:
curl -X POST http://es-cluster:9200/_aliases \
  -H "Content-Type: application/json" \
  -d '{
    "actions": [
      {"remove": {"index": "products_v_NEW", "alias": "products"}},
      {"add": {"index": "products_v_OLD", "alias": "products"}}
    ]
  }'
```

### 7.4 Rebuild Suggestions Index (manual)

Suggestions Rebuilder chạy daily 03:00 AM. Để trigger manual:

```bash
# Option 1: Wait for next scheduled run
# Option 2: Use Spring Boot Admin (if available)
# Option 3: SSH into pod and trigger via actuator (if scheduled jobs exposed)
```

### 7.5 Investigate Lag Spike

```bash
# 1. Check current lag
curl http://search-service:8080/actuator/prometheus | grep kafka_consumer_lag

# 2. Check ES cluster health
curl http://es-cluster:9200/_cluster/health?pretty

# 3. Check search-service logs
kubectl logs -f deployment/search-service --tail 200

# 4. Check for poison messages (in DLQ - we don't have one, but check metrics)
curl http://search-service:8080/actuator/prometheus | grep -E "search_payload_invalid|search_document_missing"
```

### 7.6 Investigate `document_missing_exception`

Nếu metric `search_document_missing_total` tăng:
- **Nguyên nhân:** Event `SKU_UPDATED`/`PRODUCT_UPDATED` đến trước khi document tồn tại (race condition khi product vừa được tạo).
- **Xử lý:** Log + skip + ACK. Khi `PRODUCT_CREATED` cuối cùng đến, document sẽ được tạo với state hiện tại.
- **Nếu tỷ lệ > 1%:** Check Kafka consumer lag và event order.

### 7.7 Recover from ES Cluster Failure

```bash
# 1. Check ES health
curl http://es-cluster:9200/_cluster/health?pretty

# 2. If cluster down: ES will return 503 → Search Service trả 503 cho clients
# 3. After ES recovers, ES will catch up
# 4. Verify with Full Reindex Job (Module 10) if needed
```

---

## 8. Performance Tuning

### 8.1 JVM Tuning

Default: `MaxRAMPercentage=75.0` (75% of container memory).
For 2GB container: heap ~1.5GB. ES recommends heap < 31GB.

**Optional:** Use G1GC (default) or ZGC for low-latency applications.

### 8.2 Tomcat Thread Pool

Default: 200 threads. For 1K req/s with 50ms p99 → 50 concurrent requests needed. 200 is safe.

### 8.3 Kafka Consumer Concurrency

5 threads (1 per partition). Đảm bảo Kafka có 5 partitions total (3 product + 1 category + 1 review).

### 8.4 ES Client Timeout

- Product search: 5s
- Suggest: 2s
- _update_by_query: 30s (batch)
- Connection: 5s

### 8.5 Connection Pool

ES Java Client tự quản lý connection pool. Không cần config thêm.

---

## 9. Backup & Disaster Recovery

### 9.1 ES Backup

**Decision:** Không cần backup ES. Search Service là read model; có thể rebuild hoàn toàn từ PostgreSQL qua Full Reindex Job.

### 9.2 Disaster Recovery

- **Service crash:** Kubernetes/Docker restart tự động. Consumer re-subscribes, processes backlog từ Kafka offset.
- **ES data corruption:** Trigger Full Reindex Job để rebuild.
- **Kafka outage:** Consumer pause (nack). Khi Kafka recover, consumer resume.
- **Schema conflict:** Log + metric + skip + ACK (Phase 22b). Fix manually via ops.

---

## 10. Acceptance Criteria

- [ ] Dockerfile builds successfully (multi-stage, non-root, health check)
- [ ] docker-compose.yml starts ES + Kafka + Search Service
- [ ] `curl http://localhost:8080/actuator/health/liveness` returns 200
- [ ] Search API endpoints return correct responses
- [ ] Trigger reindex via internal API with JWT ADMIN
- [ ] View metrics at `/actuator/prometheus`
- [ ] Logs are JSON format in prod profile
- [ ] No credentials in source code
- [ ] CI/CD pipeline builds + tests + pushes image

---

## 11. Implementation File Index

| # | File | Status |
|---|------|--------|
| 1 | `Dockerfile` | ⏳ Not yet |
| 2 | `docker-compose.yml` | ⏳ Not yet |
| 3 | `README.md` (quick start) | ⏳ Not yet |
| 4 | `.github/workflows/build.yml` (optional) | ⏳ Not yet |
| 5 | `prometheus-alerts.yml` (optional) | ⏳ Not yet |
| 6 | `grafana-dashboard.json` (optional) | ⏳ Not yet |
