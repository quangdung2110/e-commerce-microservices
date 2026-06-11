# Module 15: Deployment and Operations

> **Status:** Module 15 of 15
> **Prerequisites:** Module 01 (foundation)
> **Dependents:** None (leaf module)

---

## 1. Overview

This module covers deployment artifacts, containerization, environment configuration, CI/CD considerations, and operational runbooks.

---

## 2. Docker Configuration

### 2.1 File: `docker/Dockerfile`

**Path:** `product-service/docker/Dockerfile`

- [ ] Use Eclipse Temurin JDK 21 Alpine image as base
- [ ] Add Flight Recorder enabling flags
- [ ] Copy Maven wrapper and project files
- [ ] Build with Maven: `mvn package -DskipTests`
- [ ] Use multi-stage build:
  - Stage 1: build with Maven
  - Stage 2: runtime with JRE only ( slim image)
- [ ] Set `JAVA_OPTS` for JVM tuning (heap, GC)
- [ ] Expose ports: `8080` (HTTP), `9090` (gRPC)
- [ ] Health check: `curl -f http://localhost:8080/actuator/health`

### 2.2 File: `docker/docker-compose.yml`

**Path:** `product-service/docker/docker-compose.yml`

- [ ] Define `product-service` service:
  - Build from Dockerfile
  - Port mappings: `8080:8080`, `9090:9090`
  - Environment: `SPRING_PROFILES_ACTIVE=dev`
  - Dependencies: `postgres`, `redis`, `kafka` (from docker-compose stack)
  - Resource limits: 2 CPU, 2GB RAM
  - Health check with interval
- [ ] Include shared infrastructure services for local dev:
  - PostgreSQL 16
  - Redis 7
  - Kafka (KRaft mode)
  - Kafka UI (for debugging)

### 2.3 File: `docker/.env.example`

**Path:** `product-service/docker/.env.example`

- [ ] Environment variables template:
  - `DATABASE_HOST`, `DATABASE_PORT`, `DATABASE_NAME`, `DATABASE_USER`, `DATABASE_PASSWORD`
  - `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`
  - `KAFKA_BOOTSTRAP_SERVERS`
  - `DEBEZIUM_CONNECT_HOST`, `DEBEZIUM_CONNECT_PORT`
  - `GRPC_SERVER_PORT`
  - `SPRING_PROFILES_ACTIVE`
  - `JAVA_OPTS` (heap size, GC tuning)
  - `LOGGING_LEVEL`
  - `JWT_SECRET` (for admin API auth)

---

## 3. Kubernetes Manifests

### 3.1 File: `k8s/deployment.yml`

**Path:** `product-service/k8s/deployment.yml`

- [ ] Define `Deployment`:
  - Replicas: 3 (configurable)
  - `strategy: RollingUpdate` with `maxSurge: 1`, `maxUnavailable: 0`
  - Readiness probe: `httpGet /actuator/health/readiness` (initialDelay: 30s, period: 10s)
  - Liveness probe: `httpGet /actuator/health/liveness` (initialDelay: 60s, period: 15s)
  - Resource requests: `500m CPU`, `512Mi memory`
  - Resource limits: `2000m CPU`, `2Gi memory`
  - Environment variables from `ConfigMap` and `Secret`
  - `terminationGracePeriodSeconds: 60` (for graceful shutdown)
- [ ] Configure `PodDisruptionBudget`:
  - `minAvailable: 2` (ensure at least 2 pods available during updates)

### 3.2 File: `k8s/service.yml`

**Path:** `product-service/k8s/service.yml`

- [ ] Define `Service`:
  - Type: `ClusterIP`
  - Ports: `http-8080`, `grpc-9090`
  - Selector: `app: product-service`

### 3.3 File: `k8s/hpa.yml`

**Path:** `product-service/k8s/hpa.yml`

- [ ] Define `HorizontalPodAutoscaler`:
  - Min replicas: 2
  - Max replicas: 10
  - Target CPU: 70%
  - Target memory: 80%

### 3.4 File: `k8s/configmap.yml`

**Path:** `product-service/k8s/configmap.yml`

- [ ] Define `ConfigMap`:
  - `application.yml` values
  - Kafka bootstrap servers
  - Topic names
  - Consumer group IDs
  - Cron expressions for jobs
  - Inventory sync interval (500ms)

### 3.5 File: `k8s/secrets.yml`

**Path:** `product-service/k8s/secrets.yml`

- [ ] Define `Secret`:
  - Database credentials (base64 encoded)
  - Redis password (base64 encoded)
  - JWT secret (base64 encoded)
  - Debezium connector credentials

---

## 4. CI/CD Pipeline

### 4.1 File: `.github/workflows/ci.yml`

**Path:** `product-service/.github/workflows/ci.yml`

- [ ] Trigger: push to `main`, pull requests
- [ ] Jobs:
  1. **Build & Test**:
     - Setup JDK 21
     - `mvn dependency:go-offline`
     - `mvn compile`
     - `mvn test` (unit + integration)
     - `mvn verify` (includes ArchUnit tests)
  2. **Security Scan**:
     - OWASP dependency check
     - `mvn spotless:check` (code style)
     - `mvn archunit:check`
  3. **Build Image**:
     - `mvn package -DskipTests`
     - Docker build and push to registry
     - Tag with commit SHA
  4. **Deploy to Staging**:
     - Apply Kubernetes manifests
     - Wait for rollout
     - Run smoke tests
- [ ] Notifications: Slack on failure

### 4.2 File: `.github/workflows/release.yml`

**Path:** `product-service/.github/workflows/release.yml`

- [ ] Trigger: tag `v*`
- [ ] Jobs:
  1. Build and push Docker image with tag
  2. Update Helm chart version
  3. Create GitHub release
  4. Notify Slack

---

## 5. Environment Configuration

### 5.1 File: `config/application.yml`

**Path:** `product-service/config/application.yml`

**Reference configuration for externalized config management.**

- [ ] All configurable properties with defaults
- [ ] Environment variable overrides clearly documented
- [ ] Profile-specific configurations: dev, staging, prod

### 5.2 File: `config/debezium-connector.json`

**Path:** `product-service/config/debezium-connector.json`

- [ ] Debezium PostgreSQL Source Connector configuration
- [ ] Topic routing SMT configuration
- [ ] Snapshot and incremental modes

---

## 6. Operational Runbooks

### 6.1 File: `docs/runbooks/inventory-sync-degraded.md`

**Path:** `product-service/docs/runbooks/inventory-sync-degraded.md`

- [ ] **Symptom:** Pending inventory sync queue growing
- [ ] **Diagnosis**: Check `inventory.sync.pending_queue.size` metric, check Phase 2 worker logs
- [ ] **Mitigation**: Scale Phase 2 worker replicas, check DB locks
- [ ] **Recovery**: Drain queue manually via admin endpoint

### 6.2 File: `docs/runbooks/markdown-stuck.md`

**Path:** `product-service/docs/runbooks/markdown-stuck.md`

- [ ] **Symptom:** Promotional price not applying or not expiring
- [ ] **Diagnosis**: Check `MARKDOWN_ENDED` events from Promotion Service, check `product:markdown:active:*` Redis keys
- [ ] **Mitigation**: Trigger manual markdown expiry job, check Promotion Service
- [ ] **Recovery**: Run `MarkdownExpiryJob` manually

### 6.3 File: `docs/runbooks/outbox-backlog.md`

**Path:** `product-service/docs/runbooks/outbox-backlog.md`

- [ ] **Symptom:** Outbox table growing, Debezium lag
- [ ] **Diagnosis**: Check `product_outbox_events` row count, Debezium connector status
- [ ] **Mitigation**: Check Debezium connector, check PostgreSQL WAL size
- [ ] **Recovery**: Increase Debezium poll interval, check replication slot

---

## 7. Feature Flags

### 7.1 File: `config/feature-flags.yml`

**Path:** `product-service/config/feature-flags.yml`

- [ ] Feature flags for gradual rollout:
  - `inventory-sync-two-phase.enabled` (default: true)
  - `markdown-worker.enabled` (default: true)
  - `grpc-endpoint.enabled` (default: true)
  - `fsm-auto-hide.enabled` (default: true)
  - `fsm-auto-reactivate.enabled` (default: true)

---

## 8. Acceptance Criteria

- [ ] Dockerfile builds successfully and produces a working container image
- [ ] docker-compose starts all services locally with correct dependencies
- [ ] Kubernetes manifests deploy to a cluster successfully
- [ ] HPA scales pods based on CPU/memory metrics
- [ ] CI pipeline passes all stages: build, test, security scan, image build
- [ ] All environment variables are documented with example values
- [ ] Debezium connector configuration is validated against PostgreSQL WAL
- [ ] Runbooks provide clear steps for common operational issues
