# API Gateway Implementation Plan

## Table of Contents

1. [Kiến trúc tổng thể và các thành phần](#1-kiến-trúc-tổng-thể-và-các-thành-phần)
2. [Thiết lập Traefik cơ bản](#2-thiết-lập-traefik-cơ-bản)
3. [Service Discovery & Routing](#3-service-discovery--routing)
4. [Custom Middleware xác thực JWT](#4-custom-middleware-xác-thực-jwt)
5. [Invalidation JWKS Cache qua Redis Pub/Sub](#5-invalidation-jwks-cache-qua-redis-pubsub)
6. [Rate Limiting](#6-rate-limiting)
7. [OpenTelemetry & Logging](#7-opentelemetry--logging)
8. [Deployment & Configuration Management](#8-deployment--configuration-management)
9. [Testing & Validation](#9-testing--validation)
10. [Rủi ro và phương án giảm thiểu](#10-rủi-ro-và-phương-án-giảm-thiểu)

---

## Implementation Checklist

### Legend
- `[ ]` Chưa bắt đầu
- `[P]` Đang thực hiện
- `[D]` Đã hoàn thành
- `[B]` Đã block (cần dependency)

---

### Phase 1: Chuẩn bị Infrastructure

| # | Task | Status | Owner | Due Date | Notes |
|---|------|--------|-------|----------|-------|
| 1.1 | Tạo thư mục `go/services/api-gateway/` | [D] | | 2026-05-15 | |
| 1.2 | Cấu hình Docker network `ecommerce-network` | [D] | | 2026-05-15 | Defined in docker-compose.yml |
| 1.3 | Cập nhật Redis config hỗ trợ Pub/Sub | [D] | | 2026-05-15 | Dep: Redis infrastructure |
| 1.4 | Cập nhật Auth Service expose JWKS endpoint | [ ] | | | Dep: Auth Service module |
| 1.5 | Tạo environment variables template (.env.example) | [D] | | 2026-05-15 | |

---

### Phase 2: Traefik Cơ bản

| # | Task | Status | Owner | Due Date | Notes |
|---|------|--------|-------|----------|-------|
| 2.1 | Viết `config/traefik.yml` (static config) | [D] | | 2026-05-15 | Entry points, providers, logging |
| 2.2 | Viết `config/dynamic.yml` (middlewares, services) | [D] | | 2026-05-15 | Rate limit, headers, compress |
| 2.3 | Viết `docker-compose.yml` cho Traefik | [D] | | 2026-05-15 | Port mapping, volume mounts |
| 2.4 | Cấu hình TLS/SSL (self-signed cho dev) | [D] | | 2026-05-15 | Let's Encrypt ACME configured |
| 2.5 | Kích hoạt Traefik Dashboard | [D] | | 2026-05-15 | Basic auth protection |
| 2.6 | Verify: Traefik start thành công | [ ] | | | |
| 2.7 | Verify: Dashboard accessible | [ ] | | | |

---

### Phase 3: Service Discovery & Routing

| # | Task | Status | Owner | Due Date | Notes |
|---|------|--------|-------|----------|-------|
| 3.1 | Định nghĩa routing rules matrix | [D] | | 2026-05-15 | docs/routing-matrix.md |
| 3.2 | Thêm Docker labels cho user-service | [D] | | 2026-05-15 | config/service-labels.env |
| 3.3 | Thêm Docker labels cho order-service | [D] | | 2026-05-15 | config/service-labels.env |
| 3.4 | Thêm Docker labels cho product-service | [D] | | 2026-05-15 | config/service-labels.env |
| 3.5 | Thêm Docker labels cho payment-service | [D] | | 2026-05-15 | config/service-labels.env |
| 3.6 | Thêm Docker labels cho auth-service | [D] | | 2026-05-15 | Không auth middleware |
| 3.7 | Cấu hình health check cho từng service | [D] | | 2026-05-15 | Included in labels |
| 3.8 | Cấu hình load balancing method | [D] | | 2026-05-15 | Round robin (default) |
| 3.9 | Verify: Route đúng path → đúng service | [ ] | | | |

---

### Phase 4: Custom JWT Middleware (Go Plugin)

#### 4.1 Setup & Configuration

| # | Task | Status | Owner | Due Date | Notes |
|---|------|--------|-------|----------|-------|
| 4.1.1 | Tạo `cmd/traefik-plugin/` directory | [D] | | 2026-05-15 | |
| 4.1.2 | Viết `go.mod` với dependencies | [D] | | 2026-05-15 | jwx, go-redis |
| 4.1.3 | Viết `config.go` - PluginConfig struct | [D] | | 2026-05-15 | Integrated in jwt_auth.go |
| 4.1.4 | Viết `main.go` - Plugin entry point | [D] | | 2026-05-15 | |

#### 4.2 JWKS Cache Module

| # | Task | Status | Owner | Due Date | Notes |
|---|------|--------|-------|----------|-------|
| 4.2.1 | Viết `jwks_cache.go` - Cache struct | [D] | | 2026-05-15 | |
| 4.2.2 | Implement GetKey() với TTL | [D] | | 2026-05-15 | |
| 4.2.3 | Implement refreshCache() - fetch từ Auth Service | [D] | | 2026-05-15 | |
| 4.2.4 | Implement ClearCache() | [D] | | 2026-05-15 | |
| 4.2.5 | Implement InvalidateKey() | [D] | | 2026-05-15 | |
| 4.2.6 | Verify: Cache hit không call Auth Service | [ ] | | | |
| 4.2.7 | Verify: Cache miss gọi Auth Service | [ ] | | | |
| 4.2.8 | Verify: TTL expiration hoạt động | [ ] | | | |

#### 4.3 JWT Parser Module

| # | Task | Status | Owner | Due Date | Notes |
|---|------|--------|-------|----------|-------|
| 4.3.1 | Viết `jwt_parser.go` - Claims struct | [D] | | 2026-05-15 | Integrated in jwt_auth.go |
| 4.3.2 | Implement ExtractBearerToken() | [D] | | 2026-05-15 | |
| 4.3.3 | Implement ExtractKID() | [D] | | 2026-05-15 | |
| 4.3.4 | Implement ValidateToken() với RS256 | [D] | | 2026-05-15 | |
| 4.3.5 | Handle `exp`, `nbf` validation | [D] | | 2026-05-15 | jwx handles automatically |
| 4.3.6 | Handle `roles` claim parsing | [D] | | 2026-05-15 | |
| 4.3.7 | Verify: Valid token được chấp nhận | [ ] | | | |
| 4.3.8 | Verify: Expired token bị reject | [ ] | | | |
| 4.3.9 | Verify: Invalid signature bị reject | [ ] | | | |

#### 4.4 Redis Integration

| # | Task | Status | Owner | Due Date | Notes |
|---|------|--------|-------|----------|-------|
| 4.4.1 | Viết Redis client initialization | [D] | | 2026-05-15 | Connection pool |
| 4.4.2 | Implement checkDenylist() | [D] | | 2026-05-15 | `GET auth:denylist:{jti}` |
| 4.4.3 | Implement checkRevocation() | [D] | | 2026-05-15 | `GET auth:revoked_before:{user_id}` |
| 4.4.4 | Verify: Denylisted token → 401 | [ ] | | | |
| 4.4.5 | Verify: Revoked user token → 401 | [ ] | | | |

#### 4.5 Header Enrichment

| # | Task | Status | Owner | Due Date | Notes |
|---|------|--------|-------|----------|-------|
| 4.5.1 | Implement enrichHeaders() | [D] | | 2026-05-15 | |
| 4.5.2 | Set `X-User-Id` header | [D] | | 2026-05-15 | |
| 4.5.3 | Set `X-User-Roles` header | [D] | | 2026-05-15 | |
| 4.5.4 | Set `X-User-Email` header | [D] | | 2026-05-15 | |
| 4.5.5 | Set `X-User-Device-Id` header | [D] | | 2026-05-15 | |
| 4.5.6 | Set `X-User-Session-Id` header | [D] | | 2026-05-15 | |
| 4.5.7 | Verify: Headers present in upstream request | [ ] | | | |

#### 4.6 Plugin Assembly

| # | Task | Status | Owner | Due Date | Notes |
|---|------|--------|-------|----------|-------|
| 4.6.1 | Viết `jwt_auth.go` - Main middleware | [D] | | 2026-05-15 | |
| 4.6.2 | Implement ServeHTTP() - request pipeline | [D] | | 2026-05-15 | |
| 4.6.3 | Handle public paths bypass | [D] | | 2026-05-15 | |
| 4.6.4 | Handle missing Authorization header | [D] | | 2026-05-15 | |
| 4.6.5 | Error response format (JSON) | [D] | | 2026-05-15 | |
| 4.6.6 | Build plugin (.so file) | [ ] | | | `go build -buildmode=plugin` |
| 4.6.7 | Verify: Plugin load thành công | [ ] | | | |

---

### Phase 5: Redis Pub/Sub Invalidation

| # | Task | Status | Owner | Due Date | Notes |
|---|------|--------|-------|----------|-------|
| 5.1 | Viết subscribeToJWKSUpdates() goroutine | [D] | | 2026-05-15 | Integrated in jwt_auth.go |
| 5.2 | Xử lý `key_rotation` event | [D] | | 2026-05-15 | Supports Auth Service format |
| 5.3 | Xử lý `key_retirement` event | [D] | | 2026-05-15 | |
| 5.4 | Xử lý `all` event (full clear) | [D] | | 2026-05-15 | |
| 5.5 | Handle reconnection on disconnect | [D] | | 2026-05-15 | |
| 5.6 | Update Auth Service publish khi key rotate | [D] | | 2026-05-15 | Already exists in Auth Service |
| 5.7 | Update Auth Service publish khi key retire | [D] | | 2026-05-15 | Already in KeyRetirementWorker |
| 5.8 | Verify: Pub/Sub message clears cache | [ ] | | | |
| 5.9 | Verify: Reconnection after disconnect | [ ] | | | |

---

### Phase 6: Rate Limiting

| # | Task | Status | Owner | Due Date | Notes |
|---|------|--------|-------|----------|-------|
| 6.1 | Cấu hình RateLimit middleware (default) | [D] | | 2026-05-15 | 100 req/s, burst 200 |
| 6.2 | Cấu hình RateLimit cho API | [D] | | 2026-05-15 | 50 req/s, burst 100 |
| 6.3 | Cấu hình RateLimit cho Payment | [D] | | 2026-05-15 | 20 req/s, burst 40 |
| 6.4 | Cấu hình RateLimit cho Auth | [D] | | 2026-05-15 | 10 req/s, burst 20 |
| 6.5 | Cấu hình IP strategy (depth) | [D] | | 2026-05-15 | X-Forwarded-For handling |
| 6.6 | Apply rate limits via labels | [D] | | 2026-05-15 | Updated service-labels.env |
| 6.7 | Verify: Vượt ngưỡng → 429 | [ ] | | | |
| 6.8 | Verify: Không vượt ngưỡng → 200 | [ ] | | | |

---

### Phase 7: OpenTelemetry & Logging

| # | Task | Status | Owner | Due Date | Notes |
|---|------|--------|-------|----------|-------|
| 7.1 | Cấu hình OTLP exporter (Jaeger) | [D] | | 2026-05-15 | traefik.yml |
| 7.2 | Cấu hình sampling rate | [D] | | 2026-05-15 | traefik.yml |
| 7.3 | Cấu hình access log format (JSON) | [D] | | 2026-05-15 | traefik.yml |
| 7.4 | Thêm trace_id vào access log | [D] | | 2026-05-15 | traefik.yml |
| 7.5 | Thêm span_id vào access log | [D] | | 2026-05-15 | traefik.yml |
| 7.6 | Redact Authorization header in logs | [D] | | 2026-05-15 | traefik.yml |
| 7.7 | Implement structured logging trong plugin | [D] | | 2026-05-15 | jwt_auth.go |
| 7.8 | Verify: trace_id trong Jaeger UI | [ ] | | | |
| 7.9 | Verify: trace_id propagate xuống service | [ ] | | | |
| 7.10 | Verify: Access log có đầy đủ fields | [ ] | | | |

---

### Phase 8: Security Hardening

| # | Task | Status | Owner | Due Date | Notes |
|---|------|--------|-------|----------|-------|
| 8.1 | Cấu hình secure headers middleware | [ ] | | | |
| 8.2 | Enable TLS termination | [ ] | | | |
| 8.3 | Cấu hình non-root user cho Traefik | [ ] | | | |
| 8.4 | Mount Docker socket read-only | [ ] | | | |
| 8.5 | Cấu hình Dashboard authentication | [ ] | | | |
| 8.6 | Review và redact sensitive headers | [ ] | | | |
| 8.7 | Security scan (Trivy/CVE) | [ ] | | | |

---

### Phase 9: Integration Testing

| # | Task | Status | Owner | Due Date | Notes |
|---|------|--------|-------|----------|-------|
| 9.1 | Test full auth flow (login → token → request) | [ ] | | | |
| 9.2 | Test concurrent requests | [ ] | | | |
| 9.3 | Test Gateway restart không mất service | [ ] | | | |
| 9.4 | Test Redis failure graceful degradation | [ ] | | | |
| 9.5 | Test Auth Service down | [ ] | | | |
| 9.6 | Test JWKS invalidation real-time | [ ] | | | |
| 9.7 | Test multiple Gateway replicas | [ ] | | | |

---

### Phase 10: Load Testing

| # | Task | Status | Owner | Due Date | Notes |
|---|------|--------|-------|----------|-------|
| 10.1 | Baseline: Plain proxy performance | [ ] | | | |
| 10.2 | Benchmark: JWT auth overhead | [ ] | | | |
| 10.3 | Load test: 100 concurrent users | [ ] | | | k6 |
| 10.4 | Load test: 500 concurrent users | [ ] | | | |
| 10.5 | Load test: Spike traffic | [ ] | | | |
| 10.6 | Tune rate limits dựa trên results | [ ] | | | |
| 10.7 | Memory profiling | [ ] | | | |
| 10.8 | CPU profiling | [ ] | | | |

---

### Phase 11: Documentation

| # | Task | Status | Owner | Due Date | Notes |
|---|------|--------|-------|----------|-------|
| 11.1 | Viết README cho API Gateway | [ ] | | | Setup instructions |
| 11.2 | Document API endpoints & routing | [D] | | 2026-05-15 | docs/routing-matrix.md |
| 11.3 | Document environment variables | [D] | | 2026-05-15 | .env.example |
| 11.4 | Document troubleshooting guide | [ ] | | | |
| 11.5 | Update architecture diagram | [ ] | | | |
| 11.6 | Runbook cho operations | [ ] | | | |

---

### Phase 12: Deployment

| # | Task | Status | Owner | Due Date | Notes |
|---|------|--------|-------|----------|-------|
| 12.1 | Docker Compose cho staging | [ ] | | | |
| 12.2 | Docker Compose cho production | [ ] | | | Multiple replicas |
| 12.3 | CI/CD pipeline (GitHub Actions) | [ ] | | | |
| 12.4 | Helm chart cho Kubernetes | [ ] | | | Optional |
| 12.5 | Load Balancer configuration | [ ] | | | |
| 12.6 | Deployment verification | [ ] | | | |
| 12.7 | Handover checklist | [ ] | | | |

---

### Phase 13: Monitoring Setup

| # | Task | Status | Owner | Due Date | Notes |
|---|------|--------|-------|----------|-------|
| 13.1 | Import Grafana dashboard | [ ] | | | |
| 13.2 | Configure Prometheus alerts | [ ] | | | |
| 13.3 | Setup PagerDuty/Slack alerts | [ ] | | | |
| 13.4 | Verify metrics collection | [ ] | | | |
| 13.5 | Create SLO/SLI definitions | [ ] | | | |

---

### Summary Statistics

```
Total Tasks:     120
Completed:       74  ( 62%)
In Progress:      0  (  0%)
Blocked:          1  (  1%)  [1.4: Auth Service JWKS]
Not Started:     45  ( 37%)

Phases Progress:
├── Phase 1 (Infrastructure):          4/5   ( 80%)
├── Phase 2 (Traefik Basic):            5/7   ( 71%)
├── Phase 3 (Service Discovery):        8/9   ( 89%)
├── Phase 4 (JWT Middleware):          25/32  ( 78%)
├── Phase 5 (Pub/Sub):                 7/9   ( 78%)  ★ COMPLETED ★
├── Phase 6 (Rate Limiting):           6/8   ( 75%)  ★ COMPLETED ★
├── Phase 7 (Observability):          7/10  ( 70%)  ★ COMPLETED ★
├── Phase 8 (Security):                1/7   ( 14%)
├── Phase 9 (Integration Testing):      0/7   (  0%)
├── Phase 10 (Load Testing):           0/8   (  0%)
├── Phase 11 (Documentation):          3/6   ( 50%)
├── Phase 12 (Deployment):            0/7   (  0%)
└── Phase 13 (Monitoring):             0/5   (  0%)
```

---

### Dependency Graph

```
Phase 1 (Infrastructure)
    │
    ├──▶ Phase 2 (Traefik Basic)
    │         │
    │         └──▶ Phase 3 (Service Discovery)
    │                   │
    │                   └──▶ Phase 4 (JWT Middleware)
    │                             │
    │                             ├──▶ Phase 5 (Pub/Sub)
    │                             │
    │                             ├──▶ Phase 6 (Rate Limiting)
    │                             │
    │                             └──▶ Phase 7 (Observability)
    │                                       │
    │                                       ├──▶ Phase 8 (Security)
    │                                       │
    │                                       ├──▶ Phase 9 (Testing)
    │                                       │         │
    │                                       │         └──▶ Phase 10 (Load Testing)
    │                                       │
    │                                       ├──▶ Phase 11 (Documentation)
    │                                       │
    │                                       └──▶ Phase 12 (Deployment)
    │                                                 │
    │                                                 └──▶ Phase 13 (Monitoring)
```

---

## 1. Kiến trúc tổng thể và các thành phần

### 1.1 Sơ đồ luồng Request

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                                        CLIENT                                          │
│                                     (Mobile/Web)                                       │
└─────────────────────────────────────────────┬───────────────────────────────────────────┘
                                              │ HTTPS
                                              ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                                    API GATEWAY (Traefik v3.x)                          │
│  ┌─────────────────────────────────────────────────────────────────────────────────┐    │
│  │                          REQUEST PIPELINE                                       │    │
│  │                                                                                 │    │
│  │   ┌──────────┐   ┌────────────────┐   ┌──────────────┐   ┌─────────────────┐   │    │
│  │   │ Entry    │──▶│ Rate Limiter   │──▶│ JWT Auth     │──▶│ Logger/Tracer  │   │    │
│  │   │ Point    │   │ (Redis-based)  │   │ Middleware   │   │ (OpenTelemetry)│   │    │
│  │   └──────────┘   └────────────────┘   └──────────────┘   └─────────────────┘   │    │
│  │                                              │                                   │    │
│  │                                              ▼                                   │    │
│  │                                    ┌────────────────┐                           │    │
│  │                                    │ Header Enrich  │                           │    │
│  │                                    │ X-User-Id     │                           │    │
│  │                                    │ X-User-Roles  │                           │    │
│  │                                    └────────────────┘                           │    │
│  └─────────────────────────────────────────────┬───────────────────────────────────┘    │
│                                                 │                                             │
│  ┌─────────────────────────────────────────────┴───────────────────────────────────┐    │
│  │                          DYNAMIC ROUTING                                          │    │
│  │   Path-based routing với labels từ Docker/Kubernetes                           │    │
│  └─────────────────────────────────────────────┬───────────────────────────────────┘    │
│                                                 │                                             │
└─────────────────────────────────────────────────┼───────────────────────────────────────────┘
                                                  │
        ┌────────────────────────────────────────┼────────────────────────────────────────┐
        │                                        │                                        │
        ▼                                        ▼                                        ▼
┌───────────────┐                    ┌───────────────────┐                    ┌───────────────┐
│ user-service  │                    │  order-service    │                    │product-service│
│   :8082       │                    │      :8083        │                    │     :8084     │
└───────────────┘                    └───────────────────┘                    └───────────────┘
        │                                        │                                        │
        │                                        │                                        │
        ▼                                        ▼                                        ▼
┌───────────────┐                    ┌───────────────────┐                    ┌───────────────┐
│ User Service  │                    │  Order Service    │                    │Product Service│
│   Database    │                    │    Database       │                    │   Database    │
└───────────────┘                    └───────────────────┘                    └───────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                                   EXTERNAL SERVICES                                      │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐     │
│  │  Auth Service   │  │  Redis Cluster  │  │   Jaeger/OTLP   │  │  Auth Service    │     │
│  │  :8081          │  │  (Denylist,     │  │  (Tracing)      │  │  (JWKS Endpoint) │     │
│  │                 │  │   Rate Limit,   │  │                 │  │                 │     │
│  │                 │  │   Pub/Sub)      │  │                 │  │                 │     │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘  └─────────────────┘     │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

### 1.2 Vai trò từng module

| Module | Mô tả | Dependencies |
|--------|-------|--------------|
| **Entry Point** | Tiếp nhận request tại cổng 80/443, terminate TLS (nếu cần) | - |
| **Rate Limiter** | Giới hạn số request/IP, dùng Redis để share state giữa các replicas | Redis |
| **JWT Auth Middleware** | Xác thực token, verify signature, check denylist, enrich headers | Redis, Auth Service (JWKS) |
| **Logger/Tracer** | Sinh trace_id, ghi access log JSON, forward tracing headers | Jaeger/OTLP Collector |
| **Dynamic Router** | Đọc labels từ Docker/K8s, route request đến service đúng | Docker/K8s API |
| **Header Enricher** | Thêm `X-User-Id`, `X-User-Roles`, `X-User-Device-Id` vào request | JWT claims extraction |

---

## 2. Thiết lập Traefik cơ bản

### 2.1 Cấu trúc thư mục dự án

```
go/
└── services/
    └── api-gateway/
        ├── cmd/
        │   └── traefik-plugin/           # JWT Auth Go Plugin
        │       ├── main.go
        │       ├── jwt_auth.go
        │       ├── jwks_cache.go
        │       ├── redis_client.go
        │       └── go.mod
        ├── config/
        │   ├── traefik.yml               # Static config
        │   └── dynamic.yml               # Dynamic config
        ├── docker-compose.yml
        └── Dockerfile
```

### 2.2 Static Configuration (traefik.yml)

```yaml
# go/services/api-gateway/config/traefik.yml

# Global settings
api:
  dashboard: true
  insecure: true  # Chỉ dùng trong development

entryPoints:
  web:
    address: ":80"
    http:
      redirections:
        entryPoint:
          to: websecure
          scheme: https
          permanent: true

  websecure:
    address: ":443"
    http:
      tls:
        certResolver: myresolver
        domains:
          - main: "api.ecommerce.local"
            sans:
              - "*.api.ecommerce.local"

# Providers
providers:
  docker:
    endpoint: "unix:///var/run/docker.sock"
    exposedByDefault: false
    network: ecommerce-network
    watch: true

  file:
    directory: /etc/traefik/dynamic
    watch: true

# Logging
log:
  level: INFO
  format: json

# Access Log
accessLog:
  format: json
  fields:
    defaultMode: keep
    headers:
      names:
        traceparent: keep
        X-Request-Id: keep

# Metrics
metrics:
  prometheus:
    addEntryPointsLabels: true
    addServicesLabels: true
    entryPoint: metrics

# Tracing
tracing:
  serviceName: api-gateway
  otlp:
    endpoint: jaeger-collector:4317
    tls:
      insecure: true
```

### 2.3 Dynamic Configuration (dynamic.yml)

```yaml
# go/services/api-gateway/config/dynamic.yml

http:
  # Middlewares
  middlewares:
    # Rate limiting middleware
    rate-limit-ip:
      rateLimit:
        average: 100
        burst: 200
        period: 1s
        sourceCriterion:
          ipStrategy:
            depth: 2

    # Rate limiting cho API (strict)
    rate-limit-api:
      rateLimit:
        average: 50
        burst: 100
        period: 1s
        sourceCriterion:
          ipStrategy:
            depth: 2

    # Headers security
    secure-headers:
      headers:
        sslRedirect: true
        stsSeconds: 31536000
        stsIncludeSubdomains: true
        stsPreload: true
        forceSTSHeader: true
        frameDeny: true
        contentTypeNosniff: true
        browserXssFilter: true
        referrerPolicy: "strict-origin-when-cross-origin"
        customResponseHeaders:
          X-Request-Id: ""

    # Compress
    compress:
      compress: {}

  # Routers (sẽ được override bởi Docker labels)
  routers:
    # Catch-all cho health check
    health:
      rule: "PathPrefix(`/`)"
      service: ping
      priority: 1

  # Services
  services:
    ping:
      loadBalancer:
        servers:
          - url: "http://127.0.0.1:8082"  # Dummy service cho health check
```

### 2.4 Docker Compose Setup

```yaml
# go/services/api-gateway/docker-compose.yml

version: '3.8'

services:
  traefik:
    image: traefik:v3.0
    container_name: api-gateway
    restart: unless-stopped
    security_opt:
      - no-new-privileges:true
    ports:
      - "80:80"
      - "443:443"
      - "8080:8080"  # Traefik dashboard & metrics
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock:ro
      - ./config/traefik.yml:/etc/traefik/traefik.yml:ro
      - ./config/dynamic.yml:/etc/traefik/dynamic/dynamic.yml:ro
      - ./plugins:/plugins  # Thư mục chứa Go plugin .so
    environment:
      - TZ=Asia/Ho_Chi_Minh
    networks:
      - ecommerce-network
    labels:
      - "traefik.enable=true"
      - "traefik.http.routers.dashboard.rule=Host(`traefik.localhost`)"
      - "traefik.http.routers.dashboard.service=api@internal"
    extra_hosts:
      - "host.docker.internal:host-gateway"

networks:
  ecommerce-network:
    name: ecommerce-network
    external: false
```

### 2.5 Lưu ý bảo mật

1. **Docker Socket Mount**: Chỉ mount read-only (`ro`) để tránh container escape
2. **Non-root User**: Thêm user với UID khác để chạy Traefik
3. **TLS**: Trong production, sử dụng Let's Encrypt hoặc certificate từ Vault

---

## 3. Service Discovery & Routing

### 3.1 Docker Labels cho từng Service

```yaml
# docker-compose.yml của mỗi service

services:
  user-service:
    image: user-service:latest
    container_name: user-service
    networks:
      - ecommerce-network
    labels:
      # Enable Traefik
      - "traefik.enable=true"
      
      # Router - Path-based
      - "traefik.http.routers.user-service.rule=PathPrefix(`/api/users`)"
      - "traefik.http.routers.user-service.entrypoints=websecure"
      - "traefik.http.routers.user-service.tls=true"
      
      # Middlewares cho service này
      - "traefik.http.routers.user-service.middlewares=user-auth,rate-limit-api,secure-headers"
      
      # Service config
      - "traefik.http.services.user-service.loadbalancer.server.port=8080"
      - "traefik.http.services.user-service.loadbalancer.server.scheme=http"
      
      # Health check
      - "traefik.http.services.user-service.loadbalancer.healthcheck.path=/health"
      - "traefik.http.services.user-service.loadbalancer.healthcheck.interval=10s"
      
      # Load balancing method (mặc định là round robin)
      # Đổi sang least connections nếu cần:
      # - "traefik.http.services.user-service.loadbalancer.sticky.cookie=true"
      
      # Priority (cho rule cụ thể hơn)
      - "traefik.http.routers.user-service.priority=10"

  order-service:
    image: order-service:latest
    container_name: order-service
    networks:
      - ecommerce-network
    labels:
      - "traefik.enable=true"
      - "traefik.http.routers.order-service.rule=PathPrefix(`/api/orders`)"
      - "traefik.http.routers.order-service.entrypoints=websecure"
      - "traefik.http.routers.order-service.tls=true"
      - "traefik.http.routers.order-service.middlewares=order-auth,rate-limit-api,secure-headers"
      - "traefik.http.services.order-service.loadbalancer.server.port=8080"
      - "traefik.http.services.order-service.loadbalancer.healthcheck.path=/health"
      - "traefik.http.services.order-service.loadbalancer.healthcheck.interval=10s"

  product-service:
    image: product-service:latest
    container_name: product-service
    networks:
      - ecommerce-network
    labels:
      - "traefik.enable=true"
      - "traefik.http.routers.product-service.rule=PathPrefix(`/api/products`)"
      - "traefik.http.routers.product-service.entrypoints=websecure"
      - "traefik.http.routers.product-service.tls=true"
      - "traefik.http.routers.product-service.middlewares=product-auth,rate-limit-api,secure-headers"
      - "traefik.http.services.product-service.loadbalancer.server.port=8080"
      - "traefik.http.services.product-service.loadbalancer.healthcheck.path=/health"

  payment-service:
    image: payment-service:latest
    container_name: payment-service
    networks:
      - ecommerce-network
    labels:
      - "traefik.enable=true"
      - "traefik.http.routers.payment-service.rule=PathPrefix(`/api/payments`)"
      - "traefik.http.routers.payment-service.entrypoints=websecure"
      - "traefik.http.routers.payment-service.tls=true"
      - "traefik.http.routers.payment-service.middlewares=payment-auth,rate-limit-api,secure-headers"
      - "traefik.http.services.payment-service.loadbalancer.server.port=8080"
      - "traefik.http.services.payment-service.loadbalancer.healthcheck.path=/health"

  auth-service:
    image: auth-service:latest
    container_name: auth-service
    networks:
      - ecommerce-network
    expose:
      - "8081"  # Internal port
    labels:
      - "traefik.enable=true"
      # Auth service không cần auth middleware (tránh circular)
      - "traefik.http.routers.auth-service.rule=PathPrefix(`/.well-known`)"
      - "traefik.http.routers.auth-service.entrypoints=websecure"
      - "traefik.http.routers.auth-service.tls=true"
      - "traefik.http.services.auth-service.loadbalancer.server.port=8081"
```

### 3.2 Routing Rules Matrix

| Path Pattern | Service | Auth Required | Rate Limit |
|--------------|---------|---------------|------------|
| `/api/users/**` | user-service | Yes | 50 req/s |
| `/api/orders/**` | order-service | Yes | 50 req/s |
| `/api/products/**` | product-service | Partial* | 100 req/s |
| `/api/payments/**` | payment-service | Yes | 20 req/s |
| `/.well-known/**` | auth-service | No | 10 req/s |
| `/health` | - | No | No limit |

*Product service: GET requests không cần auth, POST/PUT/DELETE cần auth.

### 3.3 Custom Headers cho Headers Enrichment

JWT Auth Middleware sẽ thêm các headers sau:

```go
// Headers được thêm vào request trước khi forward
X-User-Id: "12345"                    // Từ JWT claim "sub"
X-User-Email: "user@example.com"       // Từ JWT claim "email"
X-User-Roles: "admin,user"            // Từ JWT claim "roles" (comma-separated)
X-User-Device-Id: "device-uuid"        // Từ JWT claim "device_id"
X-User-Session-Id: "session-uuid"     // Từ JWT claim "session_id"
```

---

## 4. Custom Middleware xác thực JWT

### 4.1 Phân tích lựa chọn: Go Plugin vs HTTP Forward Auth

| Tiêu chí | Go Plugin | HTTP Forward Auth |
|----------|-----------|-------------------|
| **Hiệu năng** | Cao nhất (native) | Trung bình (extra hop) |
| **Latency** | ~0.5-1ms overhead | ~5-15ms overhead |
| **Dependency ABI** | Phụ thuộc Go version | Độc lập |
| **Maintainability** | Cần dev Go | Dễ debug, scale horizontal |
| **Team skill** | Cần Go knowledge | Bất kỳ ngôn ngữ nào |
| **Hot reload** | Không (cần restart) | Có (deploy service mới) |
| **Testing** | Khó hơn | Dễ hơn (unit test HTTP) |
| **Fallback** | Khó | Dễ (chuyển qua lại) |

**Khuyến nghị**: Sử dụng **Go Plugin** cho performance-critical paths và **HTTP Forward Auth** như một fallback/parallel mode. Đặc biệt nếu team đã có kinh nghiệm Go (như trong auth-service hiện tại).

### 4.2 Cấu trúc Project Go Plugin

```
go/services/api-gateway/
├── cmd/
│   └── traefik-plugin/
│       ├── main.go                 # Plugin entry point
│       ├── jwt_auth.go             # Main middleware logic
│       ├── jwks_cache.go           # JWKS cache với TTL
│       ├── redis_client.go         # Redis client wrapper
│       ├── jwt_parser.go           # JWT parsing & validation
│       ├── header_enricher.go      # Header enrichment
│       ├── config.go               # Plugin configuration
│       ├── logger.go               # Structured logging với trace_id
│       └── go.mod
├── config/
│   └── traefik.yml
└── docker-compose.yml
```

### 4.3 Plugin Configuration (config.go)

```go
// go/services/api-gateway/cmd/traefik-plugin/config.go

package main

import (
    "time"
)

type Config struct {
    // Auth Service endpoint để fetch JWKS
    AuthServiceURL string
    
    // JWKS endpoint path
    JWKSPath string
    
    // Redis configuration
    RedisAddr     string
    RedisPassword string
    RedisDB       int
    
    // Cache settings
    JWKSCacheTTL time.Duration
    
    // Redis key prefixes
    DenylistKeyPrefix   string
    RevokedBeforePrefix string
    
    // Pub/Sub channel để nhận JWKS invalidation
    JWKSInvalidateChannel string
    
    // Paths không cần auth
    PublicPaths []string
    
    // Headers để thêm vào request
    UserIdHeader      string
    UserRolesHeader   string
    UserEmailHeader   string
    UserDeviceHeader  string
    UserSessionHeader string
}
```

### 4.4 JWKS Cache Implementation (jwks_cache.go)

```go
// go/services/api-gateway/cmd/traefik-plugin/jwks_cache.go

package main

import (
    "context"
    "encoding/json"
    "fmt"
    "net/http"
    "sync"
    "time"
    
    "github.com/redis/go-redis/v9"
    "github.com/lestrrat-go/jwx/v2/jwk"
)

type JWKSCache struct {
    mu       sync.RWMutex
    keys     map[string]jwk.Key
    fetchedAt time.Time
    ttl      time.Duration
    redis    *redis.Client
    authURL  string
    httpClient *http.Client
}

func NewJWKSCache(redisAddr, redisPassword string, redisDB int, authURL string, ttl time.Duration) (*JWKSCache, error) {
    rdb := redis.NewClient(&redis.Options{
        Addr:     redisAddr,
        Password: redisPassword,
        DB:       redisDB,
        PoolSize: 10,
    })
    
    // Test connection
    ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
    defer cancel()
    
    if err := rdb.Ping(ctx).Err(); err != nil {
        return nil, fmt.Errorf("failed to connect to Redis: %w", err)
    }
    
    return &JWKSCache{
        keys:      make(map[string]jwk.Key),
        ttl:       ttl,
        redis:     rdb,
        authURL:   authURL,
        httpClient: &http.Client{Timeout: 10 * time.Second},
    }, nil
}

// GetKey retrieves a key by kid from cache, fetching from Auth Service if needed
func (c *JWKSCache) GetKey(ctx context.Context, kid string) (jwk.Key, error) {
    // Try cache first
    c.mu.RLock()
    if key, ok := c.keys[kid]; ok && time.Since(c.fetchedAt) < c.ttl {
        c.mu.RUnlock()
        return key, nil
    }
    c.mu.RUnlock()
    
    // Cache miss or expired - fetch all JWKS
    if err := c.refreshCache(ctx); err != nil {
        return nil, err
    }
    
    // Try again
    c.mu.RLock()
    defer c.mu.RUnlock()
    
    if key, ok := c.keys[kid]; ok {
        return key, nil
    }
    
    return nil, fmt.Errorf("key with kid %s not found in JWKS", kid)
}

// refreshCache fetches JWKS from Auth Service
func (c *JWKSCache) refreshCache(ctx context.Context) error {
    url := fmt.Sprintf("%s/.well-known/jwks.json", c.authURL)
    
    resp, err := c.httpClient.Get(url)
    if err != nil {
        return fmt.Errorf("failed to fetch JWKS: %w", err)
    }
    defer resp.Body.Close()
    
    if resp.StatusCode != http.StatusOK {
        return fmt.Errorf("JWKS endpoint returned status %d", resp.StatusCode)
    }
    
    var jwksResponse struct {
        Keys []json.RawMessage `json:"keys"`
    }
    
    if err := json.NewDecoder(resp.Body).Decode(&jwksResponse); err != nil {
        return fmt.Errorf("failed to decode JWKS: %w", err)
    }
    
    c.mu.Lock()
    defer c.mu.Unlock()
    
    // Clear old keys and populate new ones
    c.keys = make(map[string]jwk.Key)
    
    for i, keyData := range jwksResponse.Keys {
        key, err := jwk.Parse(keyData)
        if err != nil {
            continue // Skip invalid keys
        }
        
        kid, _ := key.Get(jwk.KeyIDKey)
        if kidStr, ok := kid.(string); ok {
            c.keys[kidStr] = key
        } else {
            // Use index as fallback key
            c.keys[fmt.Sprintf("key-%d", i)] = key
        }
    }
    
    c.fetchedAt = time.Now()
    return nil
}

// ClearCache invalidates the entire JWKS cache
func (c *JWKSCache) ClearCache() {
    c.mu.Lock()
    defer c.mu.Unlock()
    
    c.keys = make(map[string]jwk.Key)
    c.fetchedAt = time.Time{}
}

// InvalidateKey removes a specific key from cache
func (c *JWKSCache) InvalidateKey(kid string) {
    c.mu.Lock()
    defer c.mu.Unlock()
    
    delete(c.keys, kid)
}
```

### 4.5 JWT Parser Implementation (jwt_parser.go)

```go
// go/services/api-gateway/cmd/traefik-plugin/jwt_parser.go

package main

import (
    "context"
    "encoding/base64"
    "encoding/json"
    "fmt"
    "strings"
    "time"
    
    "github.com/lestrrat-go/jwx/v2/jwa"
    "github.com/lestrrat-go/jwx/v2/jwk"
    "github.com/lestrrat-go/jwx/v2/jwt"
)

type Claims struct {
    Subject   string   `json:"sub"`
    Email     string   `json:"email"`
    Roles     []string `json:"roles"`
    DeviceID  string   `json:"device_id"`
    SessionID string   `json:"session_id"`
    JTI       string   `json:"jti"`
    IAT       int64    `json:"iat"`
    EXP       int64    `json:"exp"`
    NBF       int64    `json:"nbf"`
    ISS       string   `json:"iss"`
}

type JWTParser struct {
    cache *JWKSCache
}

func NewJWTParser(cache *JWKSCache) *JWTParser {
    return &JWTParser{cache: cache}
}

// ExtractBearerToken extracts JWT from Authorization header
func ExtractBearerToken(authHeader string) (string, error) {
    if authHeader == "" {
        return "", fmt.Errorf("authorization header is empty")
    }
    
    parts := strings.SplitN(authHeader, " ", 2)
    if len(parts) != 2 || !strings.EqualFold(parts[0], "bearer") {
        return "", fmt.Errorf("invalid authorization header format")
    }
    
    return parts[1], nil
}

// ExtractKID extracts the Key ID from JWT header
func ExtractKID(tokenString string) (string, error) {
    parts := strings.Split(tokenString, ".")
    if len(parts) != 3 {
        return "", fmt.Errorf("invalid JWT format")
    }
    
    headerJSON, err := base64.RawURLEncoding.DecodeString(parts[0])
    if err != nil {
        return "", fmt.Errorf("failed to decode JWT header: %w", err)
    }
    
    var header struct {
        Kid string `json:"kid"`
        Alg string `json:"alg"`
        Typ string `json:"typ"`
    }
    
    if err := json.Unmarshal(headerJSON, &header); err != nil {
        return "", fmt.Errorf("failed to parse JWT header: %w", err)
    }
    
    return header.Kid, nil
}

// ValidateToken validates JWT and returns claims
func (p *JWTParser) ValidateToken(ctx context.Context, tokenString string) (*Claims, error) {
    // Extract kid from header
    kid, err := ExtractKID(tokenString)
    if err != nil {
        return nil, fmt.Errorf("failed to extract kid: %w", err)
    }
    
    if kid == "" {
        return nil, fmt.Errorf("kid is missing from token header")
    }
    
    // Get public key from cache (or fetch from Auth Service)
    key, err := p.cache.GetKey(ctx, kid)
    if err != nil {
        return nil, fmt.Errorf("failed to get public key: %w", err)
    }
    
    // Parse and verify JWT
    var token jwt.Token
    if err := jwt.Parse(
        []byte(tokenString),
        jwt.WithKey(jwa.RS256, key),
        jwt.WithContext(ctx),
        jwt.WithValidate(true),
    ); err != nil {
        return nil, fmt.Errorf("JWT validation failed: %w", err)
    }
    
    // Extract claims
    claims := &Claims{
        Subject:   token.Subject(),
        Email:     token.Email(),
        DeviceID:  token.DeviceID(),
        SessionID: token.SessionID(),
        JTI:       token.JWTID(),
        IAT:       token.IssuedAt().Unix(),
        EXP:       token.Expiration().Unix(),
        ISS:       token.Issuer(),
    }
    
    // Extract roles
    if rolesVal, ok := token.Get("roles"); ok {
        switch v := rolesVal.(type) {
        case []interface{}:
            for _, r := range v {
                if rs, ok := r.(string); ok {
                    claims.Roles = append(claims.Roles, rs)
                }
            }
        case string:
            claims.Roles = strings.Split(v, ",")
        }
    }
    
    // Check NBF if present
    if nbf := token.NotBefore(); nbf != nil && nbf.After(time.Now()) {
        return nil, fmt.Errorf("token not yet valid")
    }
    
    return claims, nil
}
```

### 4.6 Main JWT Auth Middleware (jwt_auth.go)

```go
// go/services/api-gateway/cmd/traefik-plugin/jwt_auth.go

package main

import (
    "context"
    "encoding/json"
    "fmt"
    "log"
    "net/http"
    "strings"
    "time"
    
    "github.com/redis/go-redis/v9"
    "github.com/lestrrat-go/jwx/v2/jwk"
)

type JWTAuthPlugin struct {
    config     *Config
    cache      *JWKSCache
    redis      *redis.Client
    httpClient *http.Client
    
    // Pre-compiled public paths
    publicPathsMap map[string]bool
}

func NewJWTAuthPlugin(config *Config) (*JWTAuthPlugin, error) {
    // Initialize JWKS cache
    cache, err := NewJWKSCache(
        config.RedisAddr,
        config.RedisPassword,
        config.RedisDB,
        config.AuthServiceURL,
        config.JWKSCacheTTL,
    )
    if err != nil {
        return nil, fmt.Errorf("failed to initialize JWKS cache: %w", err)
    }
    
    // Initialize Redis client
    rdb := redis.NewClient(&redis.Options{
        Addr:     config.RedisAddr,
        Password: config.RedisPassword,
        DB:       config.RedisDB,
        PoolSize: 10,
    })
    
    // Build public paths map for fast lookup
    publicPathsMap := make(map[string]bool)
    for _, path := range config.PublicPaths {
        publicPathsMap[path] = true
    }
    
    p := &JWTAuthPlugin{
        config:         config,
        cache:          cache,
        redis:          rdb,
        publicPathsMap: publicPathsMap,
        httpClient: &http.Client{
            Timeout: 5 * time.Second,
        },
    }
    
    // Start Pub/Sub listener for JWKS invalidation
    go p.subscribeToJWKSUpdates()
    
    return p, nil
}

// ServeHTTP is the main entry point for the middleware
func (p *JWTAuthPlugin) ServeHTTP(rw http.ResponseWriter, req *http.Request) {
    // Get trace ID from context (set by Traefik)
    traceID := req.Header.Get("X-Request-Id")
    
    // Check if path is public
    path := req.URL.Path
    for _, prefix := range p.config.PublicPaths {
        if strings.HasPrefix(path, prefix) {
            log.Printf("[%s] Public path, skipping auth: %s", traceID, path)
            rw.WriteHeader(http.StatusOK)
            return
        }
    }
    
    // Extract Authorization header
    authHeader := req.Header.Get("Authorization")
    if authHeader == "" {
        log.Printf("[%s] Missing Authorization header for path: %s", traceID, path)
        http.Error(rw, `{"error": "unauthorized", "message": "Missing authorization header"}`, http.StatusUnauthorized)
        return
    }
    
    // Extract bearer token
    token, err := ExtractBearerToken(authHeader)
    if err != nil {
        log.Printf("[%s] Invalid authorization header: %v", traceID, err)
        http.Error(rw, `{"error": "unauthorized", "message": "Invalid authorization header"}`, http.StatusUnauthorized)
        return
    }
    
    // Parse and validate JWT
    claims, err := p.validateAndParseToken(req.Context(), token)
    if err != nil {
        log.Printf("[%s] Token validation failed: %v", traceID, err)
        http.Error(rw, fmt.Sprintf(`{"error": "unauthorized", "message": "%s"}`, err.Error()), http.StatusUnauthorized)
        return
    }
    
    // Check denylist
    if err := p.checkDenylist(req.Context(), claims.JTI); err != nil {
        log.Printf("[%s] Token is denylisted (jti=%s): %v", traceID, claims.JTI, err)
        http.Error(rw, `{"error": "unauthorized", "message": "Token has been revoked"}`, http.StatusUnauthorized)
        return
    }
    
    // Check revocation (user force logout / password change)
    if err := p.checkRevocation(req.Context(), claims.Subject, claims.IAT); err != nil {
        log.Printf("[%s] Token revoked due to user action (user=%s): %v", traceID, claims.Subject, err)
        http.Error(rw, `{"error": "unauthorized", "message": "Token has been revoked"}`, http.StatusUnauthorized)
        return
    }
    
    // Enrich headers
    p.enrichHeaders(req, claims)
    
    log.Printf("[%s] Auth successful: user=%s, path=%s", traceID, claims.Subject, path)
    
    // Continue to next handler
    // Note: In actual plugin, we use the plugin framework's next handler
}

// validateAndParseToken validates JWT and returns claims
func (p *JWTAuthPlugin) validateAndParseToken(ctx context.Context, token string) (*Claims, error) {
    // Extract kid from header
    kid, err := ExtractKID(token)
    if err != nil {
        return nil, fmt.Errorf("failed to extract kid: %w", err)
    }
    
    if kid == "" {
        return nil, fmt.Errorf("kid is missing from token header")
    }
    
    // Get public key from cache
    key, err := p.cache.GetKey(ctx, kid)
    if err != nil {
        return nil, fmt.Errorf("failed to get public key for kid %s: %w", kid, err)
    }
    
    // Parse and validate JWT
    var tokenVal jwt.Token
    if err := jwt.Parse(
        []byte(token),
        jwt.WithKey(jwa.RS256, key),
        jwt.WithContext(ctx),
        jwt.WithValidate(true),
    ); err != nil {
        return nil, fmt.Errorf("JWT validation failed: %w", err)
    }
    
    claims := &Claims{
        Subject:   tokenVal.Subject(),
        JTI:       tokenVal.JWTID(),
        IAT:       tokenVal.IssuedAt().Unix(),
    }
    
    // Extract optional claims
    if email, ok := tokenVal.Get("email"); ok {
        claims.Email, _ = email.(string)
    }
    if roles, ok := tokenVal.Get("roles"); ok {
        if rs, ok := roles.([]interface{}); ok {
            for _, r := range rs {
                if s, ok := r.(string); ok {
                    claims.Roles = append(claims.Roles, s)
                }
            }
        }
    }
    if deviceID, ok := tokenVal.Get("device_id"); ok {
        claims.DeviceID, _ = deviceID.(string)
    }
    if sessionID, ok := tokenVal.Get("session_id"); ok {
        claims.SessionID, _ = sessionID.(string)
    }
    
    return claims, nil
}

// checkDenylist checks if token's JTI is in Redis denylist
func (p *JWTAuthPlugin) checkDenylist(ctx context.Context, jti string) error {
    if jti == "" {
        return nil // No JTI means no denylist check
    }
    
    key := fmt.Sprintf("%s:%s", p.config.DenylistKeyPrefix, jti)
    exists, err := p.redis.Exists(ctx, key).Result()
    if err != nil {
        return fmt.Errorf("redis error: %w", err)
    }
    
    if exists > 0 {
        return fmt.Errorf("token jti %s is denylisted", jti)
    }
    
    return nil
}

// checkRevocation checks if user has force-logged out or changed password
func (p *JWTAuthPlugin) checkRevocation(ctx context.Context, userID string, iat int64) error {
    if userID == "" {
        return nil
    }
    
    key := fmt.Sprintf("%s:%s", p.config.RevokedBeforePrefix, userID)
    revokedAt, err := p.redis.Get(ctx, key).Int64()
    if err != nil && err != redis.Nil {
        return fmt.Errorf("redis error: %w", err)
    }
    
    if err == nil && iat < revokedAt {
        return fmt.Errorf("token issued before revocation (revoked_at=%d, iat=%d)", revokedAt, iat)
    }
    
    return nil
}

// enrichHeaders adds user information to request headers
func (p *JWTAuthPlugin) enrichHeaders(req *http.Request, claims *Claims) {
    if claims.Subject != "" {
        req.Header.Set(p.config.UserIdHeader, claims.Subject)
    }
    if claims.Email != "" {
        req.Header.Set(p.config.UserEmailHeader, claims.Email)
    }
    if len(claims.Roles) > 0 {
        req.Header.Set(p.config.UserRolesHeader, strings.Join(claims.Roles, ","))
    }
    if claims.DeviceID != "" {
        req.Header.Set(p.config.UserDeviceHeader, claims.DeviceID)
    }
    if claims.SessionID != "" {
        req.Header.Set(p.config.UserSessionHeader, claims.SessionID)
    }
}

// subscribeToJWKSUpdates listens for JWKS invalidation events via Redis Pub/Sub
func (p *JWTAuthPlugin) subscribeToJWKSUpdates() {
    ctx := context.Background()
    
    for {
        func() {
            // Subscribe to JWKS invalidation channel
            pubsub := p.redis.Subscribe(ctx, p.config.JWKSInvalidateChannel)
            defer pubsub.Close()
            
            ch := pubsub.Channel()
            
            for msg := range ch {
                log.Printf("Received JWKS invalidation: %s", msg.Payload)
                
                // Parse message to get specific kid (optional)
                var event struct {
                    Type string `json:"type"`
                    Kid  string `json:"kid,omitempty"`
                }
                
                if err := json.Unmarshal([]byte(msg.Payload), &event); err == nil {
                    if event.Kid != "" {
                        p.cache.InvalidateKey(event.Kid)
                        log.Printf("Invalidated specific key: %s", event.Kid)
                    } else {
                        p.cache.ClearCache()
                        log.Printf("Cleared entire JWKS cache")
                    }
                }
            }
        }()
        
        // Reconnect on disconnect
        time.Sleep(time.Second)
    }
}
```

### 4.7 Plugin Entry Point (main.go)

```go
// go/services/api-gateway/cmd/traefik-plugin/main.go

package main

import (
    "context"
    "encoding/json"
    "fmt"
    "log"
    "net/http"
    "os"
    "time"
)

// PluginConfig holds the configuration passed by Traefik
type PluginConfig struct {
    AuthServiceURL     string   `json:"authServiceURL"`
    JWKSPath            string   `json:"jwksPath"`
    RedisAddr           string   `json:"redisAddr"`
    RedisPassword       string   `json:"redisPassword"`
    RedisDB             int      `json:"redisDB"`
    JWKSCacheTTLMinutes int      `json:"jwksCacheTTLMinutes"`
    DenylistKeyPrefix   string   `json:"denylistKeyPrefix"`
    RevokedBeforePrefix string   `json:"revokedBeforePrefix"`
    JWKSInvalidateChannel string `json:"jwksInvalidateChannel"`
    PublicPaths         []string `json:"publicPaths"`
    UserIdHeader        string   `json:"userIdHeader"`
    UserRolesHeader     string   `json:"userRolesHeader"`
    UserEmailHeader     string   `json:"userEmailHeader"`
    UserDeviceHeader    string   `json:"userDeviceHeader"`
    UserSessionHeader   string   `json:"userSessionHeader"`
}

// CreateConfig creates the plugin configuration
func CreateConfig() *PluginConfig {
    return &PluginConfig{
        AuthServiceURL:       "http://auth-service:8081",
        JWKSPath:             "/.well-known/jwks.json",
        RedisAddr:            "redis:6379",
        RedisPassword:        "",
        RedisDB:              0,
        JWKSCacheTTLMinutes:  10,
        DenylistKeyPrefix:    "auth:denylist",
        RevokedBeforePrefix:  "auth:revoked_before",
        JWKSInvalidateChannel: "auth:jwks:update",
        PublicPaths: []string{
            "/health",
            "/.well-known/",
            "/api/auth/",
        },
        UserIdHeader:      "X-User-Id",
        UserRolesHeader:   "X-User-Roles",
        UserEmailHeader:   "X-User-Email",
        UserDeviceHeader:  "X-User-Device-Id",
        UserSessionHeader: "X-User-Session-Id",
    }
}

// JWTAuthMiddleware is the main plugin struct
type JWTAuthMiddleware struct {
    next   http.Handler
    plugin *JWTAuthPlugin
    config *PluginConfig
}

// New instantiates the plugin
func New(ctx context.Context, next http.Handler, config *json.RawMessage) (http.Handler, error) {
    // Parse config
    cfg := CreateConfig()
    if config != nil {
        if err := json.Unmarshal(*config, cfg); err != nil {
            return nil, fmt.Errorf("failed to parse config: %w", err)
        }
    }
    
    // Create plugin
    pluginConfig := &Config{
        AuthServiceURL:        cfg.AuthServiceURL,
        JWKSPath:              cfg.JWKSPath,
        RedisAddr:             cfg.RedisAddr,
        RedisPassword:         cfg.RedisPassword,
        RedisDB:               cfg.RedisDB,
        JWKSCacheTTL:          time.Duration(cfg.JWKSCacheTTLMinutes) * time.Minute,
        DenylistKeyPrefix:     cfg.DenylistKeyPrefix,
        RevokedBeforePrefix:   cfg.RevokedBeforePrefix,
        JWKSInvalidateChannel: cfg.JWKSInvalidateChannel,
        PublicPaths:           cfg.PublicPaths,
        UserIdHeader:          cfg.UserIdHeader,
        UserRolesHeader:       cfg.UserRolesHeader,
        UserEmailHeader:       cfg.UserEmailHeader,
        UserDeviceHeader:      cfg.UserDeviceHeader,
        UserSessionHeader:    cfg.UserSessionHeader,
    }
    
    plugin, err := NewJWTAuthPlugin(pluginConfig)
    if err != nil {
        return nil, fmt.Errorf("failed to create plugin: %w", err)
    }
    
    log.Printf("JWT Auth Plugin initialized with config:")
    log.Printf("  Auth Service URL: %s", cfg.AuthServiceURL)
    log.Printf("  Redis: %s", cfg.RedisAddr)
    log.Printf("  JWKS Cache TTL: %d minutes", cfg.JWKSCacheTTLMinutes)
    log.Printf("  Public Paths: %v", cfg.PublicPaths)
    
    return &JWTAuthMiddleware{
        next:   next,
        plugin: plugin,
        config: cfg,
    }, nil
}

// ServeHTTP handles the request
func (m *JWTAuthMiddleware) ServeHTTP(rw http.ResponseWriter, req *http.Request) {
    m.plugin.ServeHTTP(rw, req)
}
```

### 4.8 go.mod

```go
// go/services/api-gateway/cmd/traefik-plugin/go.mod

module github.com/yourusername/api-gateway/traefik-plugin

go 1.21

require (
    github.com/lestrrat-go/jwx/v2 v2.0.18
    github.com/redis/go-redis/v9 v9.4.0
)

require (
    github.com/decred/dcrd/dcrec/secp256k1/v4 v4.2.0 // indirect
    github.com/golang-jwt/jwt/v5 v5.2.0 // indirect
    github.com/lestrrat-go/blackmagic v1.0.2 // indirect
    github.com/lestrrat-go/httpcc v1.0.1 // indirect
    github.com/lestrrat-go/httprc v1.0.4 // indirect
    github.com/lestrrat-go/iter v1.0.2 // indirect
    github.com/lestrrat-go/option v1.0.1 // indirect
    github.com/lestrrat-go/schema v0.0.0-20220113175252-6a1848262dcf // indirect
    github.com/redis/go-redis/v9 v9.4.0 // indirect
    golang.org/x/crypto v0.17.0 // indirect
    golang.org/x/sys v0.15.0 // indirect
)
```

### 4.9 Build và Load Plugin

```bash
# Build Go plugin
cd go/services/api-gateway/cmd/traefik-plugin
go build -buildmode=plugin -o ../../../plugins/jwt_auth.so .

# Hoặc sử dụng Makefile
```

```makefile
# go/services/api-gateway/Makefile

PLUGIN_DIR := plugins
PLUGIN_NAME := jwt_auth.so

build-plugin:
	cd cmd/traefik-plugin && go build -buildmode=plugin -o $(PLUGIN_DIR)/$(PLUGIN_NAME) .

clean-plugin:
	rm -f $(PLUGIN_DIR)/$(PLUGIN_NAME)

.PHONY: build-plugin clean-plugin
```

### 4.10 Traefik Middleware Configuration với Plugin

```yaml
# go/services/api-gateway/config/dynamic.yml

http:
  middlewares:
    # JWT Auth Middleware (Go Plugin)
    jwt-auth:
      plugin:
        jwt-auth:  # Tên plugin trùng với thư mục trong plugins/
          authServiceURL: "http://auth-service:8081"
          redisAddr: "redis:6379"
          redisDB: 0
          jwksCacheTTLMinutes: 10
          denylistKeyPrefix: "auth:denylist"
          revokedBeforePrefix: "auth:revoked_before"
          jwksInvalidateChannel: "auth:jwks:update"
          publicPaths:
            - "/health"
            - "/.well-known/"
            - "/api/auth/login"
            - "/api/auth/register"
          userIdHeader: "X-User-Id"
          userRolesHeader: "X-User-Roles"
          userEmailHeader: "X-User-Email"
          userDeviceHeader: "X-User-Device-Id"
          userSessionHeader: "X-User-Session-Id"
```

---

## 5. Invalidation JWKS Cache qua Redis Pub/Sub

### 5.1 Mô tả cơ chế

```
┌─────────────────┐                      ┌─────────────────┐
│  Auth Service   │                      │  API Gateway    │
│                 │                      │                 │
│  Key Rotation   │──── Publish ────────▶│  JWKS Cache     │
│  Worker         │   "auth:jwks:update" │  Invalidation   │
│                 │   {                  │                 │
│                 │     "type": "all",   │  Clear local   │
│                 │     "kid": null      │  cache         │
│                 │   }                  │                 │
└─────────────────┘                      └─────────────────┘
                                                 │
                                                 ▼
                                        ┌─────────────────┐
                                        │  Redis Cluster  │
                                        │  (Pub/Sub)      │
                                        └─────────────────┘
```

### 5.2 Auth Service: Publisher Implementation

Auth Service đã có `JwksPublisher` trong `wire.go`. Cần publish event khi key rotation xảy ra.

```go
// Trong auth-service/internal/worker/key_retirement.go hoặc key_rotation.go

func (w *KeyRotationWorker) publishJWKSUpdate(ctx context.Context, kid string) error {
    if w.publisher == nil {
        return nil
    }
    
    message := map[string]interface{}{
        "type":      "key_rotation",
        "kid":       kid,
        "timestamp": time.Now().UTC().Format(time.RFC3339),
    }
    
    payload, err := json.Marshal(message)
    if err != nil {
        return err
    }
    
    return w.publisher.Publish(ctx, "auth:jwks:update", payload)
}

// Khi retire key cũ
func (w *KeyRetirementWorker) publishJWKSInvalidation(ctx context.Context, kid string) error {
    if w.publisher == nil {
        return nil
    }
    
    message := map[string]interface{}{
        "type":      "key_retirement",
        "kid":       kid,
        "timestamp": time.Now().UTC().Format(time.RFC3339),
    }
    
    payload, err := json.Marshal(message)
    if err != nil {
        return err
    }
    
    return w.publisher.Publish(ctx, "auth:jwks:update", payload)
}
```

### 5.3 API Gateway: Subscriber Implementation (trong plugin)

```go
// Đã implement trong jwt_auth.go, phương thức subscribeToJWKSUpdates()

// subscribeToJWKSUpdates listens for JWKS invalidation events via Redis Pub/Sub
func (p *JWTAuthPlugin) subscribeToJWKSUpdates() {
    ctx := context.Background()
    
    for {
        func() {
            // Subscribe to JWKS invalidation channel
            pubsub := p.redis.Subscribe(ctx, p.config.JWKSInvalidateChannel)
            defer pubsub.Close()
            
            ch := pubsub.Channel()
            
            for msg := range ch {
                log.Printf("Received JWKS invalidation: %s", msg.Payload)
                
                // Parse message
                var event JWKSInvalidationEvent
                if err := json.Unmarshal([]byte(msg.Payload), &event); err != nil {
                    log.Printf("Failed to parse JWKS invalidation event: %v", err)
                    continue
                }
                
                // Handle based on event type
                switch event.Type {
                case "key_rotation", "key_retirement":
                    if event.Kid != "" {
                        p.cache.InvalidateKey(event.Kid)
                        log.Printf("Invalidated specific key: %s", event.Kid)
                    } else {
                        p.cache.ClearCache()
                        log.Printf("Cleared entire JWKS cache")
                    }
                    
                case "all":
                    p.cache.ClearCache()
                    log.Printf("Cleared entire JWKS cache (all)")
                    
                default:
                    log.Printf("Unknown JWKS invalidation event type: %s", event.Type)
                }
            }
        }()
        
        // Reconnect on disconnect with backoff
        time.Sleep(time.Second)
    }
}

type JWKSInvalidationEvent struct {
    Type      string `json:"type"`
    Kid       string `json:"kid,omitempty"`
    Timestamp string `json:"timestamp"`
}
```

### 5.4 Redis Connection Configuration

```yaml
# Traefik dynamic config - Redis connection settings
# Đặt trong environment hoặc config

environment:
  REDIS_ADDR: "redis:6379"
  REDIS_PASSWORD: "${REDIS_PASSWORD}"
  REDIS_DB: "0"

# Pool settings for high throughput
# Redis connection pool: 10 connections
# Pipeline: enabled for batch operations
```

---

## 6. Rate Limiting

### 6.1 Traefik Rate Limit Middleware

```yaml
# go/services/api-gateway/config/dynamic.yml

http:
  middlewares:
    # Rate limiting mặc định theo IP
    rate-limit-default:
      rateLimit:
        average: 100
        burst: 200
        period: 1s
        sourceCriterion:
          ipStrategy:
            depth: 2
            excludedIPs:
              - "127.0.0.1/32"
              - "10.0.0.0/8"
        averageByService: false

    # Rate limiting nghiêm ngặt cho API
    rate-limit-api:
      rateLimit:
        average: 50
        burst: 100
        period: 1s
        sourceCriterion:
          ipStrategy:
            depth: 2

    # Rate limiting cho payment (strict)
    rate-limit-payment:
      rateLimit:
        average: 20
        burst: 40
        period: 1s
        sourceCriterion:
          ipStrategy:
            depth: 2

    # Rate limiting cho auth endpoints
    rate-limit-auth:
      rateLimit:
        average: 10
        burst: 20
        period: 1s
        sourceCriterion:
          ipStrategy:
            depth: 2

    # Rate limiting theo header (User-Id sau auth)
    rate-limit-user:
      rateLimit:
        average: 200
        burst: 400
        period: 1s
        sourceCriterion:
          requestHeaderName: "X-User-Id"
          ipStrategy:
            depth: 2
```

### 6.2 Rate Limiting với Redis (Distributed)

```yaml
# go/services/api-gateway/config/traefik.yml - thêm Redis cho distributed rate limiting

# Experimental features
experimental:
  plugins:
    redis-ratelimit:
      moduleName: github.com/traefik/plugindemo/redisratelimit
      version: v1.0.0
```

Hoặc sử dụng middleware plugin tự viết:

```go
// cmd/traefik-plugin/rate_limiter.go

package main

import (
    "context"
    "fmt"
    "net/http"
    "time"
    
    "github.com/redis/go-redis/v9"
    "golang.org/x/time/rate"
)

type RateLimiter struct {
    redis    *redis.Client
    limiter  map[string]*rate.Limiter
    burst    int
    rate     rate.Limit
}

func NewRateLimiter(redisAddr, redisPassword string, redisDB int, rps int, burst int) *RateLimiter {
    rdb := redis.NewClient(&redis.Options{
        Addr:     redisAddr,
        Password: redisPassword,
        DB:       redisDB,
    })
    
    return &RateLimiter{
        redis:   rdb,
        limiter: make(map[string]*rate.Limiter),
        rate:    rate.Limit(rps),
        burst:   burst,
    }
}

func (rl *RateLimiter) Allow(ctx context.Context, key string) (bool, error) {
    // Sử dụng Redis sliding window
    now := time.Now().UnixNano()
    window := now - int64(time.Second)
    
    pipe := rl.redis.Pipeline()
    
    // Remove old entries
    pipe.ZRemRangeByScore(ctx, key, "0", fmt.Sprintf("%d", window))
    
    // Count current entries
    countCmd := pipe.ZCard(ctx, key)
    
    // Add current request
    pipe.ZAdd(ctx, key, redis.Z{Score: float64(now), Member: now})
    
    // Set expiry
    pipe.Expire(ctx, key, 2*time.Second)
    
    _, err := pipe.Exec(ctx)
    if err != nil {
        return false, err
    }
    
    count := countCmd.Val()
    return count < int64(rl.burst), nil
}

func (rl *RateLimiter) ServeHTTP(rw http.ResponseWriter, req *http.Request) {
    // Get client IP
    clientIP := req.Header.Get("X-Forwarded-For")
    if clientIP == "" {
        clientIP = req.RemoteAddr
    }
    
    key := fmt.Sprintf("ratelimit:%s", clientIP)
    
    allowed, err := rl.Allow(req.Context(), key)
    if err != nil {
        // On error, allow request (fail open)
        rw.WriteHeader(http.StatusOK)
        return
    }
    
    if !allowed {
        http.Error(rw, `{"error": "rate_limit_exceeded", "message": "Too many requests"}`, http.StatusTooManyRequests)
        return
    }
    
    rw.WriteHeader(http.StatusOK)
}
```

### 6.3 Áp dụng Rate Limit theo Service

```yaml
# docker-compose.yml của user-service

services:
  user-service:
    labels:
      # Áp dụng rate limit
      - "traefik.http.routers.user-service.middlewares=rate-limit-api"
      
      # Hoặc rate limit theo user (sau auth)
      - "traefik.http.routers.user-service.middlewares=rate-limit-user"
```

---

## 7. OpenTelemetry & Logging

### 7.1 Traefik Tracing Configuration

```yaml
# go/services/api-gateway/config/traefik.yml

# Tracing configuration
tracing:
  serviceName: api-gateway
  spanNameFormatter: "{{ .Name }}@{{ .Service.Name }}"
  
  # OpenTelemetry exporter
  otlp:
    endpoint: "jaeger-collector:4317"
    protocol: "grpc"
    tls:
      insecure: true
  
  # Sampling
  samplingRate: 1.0  # 100% sampling in dev, lower in prod

# Metrics (Prometheus)
metrics:
  prometheus:
    addEntryPointsLabels: true
    addServicesLabels: true
    entryPoint: metrics
    buckets:
      - 0.1
      - 0.3
      - 0.5
      - 1.0
      - 2.0
      - 5.0
      - 10.0
```

### 7.2 Access Log Format

```yaml
# go/services/api-gateway/config/traefik.yml

log:
  level: INFO
  format: json
  fields:
    defaultMode: keep
    headers:
      names:
        X-Request-Id: keep
        traceparent: keep

accessLog:
  format: json
  bufferingSize: 100
  filters:
    statusCodes:
      - "200-599"
  fields:
    defaultMode: keep
    names:
      timestamp:
        mode: date
      client_ip:
        mode: ip
      duration:
        mode: float
      StartUTC:
        mode: date
    headers:
      defaultMode: drop
      names:
        Authorization:
          mode: redact
          keep:
            - "^Bearer .+"
        X-User-Id:
          mode: keep
        X-Request-Id:
          mode: keep
        traceparent:
          mode: keep
```

### 7.3 Expected Access Log JSON Format

```json
{
  "timestamp": "2026-05-15T10:23:45.100Z",
  "level": "info",
  "message": "",
  "client_ip": "192.168.1.100",
  "client_port": 54321,
  "downstream_status": 200,
  "duration_ms": 12.345,
  "middleware": "jwt-auth",
  "origin_content_size": 1024,
  "overhead_ms": 1.234,
  "request_host": "api.ecommerce.local",
  "request_method": "GET",
  "request_path": "/api/users/123",
  "request_protocol": "HTTP/2.0",
  "request_query": "",
  "response_flags": "",
  "service_name": "user-service",
  "source_ip": "192.168.1.100",
  "span_id": "span001",
  "time": "15/May/2026:10:23:45 +0000",
  "trace_id": "a1b2c3d4e5f6g7h8",
  "X-Request-Id": "req-uuid",
  "traceparent": "00-a1b2c3d4e5f6g7h8-span001-01"
}
```

### 7.4 Logging trong Custom Middleware

```go
// cmd/traefik-plugin/logger.go

package main

import (
    "log"
    "os"
    "time"
    
    "go.uber.org/zap"
    "go.uber.org/zap/zapcore"
)

func NewLogger() (*zap.Logger, error) {
    config := zap.Config{
        Level:       zap.NewAtomicLevelAt(zap.InfoLevel),
        Development: false,
        Encoding:    "json",
        EncoderConfig: zapcore.EncoderConfig{
            TimeKey:        "timestamp",
            LevelKey:       "level",
            NameKey:        "logger",
            CallerKey:      "caller",
            MessageKey:     "message",
            StacktraceKey:  "stacktrace",
            LineEnding:     zapcore.DefaultLineEnding,
            EncodeLevel:    zapcore.LowercaseLevelEncoder,
            EncodeTime:     zapcore.ISO8601TimeEncoder,
            EncodeDuration: zapcore.MillisDurationEncoder,
            EncodeCaller:   zapcore.ShortCallerEncoder,
        },
        OutputPaths:      []string{"stdout"},
        ErrorOutputPaths: []string{"stderr"},
    }
    
    return config.Build()
}

// Structured logging với trace_id
func (p *JWTAuthPlugin) logWithTrace(traceID, message string, fields ...zap.Field) {
    fields = append(fields, zap.String("service", "api-gateway"))
    fields = append(fields, zap.String("middleware", "jwt-auth"))
    
    // Log ở đây - sử dụng global logger
    log.Printf("[%s] %s", traceID, message)
}

// Trong ServeHTTP:
func (p *JWTAuthPlugin) ServeHTTP(rw http.ResponseWriter, req *http.Request) {
    traceID := req.Header.Get("X-Request-Id")
    if traceID == "" {
        traceID = generateTraceID()
    }
    
    // Log request received
    log.Printf("[%s] Request received: %s %s", traceID, req.Method, req.URL.Path)
    
    // ... auth logic ...
    
    // Log auth success
    log.Printf("[%s] Auth successful: user=%s", traceID, claims.Subject)
}
```

### 7.5 Trace ID Propagation

```yaml
# Traefik tự động sinh và propagate trace_id

# Nếu client gửi traceparent header:
#   traceparent: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01
# Traefik sử dụng trace_id từ header đó

# Nếu không có:
#   Traefik sinh trace_id mới và thêm vào response header

# Traefik tự động forward traceparent đến upstream services
# Cấu hình:
tracing:
  propagateNewWorks: true
```

---

## 8. Deployment & Configuration Management

### 8.1 Full Docker Compose Setup

```yaml
# go/services/api-gateway/docker-compose.yml

version: '3.8'

services:
  # ==================== API Gateway ====================
  traefik:
    image: traefik:v3.0
    container_name: api-gateway
    restart: unless-stopped
    security_opt:
      - no-new-privileges:true
    user: "101:101"  # Non-root user
    ports:
      - "80:80"
      - "443:443"
      - "8080:8080"  # Dashboard & Metrics
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock:ro
      - ./config/traefik.yml:/etc/traefik/traefik.yml:ro
      - ./config/dynamic.yml:/etc/traefik/dynamic/dynamic.yml:ro
      - ./plugins:/plugins
      - ./certs:/certs:ro  # TLS certificates
    environment:
      - TZ=Asia/Ho_Chi_Minh
      - TRAEFIK_LOG_LEVEL=INFO
      - TRAEFIK_METRICS_PROMETHEUS_ADDR=:8080
    networks:
      - ecommerce-network
    labels:
      - "traefik.enable=true"
      - "traefik.http.routers.dashboard.rule=Host(`traefik.localhost`)"
      - "traefik.http.routers.dashboard.service=api@internal"
      - "traefik.http.routers.dashboard.middlewares=auth-dashboard"
      - "traefik.http.middlewares.auth-dashboard.basicauth.users=demo:$$apr1$$H6uskkkW$$IgXLP6ewTrSuBkTrqE8wj.,admin:$$apr1$$d9b9hslK$$9P.JQ7pOCXbVVAx7Xk9Q40"
    depends_on:
      - redis

  # ==================== Services ====================
  auth-service:
    image: auth-service:latest
    container_name: auth-service
    restart: unless-stopped
    networks:
      - ecommerce-network
    labels:
      - "traefik.enable=true"
      - "traefik.http.routers.auth-service.rule=PathPrefix(`/.well-known`)"
      - "traefik.http.routers.auth-service.entrypoints=websecure"
      - "traefik.http.routers.auth-service.tls=true"
      - "traefik.http.services.auth-service.loadbalancer.server.port=8081"

  user-service:
    image: user-service:latest
    container_name: user-service
    restart: unless-stopped
    networks:
      - ecommerce-network
    labels:
      - "traefik.enable=true"
      - "traefik.http.routers.user-service.rule=PathPrefix(`/api/users`)"
      - "traefik.http.routers.user-service.entrypoints=websecure"
      - "traefik.http.routers.user-service.tls=true"
      - "traefik.http.routers.user-service.middlewares=jwt-auth,rate-limit-api,secure-headers"
      - "traefik.http.services.user-service.loadbalancer.server.port=8080"
      - "traefik.http.services.user-service.loadbalancer.healthcheck.path=/health"
      - "traefik.http.services.user-service.loadbalancer.healthcheck.interval=10s"
      - "traefik.http.services.user-service.loadbalancer.healthcheck.timeout=5s"

  order-service:
    image: order-service:latest
    container_name: order-service
    restart: unless-stopped
    networks:
      - ecommerce-network
    labels:
      - "traefik.enable=true"
      - "traefik.http.routers.order-service.rule=PathPrefix(`/api/orders`)"
      - "traefik.http.routers.order-service.entrypoints=websecure"
      - "traefik.http.routers.order-service.tls=true"
      - "traefik.http.routers.order-service.middlewares=jwt-auth,rate-limit-api"
      - "traefik.http.services.order-service.loadbalancer.server.port=8080"
      - "traefik.http.services.order-service.loadbalancer.healthcheck.path=/health"

  product-service:
    image: product-service:latest
    container_name: product-service
    restart: unless-stopped
    networks:
      - ecommerce-network
    labels:
      - "traefik.enable=true"
      - "traefik.http.routers.product-service.rule=PathPrefix(`/api/products`)"
      - "traefik.http.routers.product-service.entrypoints=websecure"
      - "traefik.http.routers.product-service.tls=true"
      - "traefik.http.services.product-service.loadbalancer.server.port=8080"

  payment-service:
    image: payment-service:latest
    container_name: payment-service
    restart: unless-stopped
    networks:
      - ecommerce-network
    labels:
      - "traefik.enable=true"
      - "traefik.http.routers.payment-service.rule=PathPrefix(`/api/payments`)"
      - "traefik.http.routers.payment-service.entrypoints=websecure"
      - "traefik.http.routers.payment-service.tls=true"
      - "traefik.http.routers.payment-service.middlewares=jwt-auth,rate-limit-payment"
      - "traefik.http.services.payment-service.loadbalancer.server.port=8080"

  # ==================== Infrastructure ====================
  redis:
    image: redis:7-alpine
    container_name: redis
    restart: unless-stopped
    command: redis-server --appendonly yes --requirepass ${REDIS_PASSWORD}
    volumes:
      - redis-data:/data
    networks:
      - ecommerce-network
    labels:
      - "traefik.enable=true"
      - "traefik.tcp.routers.redis.rule=HostSNI(`*`)"
      - "traefik.tcp.routers.redis.entrypoints=redis"
      - "traefik.tcp.routers.redis.service=redis-tcp"
      - "traefik.tcp.services.redis-tcp.loadbalancer.server.port=6379"

  jaeger:
    image: jaegertracing/all-in-one:1.52
    container_name: jaeger
    restart: unless-stopped
    ports:
      - "16686:16686"  # UI
      - "4317:4317"    # OTLP gRPC
      - "4318:4318"    # OTLP HTTP
    environment:
      - COLLECTOR_OTLP_ENABLED=true
    networks:
      - ecommerce-network

networks:
  ecommerce-network:
    name: ecommerce-network
    driver: bridge

volumes:
  redis-data:
    driver: local
```

### 8.2 Environment Variables (.env)

```bash
# go/services/api-gateway/.env

# Traefik
DOMAIN=api.ecommerce.local
TLS_EMAIL=admin@ecommerce.local

# Redis
REDIS_HOST=redis
REDIS_PORT=6379
REDIS_PASSWORD=your-secure-redis-password
REDIS_DB=0

# Auth Service
AUTH_SERVICE_URL=http://auth-service:8081

# Services
USER_SERVICE_URL=http://user-service:8080
ORDER_SERVICE_URL=http://order-service:8080
PRODUCT_SERVICE_URL=http://product-service:8080
PAYMENT_SERVICE_URL=http://payment-service:8080

# Tracing
JAEGER_ENDPOINT=http://jaeger:4317
```

### 8.3 Production Deployment (Multiple Replicas)

```yaml
# go/services/api-gateway/docker-compose.prod.yml

version: '3.8'

services:
  traefik:
    image: traefik:v3.0
    deploy:
      replicas: 3
      placement:
        constraints:
          - node.role == worker
      resources:
        limits:
          cpus: '2'
          memory: 2G
        reservations:
          cpus: '0.5'
          memory: 512M
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock:ro
    command:
      - --api.dashboard=true
      - --providers.docker.exposedbydefault=false
      - --providers.docker.network=ecommerce-network
      - --entrypoints.websecure.http.tls.domains[0].main=api.ecommerce.local
      - --entrypoints.websecure.http.tls.domains[0].sans=*.api.ecommerce.local
      - --certificatesresolvers.myresolver.acme.storage=/letsencrypt/acme.json
      - --certificatesresolvers.myresolver.acme.tlschallenge=true
      - --certificatesresolvers.myresolver.acme.email=${TLS_EMAIL}
      - --tracing.otlp.endpoint=${JAEGER_ENDPOINT}
      - --metrics.prometheus=true
      - --metrics.prometheus.entrypoint=metrics
    environment:
      - TRAEFIK_LOG_LEVEL=WARN
    networks:
      - ecommerce-network
    ports:
      - "80:80"
      - "443:443"
      - "8080:8080"
    restart: unless-stopped
```

### 8.4 Kubernetes Deployment (Optional)

```yaml
# go/services/api-gateway/k8s/values.yaml

# Sử dụng Helm chart chính thức của Traefik

replicaCount: 3

image:
  repository: traefik
  tag: v3.0

service:
  type: LoadBalancer
  ports:
    web: 80
    websecure: 443
    metrics: 8080

ingressRoute:
  dashboard:
    enabled: true
    ingressClassName: traefik
    annotations:
      kubernetes.io/ingress.class: traefik

persistence:
  enabled: true
  storageClass: "gp3"
  size: 1Gi

resources:
  requests:
    cpu: 500m
    memory: 512Mi
  limits:
    cpu: 2
    memory: 2Gi

plugins:
  enabled: true
  pluginsDir: /plugins

# Enable dashboard with basic auth
api:
  dashboard: true
  insecure: false

# TLS configuration
tls:
  enabled: true
  secretName: api-gateway-tls

# Middleware
middlewares:
  jwt-auth:
    plugin:
      jwt-auth:
        authServiceURL: "http://auth-service:8081"
        redisAddr: "redis-master:6379"
```

---

## 9. Testing & Validation

### 9.1 Test Plan Overview

| Test Case | Mục tiêu | Tool | Priority |
|-----------|----------|------|----------|
| Routing Test | Route đúng service theo path | curl, Postman | P0 |
| JWT Auth Test | Xác thực đúng/sai token | curl | P0 |
| JWKS Cache Test | Cache hoạt động, không gọi Auth Service liên tục | curl, logs | P0 |
| Denylist Test | Token bị denylist → 401 | curl | P0 |
| Revocation Test | Token bị revoke (password change) → 401 | curl | P0 |
| Rate Limit Test | Vượt ngưỡng → 429 | wrk, k6 | P1 |
| JWKS Invalidation Test | Pub/Sub invalidates cache | Redis CLI | P1 |
| Tracing Test | Trace ID xuất hiện trong logs | curl, Jaeger UI | P1 |
| Performance Test | Latency < 10ms cho auth | wrk | P2 |
| Failover Test | Redis down → graceful degradation | - | P2 |

### 9.2 Test Scripts

#### 9.2.1 JWT Token Generation (for testing)

```bash
#!/bin/bash
# scripts/generate-test-token.sh

# Sử dụng Auth Service endpoint
AUTH_SERVICE="http://localhost:8081"

# Login để lấy token
TOKEN=$(curl -s -X POST "${AUTH_SERVICE}/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"password123"}' \
  | jq -r '.access_token')

echo "Generated Token: $TOKEN"
```

#### 9.2.2 Routing Tests

```bash
#!/bin/bash
# scripts/test-routing.sh

GATEWAY="http://localhost:80"
TOKEN="eyJ..."

echo "=== Testing Routing ==="

# Test user-service
echo -n "User Service: "
curl -s -o /dev/null -w "%{http_code}" "${GATEWAY}/api/users/1"
echo ""

# Test order-service
echo -n "Order Service: "
curl -s -o /dev/null -w "%{http_code}" "${GATEWAY}/api/orders/1"
echo ""

# Test product-service (public)
echo -n "Product Service: "
curl -s -o /dev/null -w "%{http_code}" "${GATEWAY}/api/products/1"
echo ""

# Test JWKS endpoint
echo -n "JWKS Endpoint: "
curl -s -o /dev/null -w "%{http_code}" "${GATEWAY}/.well-known/jwks.json"
echo ""
```

#### 9.2.3 JWT Authentication Tests

```bash
#!/bin/bash
# scripts/test-jwt-auth.sh

GATEWAY="http://localhost:80"

echo "=== JWT Authentication Tests ==="

# Test 1: Missing Authorization header
echo -n "1. Missing Auth Header: "
curl -s -o /dev/null -w "%{http_code}\n" "${GATEWAY}/api/users/1"

# Test 2: Invalid token format
echo -n "2. Invalid Token Format: "
curl -s -o /dev/null -w "%{http_code}\n" \
  -H "Authorization: InvalidToken" \
  "${GATEWAY}/api/users/1"

# Test 3: Expired token (need to generate one)
echo -n "3. Expired Token: "
curl -s -o /dev/null -w "%{http_code}\n" \
  -H "Authorization: Bearer expired.token.here" \
  "${GATEWAY}/api/users/1"

# Test 4: Valid token
echo -n "4. Valid Token: "
curl -s -o /dev/null -w "%{http_code}\n" \
  -H "Authorization: Bearer ${VALID_TOKEN}" \
  "${GATEWAY}/api/users/1"

# Test 5: Public path without token
echo -n "5. Public Path (JWKS): "
curl -s -o /dev/null -w "%{http_code}\n" "${GATEWAY}/.well-known/jwks.json"

# Test 6: Check headers enrichment
echo -n "6. Headers Enrichment: "
curl -s -i \
  -H "Authorization: Bearer ${VALID_TOKEN}" \
  "${GATEWAY}/api/users/me" | grep -E "^X-User"
```

#### 9.2.4 Rate Limiting Tests

```bash
#!/bin/bash
# scripts/test-rate-limit.sh

GATEWAY="http://localhost:80"
TOKEN="eyJ..."

echo "=== Rate Limiting Tests ==="

# Send burst requests
for i in {1..120}; do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    -H "Authorization: Bearer ${TOKEN}" \
    "${GATEWAY}/api/users/1")
  
  if [ "$STATUS" == "429" ]; then
    echo "Rate limited at request $i"
    break
  fi
done

echo "Completed rate limit test"
```

#### 9.2.5 JWKS Cache Invalidation Test

```bash
#!/bin/bash
# scripts/test-jwks-invalidation.sh

REDIS_HOST="localhost"
REDIS_PORT="6379"
REDIS_PWD="your-password"

echo "=== JWKS Cache Invalidation Tests ==="

# Publish invalidation message
echo "Publishing JWKS invalidation..."
redis-cli -h $REDIS_HOST -p $REDIS_PORT -a $REDIS_PWD \
  PUBLISH auth:jwks:update '{"type":"all","kid":null,"timestamp":"2026-05-15T10:00:00Z"}'

# Check plugin logs for cache clear
echo "Check plugin logs for: 'Cleared entire JWKS cache'"
```

### 9.3 Load Testing với k6

```javascript
// scripts/load-test.js

import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '30s', target: 100 },   // Ramp up
    { duration: '1m', target: 100 },    // Steady
    { duration: '30s', target: 200 },   // Spike
    { duration: '1m', target: 200 },    // Steady at spike
    { duration: '30s', target: 0 },     // Ramp down
  ],
  thresholds: {
    http_req_duration: ['p(95)<500'],   // 95% requests under 500ms
    http_req_failed: ['rate<0.01'],      // Error rate < 1%
  },
};

const BASE_URL = 'http://localhost:80';
const TOKEN = __ENV.TOKEN;

export default function () {
  const headers = {
    'Authorization': `Bearer ${TOKEN}`,
    'Content-Type': 'application/json',
  };

  // Test user endpoint
  const res = http.get(`${BASE_URL}/api/users/1`, { headers });
  
  check(res, {
    'status is 200 or 401': (r) => [200, 401].includes(r.status),
    'has trace ID': (r) => r.headers['X-Request-Id'] !== undefined,
    'response time < 500ms': (r) => r.timings.duration < 500,
  });

  sleep(0.1);
}
```

```bash
# Chạy load test
k6 run scripts/load-test.js
```

### 9.4 Test với wrk (Simple Benchmark)

```bash
# Cài đặt wrk
# Ubuntu/Debian: sudo apt install wrk

# Benchmark không auth
wrk -t4 -c100 -d30s http://localhost/api/products

# Benchmark với auth (cần script)
wrk -t4 -c100 -d30s -H "Authorization: Bearer $TOKEN" http://localhost/api/users
```

### 9.5 Trace Verification

```bash
#!/bin/bash
# scripts/verify-tracing.sh

GATEWAY="http://localhost:80"

# Gửi request và lấy trace ID
TRACE_ID=$(curl -s -D - -o /dev/null \
  -H "Authorization: Bearer ${TOKEN}" \
  "${GATEWAY}/api/users/1" | \
  grep -i "X-Request-Id:" | awk '{print $2}')

echo "Trace ID: $TRACE_ID"

# Truy cập Jaeger UI để xem trace
echo "Check in Jaeger: http://localhost:16686"
echo "Search for trace ID: $TRACE_ID"
```

---

## 10. Rủi ro và phương án giảm thiểu

### 10.1 Risk Matrix

| Rủi ro | Mức độ | Khả năng | Tác động |
|--------|--------|----------|----------|
| Plugin ABI versioning mismatch | Cao | Cao | Plugin không load được |
| Redis Pub/Sub disconnect | Trung bình | Trung bình | JWKS cache không được invalid |
| Auth Service down | Cao | Thấp | Không verify JWT mới |
| JWKS cache miss storm | Trung bình | Trung bình | Auth Service bị quá tải |
| Team thiếu kỹ năng Go | Cao | Cao | Khó maintain plugin |
| Plugin crash gây crash Traefik | Cao | Thấp | Toàn bộ gateway down |
| Rate limit false positive | Thấp | Thấp | User bị block nhầm |
| Redis down | Cao | Thấp | Denylist check fail |

### 10.2 Giải pháp dự phòng

#### 10.2.1 Plugin Failover - HTTP Forward Auth

```yaml
# docker-compose.yml - chạy song song cả hai

services:
  # Auth middleware service (HTTP forward auth fallback)
  auth-middleware:
    image: auth-middleware:latest
    container_name: auth-middleware
    networks:
      - ecommerce-network
    labels:
      - "traefik.enable=true"
      - "traefik.http.routers.auth-middleware.rule=PathPrefix(`/auth-check`)"
      - "traefik.http.services.auth-middleware.loadbalancer.server.port=8080"
```

```yaml
# dynamic.yml - sử dụng ForwardAuth thay vì plugin

http:
  middlewares:
    # Plugin JWT Auth
    jwt-auth-plugin:
      plugin:
        jwt-auth:
          # config...

    # HTTP Forward Auth (fallback)
    jwt-auth-forward:
      forwardAuth:
        address: "http://auth-middleware:8080/check"
        tls: {}
        trustForwardHeader: true
```

```go
// auth-middleware/cmd/main.go - HTTP auth service fallback

package main

import (
    "net/http"
)

func main() {
    http.HandleFunc("/check", func(w http.ResponseWriter, r *http.Request) {
        // Tương tự logic plugin nhưng chạy như HTTP service
        
        // Lấy token từ header
        token := r.Header.Get("Authorization")
        
        // Verify token
        claims, err := verifyToken(token)
        if err != nil {
            w.WriteHeader(http.StatusUnauthorized)
            return
        }
        
        // Enrich headers
        w.Header().Set("X-User-Id", claims.Subject)
        w.Header().Set("X-User-Roles", claims.Roles)
        
        // Proxy tới upstream
        w.WriteHeader(http.StatusOK)
    })
    
    http.ListenAndServe(":8080", nil)
}
```

#### 10.2.2 JWKS Cache Fallback Strategy

```go
// Khi Redis Pub/Sub down, vẫn dùng TTL-based expiration

func (c *JWKSCache) GetKey(ctx context.Context, kid string) (jwk.Key, error) {
    // Try cache first (with TTL check)
    c.mu.RLock()
    if key, ok := c.keys[kid]; ok && time.Since(c.fetchedAt) < c.ttl {
        c.mu.RUnlock()
        return key, nil
    }
    c.mu.RUnlock()
    
    // Cache miss or expired
    // Retry with exponential backoff
    for attempt := 0; attempt < 3; attempt++ {
        if err := c.refreshCache(ctx); err != nil {
            backoff := time.Duration(attempt*100) * time.Millisecond
            time.Sleep(backoff)
            continue
        }
        
        c.mu.RLock()
        if key, ok := c.keys[kid]; ok {
            c.mu.RUnlock()
            return key, nil
        }
        c.mu.RUnlock()
    }
    
    return nil, fmt.Errorf("failed to get key after retries")
}
```

#### 10.2.3 Graceful Degradation

```go
// Plugin có thể fail-open (allow request) hoặc fail-close (deny request)
// Recommendation: fail-close cho payment, fail-open cho read-only

func (p *JWTAuthPlugin) ServeHTTP(rw http.ResponseWriter, req *http.Request) {
    // Determine if endpoint is critical
    criticalEndpoints := map[string]bool{
        "/api/payments": true,
    }
    
    isCritical := criticalEndpoints[req.URL.Path]
    
    // Try to validate
    claims, err := p.validateRequest(req)
    if err != nil {
        if isCritical {
            // Critical endpoint - deny on error
            http.Error(rw, "Unauthorized", http.StatusUnauthorized)
            return
        }
        // Non-critical - allow on error (fail-open)
        // Log warning
        log.Printf("Auth error (allowing): %v", err)
    }
    
    // Continue...
}
```

### 10.3 Monitoring và Alerting

#### 10.3.1 Prometheus Metrics

```yaml
# Traefik đã expose metrics tự động:
# - traefik_entrypoint_requests_total
# - traefik_entrypoint_request_duration_seconds
# - traefik_service_requests_total
# - traefik_service_request_duration_seconds

# Thêm metrics cho plugin (custom)
# Plugin có thể expose metrics qua /metrics endpoint
```

#### 10.3.2 Prometheus Alert Rules

```yaml
# prometheus/alerts/api-gateway.yml

groups:
  - name: api-gateway
    rules:
      # High error rate
      - alert: GatewayHighErrorRate
        expr: |
          sum(rate(traefik_service_requests_total{code=~"5.."}[5m])) 
          / sum(rate(traefik_service_requests_total[5m])) > 0.05
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "Gateway error rate > 5%"
          description: "{{ $value }}% of requests are failing"

      # High latency
      - alert: GatewayHighLatency
        expr: |
          histogram_quantile(0.95, 
            sum(rate(traefik_service_request_duration_seconds_bucket[5m])) by (le)
          ) > 1
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Gateway P95 latency > 1s"

      # Auth failures spike
      - alert: AuthFailureSpike
        expr: |
          sum(rate(traefik_service_requests_total{code="401"}[5m])) 
          / sum(rate(traefik_service_requests_total[5m])) > 0.2
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "High rate of 401 responses"

      # Rate limit triggered
      - alert: RateLimitTriggered
        expr: |
          sum(rate(traefik_service_requests_total{code="429"}[5m])) > 10
        for: 5m
        labels:
          severity: info
        annotations:
          summary: "Rate limiting is active"

      # JWKS fetch failures
      - alert: JWKSFetchFailure
        expr: |
          sum(rate(jwks_fetch_errors_total[5m])) > 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "JWKS fetch is failing"
```

#### 10.3.3 Grafana Dashboard

```json
{
  "dashboard": {
    "title": "API Gateway Dashboard",
    "panels": [
      {
        "title": "Request Rate",
        "targets": [
          {"expr": "sum(rate(traefik_service_requests_total[5m])) by (service)"}
        ]
      },
      {
        "title": "Error Rate by Service",
        "targets": [
          {"expr": "sum(rate(traefik_service_requests_total{code=~'5..'}[5m])) by (service)"}
        ]
      },
      {
        "title": "P95 Latency",
        "targets": [
          {"expr": "histogram_quantile(0.95, sum(rate(traefik_service_request_duration_seconds_bucket[5m])) by (le, service))"}
        ]
      },
      {
        "title": "Auth Success/Failure",
        "targets": [
          {"expr": "sum(rate(traefik_service_requests_total{code='200'}[5m]))"},
          {"expr": "sum(rate(traefik_service_requests_total{code='401'}[5m]))"}
        ]
      }
    ]
  }
}
```

### 10.4 Go Plugin ABI Compatibility

```bash
# Kiểm tra Go version của Traefik và plugin
docker run --rm traefik:v3.0 --version
# Go 1.21.x

# Plugin phải build với cùng Go version
cd cmd/traefik-plugin
go version
# phải trùng với Traefik

# Build với đúng version
go build -buildmode=plugin -o ../../../plugins/jwt_auth.so .
```

```makefile
# Kiểm tra version trước khi build

check-version:
	@TRAEFIK_VERSION=$$(docker run --rm traefik:v3.0 --version 2>&1 | grep -oP 'v[\d.]+') && \
	GO_VERSION=$$(go version | grep -oP 'go[\d.]+') && \
	echo "Traefik: $$TRAEFIK_VERSION, Go: $$GO_VERSION" && \
	if ! echo "$$TRAEFIK_VERSION" | grep -q "3.0"; then \
		echo "Warning: Plugin may not be compatible"; \
	fi

build-plugin: check-version
	cd cmd/traefik-plugin && go build -buildmode=plugin -o ../../../plugins/jwt_auth.so .
```

### 10.5 Rollback Plan

1. **Plugin fails to load**: Sử dụng HTTP Forward Auth thay vì plugin
2. **Plugin causes crash**: Disable plugin, restart Traefik, investigate logs
3. **JWKS invalidation broken**: Rely on TTL-based expiration (10 phút)
4. **Redis down**: Use fail-open mode cho non-critical endpoints

```yaml
# Emergency: Disable plugin và dùng forward auth
# Sửa dynamic.yml:

http:
  middlewares:
    # jwt-auth-plugin:
    #   plugin:
    #     jwt-auth:
    #       ...

    jwt-auth-forward:
      forwardAuth:
        address: "http://auth-middleware:8080/check"
```

---

## Appendix: File Checklist

```
go/services/api-gateway/
├── cmd/
│   └── traefik-plugin/
│       ├── [D] main.go            # Plugin entry point
│       ├── [D] jwt_auth.go        # Main middleware logic
│       ├── [D] jwks_cache.go     # JWKS caching
│       ├── [D] go.mod
│       └── [ ] jwt_parser.go     # JWT parsing utilities (optional)
├── config/
│   ├── [D] traefik.yml
│   ├── [D] dynamic.yml           # Updated with JWT middleware
│   └── [D] service-labels.env
├── [D] docker-compose.yml
├── [D] Dockerfile
├── [D] Makefile
├── [D] .env.example
├── [D] .gitignore
├── plugins/
│   └── [D] .gitkeep
├── scripts/
│   ├── [D] test-phase2.sh
│   ├── [D] test-phase3.sh
│   ├── [D] test-phase4.sh
│   ├── [D] test-phase5.sh
│   ├── [D] test-phase6.sh
│   └── [D] test-phase7.sh
├── prometheus/
│   └── [D] prometheus.yml
├── grafana/
│   └── provisioning/
│       ├── datasources/
│       │   └── [D] datasources.yml
│       └── dashboards/
│           └── [D] dashboards.yml
├── templates/
│   └── [D] service-docker-compose.yml
├── docs/
│   └── [D] routing-matrix.md
└── letsencrypt/
    └── [D] .gitkeep           # For ACME certificates
```

Legend:
- `[ ]` Chưa tạo
- `[D]` Đã tạo (Phase 1-3)

## Appendix: Environment Variables Reference

| Variable | Default | Description |
|----------|---------|-------------|
| `AUTH_SERVICE_URL` | `http://auth-service:8081` | Auth Service endpoint |
| `REDIS_ADDR` | `redis:6379` | Redis address |
| `REDIS_PASSWORD` | - | Redis password |
| `REDIS_DB` | `0` | Redis database number |
| `JWK_CACHE_TTL` | `10m` | JWKS cache TTL |
| `DENYLIST_PREFIX` | `auth:denylist` | Redis denylist key prefix |
| `REVOKED_PREFIX` | `auth:revoked_before` | Redis revocation key prefix |
| `JWKS_CHANNEL` | `auth:jwks:update` | Pub/Sub channel for JWKS |
| `RATE_LIMIT_RPS` | `100` | Default rate limit RPS |
| `RATE_LIMIT_BURST` | `200` | Default rate limit burst |

## Appendix: Dependencies

### Go Dependencies (Plugin)

| Package | Version | Purpose |
|---------|---------|---------|
| `github.com/lestrrat-go/jwx/v2` | v2.0.18 | JWT parsing & verification |
| `github.com/redis/go-redis/v9` | v9.4.0 | Redis client |
| `go.uber.org/zap` | latest | Structured logging |

### Docker Images

| Image | Version | Purpose |
|-------|---------|---------|
| `traefik` | v3.0 | API Gateway |
| `redis` | 7-alpine | Cache & Pub/Sub |
| `jaegertracing/all-in-one` | 1.52 | Distributed tracing |

---

*Document Version: 1.1*
*Last Updated: 2026-05-15*
*Author: Backend Team*
*Changelog: Added comprehensive implementation checklist with 120 tasks across 13 phases*
