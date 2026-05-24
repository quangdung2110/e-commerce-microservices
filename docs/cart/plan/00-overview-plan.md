# Cart Service — Implementation Plan Overview

**Document:** 00 | **Module:** Overview & Dependency Graph  
**Status:** `[ ]` Chưa hoàn thành | `[x]` Đã hoàn thành  
**Phụ thuộc:** Không có (Module nền tảng nhất)

---

## 1. Kiến trúc Tổng thể

### 1.1. Mô hình Kiến trúc

Cart Service tuân theo mô hình **Redis-First** với kiến trúc CQRS/Event-Driven tuân thủ nghiêm ngặt nguyên tắc **Layered + Hexagonal Architecture**:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              ENTRY POINTS                                │
│   ┌──────────────────┐  ┌──────────────────────┐  ┌────────────────┐  │
│   │   HTTP Server     │  │   Kafka Consumer     │  │  CronJobs      │  │
│   │   (Gin Router)    │  │   (sarama/segmentio) │  │  (robfig/cron) │  │
│   └─────────┬──────────┘  └──────────┬───────────┘  └───────┬────────┘  │
└─────────────┼──────────────────────────┼─────────────────────┼──────────┘
              │                          │                     │
              ▼                          ▼                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                            API LAYER (Ports)                            │
│  ┌──────────────┐  ┌───────────────┐  ┌──────────────┐  ┌─────────┐ │
│  │   Handlers   │  │   Middleware  │  │    Router    │  │   DTO   │ │
│  │  cart_handler│  │  auth/logging  │  │  Gin router  │  │ request │ │
│  │ health_handle│  │  recovery/rate │  │              │  │ response│ │
│  └──────┬───────┘  └───────────────┘  └──────────────┘  └─────────┘ │
└──────────┼────────────────────────────────────────────────────────────┘
           │
           ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                        DOMAIN LAYER (Core — Pure Logic)                  │
│  ┌────────────────┐  ┌────────────────┐  ┌──────────────────────────┐  │
│  │    Entities     │  │   Services     │  │   Ports (Interfaces)     │  │
│  │  CartItem       │  │  CartService   │  │  CartRepository          │  │
│  │  Cart           │  │                │  │  ProductClient           │  │
│  │  CartStatus     │  │                │  │  CacheRepo               │  │
│  │  CartEvent      │  │                │  │  KafkaPublisher          │  │
│  └────────────────┘  └────────────────┘  └──────────────────────────┘  │
│  ┌────────────────┐  ┌──────────────────────────────────────────────┐  │
│  │  Value Objects │  │              Domain Events                     │  │
│  │  CartItemValue │  │  CartItemAdded / Updated / Deleted            │  │
│  │  PriceInfo     │  │  CartCleared / CheckedOut / Abandoned        │  │
│  └────────────────┘  └──────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
           │
           ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    INFRASTRUCTURE LAYER (Adapters)                      │
│  ┌─────────────┐  ┌──────────────┐  ┌────────────┐  ┌─────────────┐  │
│  │    Redis     │  │  PostgreSQL   │  │    Kafka   │  │   gRPC      │  │
│  │  (go-redis)  │  │    (pgx)      │  │  (sarama)  │  │  (product)  │  │
│  │ Lua Scripts  │  │   Repos       │  │  Producer  │  │  CircuitBrk │  │
│  └─────────────┘  └──────────────┘  └────────────┘  └─────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
```

### 1.2. Luồng Dữ liệu (Data Flow)

```
┌──────────────────────────────────────────────────────────────────────────┐
│                           WRITE PATH                                       │
│                                                                          │
│  HTTP Request                                                           │
│       │                                                                 │
│       ▼                                                                 │
│  ┌─────────────────────────────────────────────────────────────┐        │
│  │  CartHandler.AddItem(ctx, req)                              │        │
│  │    ├── 1. Validate request (DTO)                           │        │
│  │    ├── 2. ProductService.GetProductSku(ctx, skuID)         │        │
│  │    │         (gRPC + Circuit Breaker)                      │        │
│  │    ├── 3. CartService.AddItem(ctx, cartID, item)           │        │
│  │    │         └─→ CartLuaRepository.AddOrUpdateItem()       │        │
│  │    │              Lua Script: HSET + XADD (atomic)         │        │
│  │    │              └──→ Global Stream Shard 0..3            │        │
│  │    └── 4. CacheRepo.DeleteNegativeCache(cartID)            │        │
│  └─────────────────────────────────────────────────────────────┘        │
│       │                                                                 │
│       ▼                                                                 │
│  ┌───────────────┐     ┌──────────────────┐     ┌──────────────────┐   │
│  │ OutboxRelay   │────▶│  Kafka Topic     │────▶│ PostgresSync      │   │
│  │ Worker        │     │  t.cart.events    │     │ Consumer         │   │
│  │ XREADGROUP    │     │  (partition by    │     │ ───┬───────────  │   │
│  │ ────── Kafka  │     │   cart_id)        │     │    │  UPSERT       │   │
│  └───────────────┘     └──────────────────┘     │    ▼              │   │
│                                                 │ PostgreSQL Cold   │   │
│                                                 │ Store (cart_items,│   │
│                                                 │  carts)          │   │
│                                                 └──────────────────┘   │
└──────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────┐
│                           READ PATH (Cache Hit)                            │
│                                                                          │
│  HTTP GET /api/v1/carts/mine                                             │
│       │                                                                 │
│       ▼                                                                 │
│  ┌─────────────────────────────────────────────────────────────┐        │
│  │  CartHandler.GetCart(ctx, userID)                           │        │
│  │    ├── 1. Check negative cache: EXISTS {cart}:{user}:notfound│       │
│  │    ├── 2. HGETALL {cart}:{userID} ──────▶ Redis           │        │
│  │    ├── 3. Decode hash → []CartItemView                     │        │
│  │    ├── 4. ProductService.GetBatchSkus(ctx, skuIDs)          │        │
│  │    │         (gRPC + Circuit Breaker, 200ms timeout)        │        │
│  │    ├── 5. Merge is_available (write back false → Redis HSET)│       │
│  │    └── 6. Return CartResponse with current_price            │        │
│  └─────────────────────────────────────────────────────────────┘        │
└──────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────┐
│                       READ PATH (Cache Miss)                              │
│                                                                          │
│  HTTP GET /api/v1/carts/mine                                             │
│       │                                                                 │
│       ▼                                                                 │
│  ┌─────────────────────────────────────────────────────────────┐        │
│  │  Cache MISS ──▶ Check carts.status in PostgreSQL            │        │
│  │    ├── DELETED/CHECKED_OUT ──▶ Return empty cart            │        │
│  │    ├── ACTIVE ──▶ SELECT cart_items FROM pg                  │        │
│  │    ├── Merge product data via gRPC GetBatchSkus()           │        │
│  │    ├── Rehydrate Lua Script (timestamp guard, no XADD)       │        │
│  │    │         HSET only if Redis field missing or stale      │        │
│  │    └── Return CartResponse + is_price_degraded flag          │        │
│  └─────────────────────────────────────────────────────────────┘        │
└──────────────────────────────────────────────────────────────────────────┘
```

### 1.3. Module Dependency Graph (Thứ tự triển khai bottom-up)

```
MODULE DEPENDENCY ORDERING (bottom → top):

Tier 0: Foundation
─────────────────
  [00-overview-plan]  ← Bạn đang ở đây
         │
         ▼
  [01-project-scaffolding]  go.mod, Makefile, configs/, scripts/
         │
         ▼
Tier 1: Domain Core (Pure logic — NO infrastructure imports)
─────────────────
  [02-domain-models]     CartItem, Cart, CartStatus, errors
         │
         ├──────────────────────┐
         ▼                      ▼
  [04-domain-events]          [03-domain-ports]
  CartEvent, payloads         Interfaces: CartRepo, ProductClient,
                              CacheRepo, KafkaPublisher
         │                      │
         └──────────┬───────────┘
                    ▼
Tier 2: Infrastructure Adapters (implement domain ports)
─────────────────
  [05-lua-scripts]         Lua source (standalone, embed-only)
  [06-infra-redis]         go-redis client + Lua wrappers
  [07-infra-postgres]      pgx connection + GORM repos
  [08-infra-kafka]         sarama producer/consumer
  [09-infra-grpc]          gRPC client + gobreaker
  [10-infra-config]        viper config loading
         │
         ├──────────────────────────────┐
         ▼                              ▼
Tier 3: Domain Services (orchestrate domain + infra)
─────────────────
  [11-domain-services]    CartService (AddItem, GetCart, BatchDelete)
         │
         ▼
Tier 4: API Layer (HTTP entry points)
─────────────────
  [12-api-dto]             Request/Response DTOs
  [13-api-middleware]      auth, logging, recovery, ratelimit
  [14-api-handlers]        Gin handlers + Router
         │
         ▼
Tier 5: Background Workers (async processing)
─────────────────
  [15-21-workers]
    ├── 15-outbox-relay-worker.md
    ├── 16-postgres-sync-worker.md
    ├── 17-abandoned-cart-marker.md
    ├── 18-data-archival-cleaner.md
    ├── 19-stream-trimmer.md
    └── 20-order-event-consumer.md
         │
         ▼
Tier 6: Application Bootstrap
─────────────────
  [21-cmd-main]           main.go, DI wiring, graceful shutdown
         │
         ▼
Tier 7: Cross-Cutting Concerns
─────────────────
  [22-testing-plan]       Unit/Integration/Load test strategy
  [23-deployment-plan]    Dockerfile, docker-compose, K8s manifests
```

---

## 2. Cây Thư Mục Go (Dự kiến)

Tuân thủ [golang-common-layout.md](../../common/golang/golang-common-layout.md) — Layered + Hexagonal Architecture:

```
cart-service/
├── cmd/
│   └── server/
│       └── main.go                     # Entry point, DI wiring
│
├── internal/
│   ├── api/                            # API Layer
│   │   ├── handler/
│   │   │   ├── cart_handler.go         # Cart CRUD handlers
│   │   │   └── health_handler.go       # Health/liveness checks
│   │   ├── middleware/
│   │   │   ├── auth.go                 # X-User-Id extraction
│   │   │   ├── logging.go              # Request/response logging
│   │   │   ├── recovery.go             # Panic recovery
│   │   │   ├── ratelimit.go            # Rate limiting
│   │   │   └── tracing.go              # OpenTelemetry tracing
│   │   ├── router/
│   │   │   └── router.go               # Gin route definitions
│   │   └── dto/
│   │       ├── request/
│   │       │   ├── add_item.go         # POST /carts/items
│   │       │   ├── batch_delete.go     # DELETE /carts/items
│   │       │   └── get_cart.go        # GET /carts/mine (nil body)
│   │       └── response/
│   │           ├── cart.go             # Cart response
│   │           ├── cart_item.go        # Item response
│   │           └── error.go            # Error response
│   │
│   ├── domain/                         # Domain Layer (CORE)
│   │   ├── entity/
│   │   │   ├── cart.go                # Cart aggregate root
│   │   │   ├── cart_item.go           # CartItem entity
│   │   │   └── errors.go              # Domain errors
│   │   ├── service/
│   │   │   └── cart_service.go        # Cart business logic
│   │   ├── port/                      # Interfaces (contracts)
│   │   │   ├── cart_repository.go      # Cart data access
│   │   │   ├── cart_lua_repo.go       # Lua script executor
│   │   │   ├── cache_repo.go          # Cache operations
│   │   │   ├── product_client.go      # Product gRPC client
│   │   │   └── kafka_publisher.go     # Kafka producer
│   │   └── event/
│   │       └── cart_events.go         # Event type constants + structs
│   │
│   ├── infrastructure/                 # Infrastructure Adapters
│   │   ├── redis/
│   │   │   ├── connection.go          # go-redis client setup
│   │   │   ├── cart_lua_repo.go      # Lua script invocations
│   │   │   ├── cache_repo.go         # Negative cache + hash ops
│   │   │   └── scripts/
│   │   │       ├── add_or_update.lua # Add/Update + XADD
│   │   │       ├── batch_delete.lua   # Batch HDEL + XADD
│   │   │       └── rehydrate.lua      # Silent rehydrate (no XADD)
│   │   ├── postgres/
│   │   │   ├── connection.go          # pgx connection pool
│   │   │   ├── cart_repo.go          # CartRepository impl
│   │   │   ├── cart_item_repo.go     # CartItemRepository impl
│   │   │   └── migrations/
│   │   │       ├── 001_initial_schema.up.sql
│   │   │       └── 001_initial_schema.down.sql
│   │   ├── kafka/
│   │   │   ├── config.go             # Kafka config
│   │   │   ├── producer.go           # Kafka producer
│   │   │   ├── consumer.go          # Kafka consumer base
│   │   │   └── deserializer.go      # JSON deserialization
│   │   ├── grpc/
│   │   │   ├── product_client.go    # gRPC ProductService client
│   │   │   └── circuit_breaker.go   # gobreaker wrapper
│   │   └── config/
│   │       └── config.go             # Viper config struct
│   │
│   ├── workers/                       # Background Workers
│   │   ├── outbox_relay/
│   │   │   ├── worker.go            # Per-shard relay goroutines
│   │   │   ├── consumer.go          # XREADGROUP consumer
│   │   │   ├── producer.go          # Kafka producer wrapper
│   │   │   └── metrics.go           # Prometheus metrics
│   │   ├── postgres_sync/
│   │   │   ├── worker.go           # Kafka consumer + batch processor
│   │   │   ├── batch_processor.go  # Per-cart transaction
│   │   │   └── idempotency.go      # Stream ID guard logic
│   │   ├── abandoned_marker/
│   │   │   ├── job.go              # Cronjob definition
│   │   │   └── publisher.go        # XADD CART_ABANDONED events
│   │   ├── archival_cleaner/
│   │   │   └── job.go              # Hard delete cronjob
│   │   ├── stream_trimmer/
│   │   │   └── job.go              # XTRIM periodic trimmer
│   │   └── order_event_consumer/
│   │       ├── worker.go           # ORDER_PAID consumer
│   │       └── idempotency.go      # order_paid idempotency
│   │
│   └── common/                       # Shared utilities
│       ├── constants/
│       │   └── constants.go         # Redis key prefixes, TTLs, limits
│       ├── errors/
│       │   └── errors.go            # App-level errors (ErrCartFull, etc.)
│       ├── keybuilder/
│       │   └── keybuilder.go       # Redis key construction (hash tags)
│       ├── circuitbreaker/
│       │   └── circuitbreaker.go   # Configurable breaker per service
│       └── logging/
│           └── logger.go            # zap logger setup
│
├── configs/
│   ├── application.yaml             # Main configuration
│   └── .env.example                 # Env variable template
│
├── scripts/
│   └── standalone_worker.go        # Standalone cronjob binary (optional)
│
├── test/
│   ├── mocks/
│   │   ├── cart_repo_mock.go
│   │   ├── cart_lua_repo_mock.go
│   │   ├── product_client_mock.go
│   │   └── kafka_publisher_mock.go
│   ├── fixtures/
│   │   └── cart_fixtures.go        # Test data factories
│   └── integration/
│       └── cart_integration_test.go
│
├── go.mod
├── go.sum
├── Dockerfile
├── Makefile
├── docker-compose.yaml
└── README.md
```

---

## 3. Danh sách Tất cả Module (Thứ tự triển khai)

| # | File | Mô tả | Phụ thuộc | Output artifact |
|---|------|--------|-----------|----------------|
| 01 | `01-project-scaffolding.md` | go.mod, Makefile, Dockerfile, docker-compose, cấu trúc thư mục rỗng | - | Project skeleton |
| 02 | `02-domain-models.md` | Cart, CartItem entities; CartStatus enum; domain errors | - | `internal/domain/entity/` |
| 03 | `03-domain-ports.md` | Tất cả interfaces: CartRepository, CartLuaRepository, CacheRepo, ProductClient, KafkaPublisher | 02 | `internal/domain/port/` |
| 04 | `04-domain-events.md` | Event type constants + event structs (Kafka headers, payload) | 02 | `internal/domain/event/` |
| 05 | `05-lua-scripts.md` | 3 Lua scripts: add_or_update, batch_delete, rehydrate | - (embed only) | `internal/infrastructure/redis/scripts/` |
| 06 | `06-infra-redis.md` | go-redis connection, CartLuaRepo impl, CacheRepo impl | 03, 04, 05 | `internal/infrastructure/redis/` |
| 07 | `07-infra-postgres.md` | pgx connection, CartRepo impl, CartItemRepo impl, migrations | 03 | `internal/infrastructure/postgres/` |
| 08 | `08-infra-kafka.md` | Kafka producer, consumer base, deserializer | 04 | `internal/infrastructure/kafka/` |
| 09 | `09-infra-grpc.md` | gRPC product client, gobreaker circuit breaker | 03 | `internal/infrastructure/grpc/` |
| 10 | `10-infra-config.md` | Viper config loading, env override, config struct | - | `internal/infrastructure/config/` |
| 11 | `11-domain-services.md` | CartService — AddItem, GetCart, BatchDelete, Rehydrate | 02, 03, 06, 09 | `internal/domain/service/` |
| 12 | `12-api-dto.md` | Request DTOs + Response DTOs + validators | 02, 04 | `internal/api/dto/` |
| 13 | `13-api-middleware.md` | Auth, logging, recovery, rate-limit, tracing middleware | - | `internal/api/middleware/` |
| 14 | `14-api-handlers.md` | Gin handlers + router setup | 11, 12, 13 | `internal/api/handler/`, `router/` |
| 15 | `15-21-workers.md` | Tất cả 6 workers: OutboxRelay, PostgresSync, AbandonedMarker, ArchivalCleaner, StreamTrimmer, OrderEventConsumer | 03, 06, 07, 08, 09, 10, 11 | `internal/workers/` |
| 16 | `22-cmd-main.md` | main.go, DI wiring, graceful shutdown, server bootstrap | Tất cả trên | `cmd/server/main.go` |
| 17 | `23-testing-plan.md` | Unit test, integration test (testcontainers), load test, health checks | Tất cả trên | `test/`, CI configs |
| 18 | `24-deployment-plan.md` | Dockerfile (distroless), docker-compose, K8s manifests, HPA | 16, 17 | `deployments/`, `Dockerfile` |

---

## 4. Nguyên tắc Phân tách Module

### 4.1. Dependency Rules (Nghiêm ngặt)

```
Cấm tuyệt đối:
  ✗ domain/entity/     → infrastructure/
  ✗ domain/port/        → infrastructure/
  ✗ domain/service/     → infrastructure/
  ✗ common/             → domain/
  ✗ cmd/                → workers/ (ngược)

Cho phép:
  ✓ domain/entity/      → domain/port/ (interfaces)
  ✓ domain/service/     → domain/entity/ + domain/port/ + common/
  ✓ infrastructure/     → domain/port/ (implement interfaces)
  ✓ api/handler/        → domain/service/ + api/dto/ + common/
  ✓ workers/             → domain/ + infrastructure/ + common/
  ✓ common/              → chỉ stdlib
```

### 4.2. Lý do sắp xếp thứ tự

| Module | Lý do xếp ở bước Này |
|--------|------------------------|
| **01-project-scaffolding** | Tạo nền tảng — không phụ thuộc gì cả. Tạo thư mục rỗng để các module sau fill in. |
| **02-domain-models** | Domain entities là nền tảng logic thuần túy — không import gì infra hết. |
| **03-domain-ports** | Định nghĩa contracts (interfaces) sau khi đã biết domain model. Không implement, chỉ khai báo. |
| **04-domain-events** | Event structs dùng domain models, độc lập infra. |
| **05-lua-scripts** | Chỉ là text Lua — embed vào binary, không import Go nào. |
| **06-10-infra-*** | Implement domain ports. Tuỳ thứ tự: Redis trước vì workers cần; Config cuối vì chỉ là struct data. |
| **11-domain-services** | Gom business logic sau khi có cả domain models lẫn port interfaces. |
| **12-14-api-*** | API layer sau domain service. Không có service thì handler không có gì để gọi. |
| **15-21-workers** | Workers cần TẤT CẢ: infra clients (Redis, Kafka, PG), domain services, domain events, config. |
| **22-cmd-main** | Wiring everything together — phụ thuộc toàn bộ. |
| **23-24 (test + deploy)** | Cross-cutting — không ảnh hưởng code, chỉ ảnh hưởng operation. |

### 4.3. Mỗi Module chỉ mô tả ở một file

| Module | Chỉ mô tả chi tiết tại |
|--------|------------------------|
| Redis Hash structure | `02-domain-models.md` (domain perspective) |
| Lua script logic | `05-lua-scripts.md` (chỉ code Lua) |
| Lua invocation in Go | `06-infra-redis.md` (chỉ Go wrapper) |
| Kafka message format | `04-domain-events.md` (event structs) |
| Kafka producer/consumer | `08-infra-kafka.md` (infra impl) |
| Event processing logic | `11-domain-services.md` (business logic) |
| Worker that reads Kafka | `15-21-workers.md` (Worker #2, #6) |
| Worker that writes Redis | `15-21-workers.md` (Worker #1, #3) |
| Worker that writes PG | `15-21-workers.md` (Worker #2, #4) |

---

## 5. Tech Stack Tổng hợp

| Component | Thư viện | Phiên bản đề xuất | Purpose |
|-----------|-----------|-------------------|---------|
| HTTP Framework | `github.com/gin-gonic/gin` | v1.9+ | HTTP routing, middleware |
| Redis Client | `github.com/redis/go-redis/v9` | v9.5+ | Redis operations, Lua scripts, Streams |
| PostgreSQL Driver | `github.com/jackc/pgx/v5` + `gorm.io/gorm` | pgx v5, GORM v2 | DB connection + ORM |
| Kafka Client | `github.com/IBM/sarama` | v1.43+ | Kafka producer/consumer |
| gRPC | `google.golang.org/grpc` + `google.golang.org/protobuf` | v1.60+ | Inter-service communication |
| Circuit Breaker | `github.com/sony/gobreaker` | v2+ | Fault tolerance |
| Config | `github.com/spf13/viper` | v1.18+ | Configuration management |
| Logging | `go.uber.org/zap` | v1.26+ | Structured logging |
| UUID | `github.com/google/uuid` | v4+ | ID generation |
| Cron Scheduler | `github.com/robfig/cron/v3` | v3+ | Scheduled jobs |
| Graceful Shutdown | `context` + `net/http` | stdlib | HTTP shutdown |
| Health Checks | `github.com/grpc-ecosystem/grpc-health-probe` | - | k8s health probes |
| Docker | distroless/static | - | Minimal image |
| Test Containers | `github.com/testcontainers/testcontainers-go` | - | Integration testing |

---

## 6. Triển khai từng bước (Execution Checklist)

```markdown
## Phase 1: Foundation (Tuần 1)
- [x] 01-project-scaffolding.md  → Tạo project skeleton, go.mod, Makefile ✅ (2026-05-24)
- [ ] 02-domain-models.md        → Entities, errors
- [ ] 03-domain-ports.md         → Interfaces
- [ ] 04-domain-events.md        → Event definitions

## Phase 2: Lua Scripts (Tuần 1-2)
- [ ] 05-lua-scripts.md          → 3 Lua scripts (add_or_update, batch_delete, rehydrate)

## Phase 3: Infrastructure (Tuần 2)
- [ ] 06-infra-redis.md          → Redis client + Lua wrappers
- [ ] 07-infra-postgres.md       → PG connection + repos + migrations
- [ ] 08-infra-kafka.md          → Kafka producer/consumer
- [ ] 09-infra-grpc.md           → gRPC client + circuit breaker
- [ ] 10-infra-config.md         → Config loading

## Phase 4: Business Logic (Tuần 2-3)
- [ ] 11-domain-services.md      → CartService (AddItem, GetCart, BatchDelete, Rehydrate)

## Phase 5: API Layer (Tuần 3)
- [ ] 12-api-dto.md              → DTOs
- [ ] 13-api-middleware.md        → Middleware stack
- [ ] 14-api-handlers.md         → Handlers + Router

## Phase 6: Workers (Tuần 3-4)
- [ ] 15-21-workers.md            → All 6 workers

## Phase 7: Bootstrap + QA (Tuần 4)
- [ ] 22-cmd-main.md             → main.go + wiring
- [ ] 23-testing-plan.md          → Test strategy + test utilities
- [ ] 24-deployment-plan.md        → Docker + K8s

## Phase 8: Integration & Rollout (Tuần 5)
- [ ] Run unit tests: Lua scripts (miniredis), domain services
- [ ] Run integration tests: Redis, Kafka, PostgreSQL (testcontainers)
- [ ] Load test: wrk/k6 on Cart API
- [ ] Deploy to staging: kubectl apply
- [ ] Smoke test: health checks, add/cart/get/delete flows
```

---

## 7. Rủi ro và Giải pháp

| Rủi ro | Mức độ | Giải pháp |
|--------|---------|-----------|
| Lua script CROSSSLOT error | Cao | Bắt buộc Hash Tag `{cart}` trong tất cả Redis keys |
| Kafka consumer lag tăng | Cao | HPA scale consumer theo lag metric; batch processing |
| Redis eviction mất data | Cao | PostgreSQL là cold store; TTL 7 ngày; rehydrate pipeline |
| Out-of-order event đè data | Trung | `stream_id` guard trong tất cả Upsert SQL |
| Circuit breaker mở liên tục | Trung | Half-open probe; fallback snapshot; degraded flag |
| Worker crash mất pending msg | Trung | XAUTOCLAIM recovery; Consumer Group pending tracking |
| Shard hot (skewed traffic) | Thấp | CRC32 hash đủ đều; monitor per-shard lag |

---

*Hãy bắt đầu với [01-project-scaffolding.md](01-project-scaffolding.md) để tạo project skeleton.*
