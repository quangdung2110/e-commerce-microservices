# Go Microservices - Common Layout Design

> **Document Version:** 1.0  
> **Date:** May 17, 2026  
> **Status:** Proposed Standard  
> **Applies To:** All Golang microservices (auth-service, cart-service, user-service, inventory-service, etc.)

---

## Table of Contents

1. [Problem Statement](#1-problem-statement)
2. [Design Principles](#2-design-principles)
3. [Proposed Standard Layout](#3-proposed-standard-layout)
4. [Directory Details](#4-directory-details)
5. [Comparison: Current vs Proposed](#5-comparison-current-vs-proposed)
6. [Migration Guide](#6-migration-guide)
7. [Code Examples](#7-code-examples)
8. [Best Practices Checklist](#8-best-practices-checklist)

---

## 1. Problem Statement

### Current State

Các service hiện tại có cấu trúc thư mục khác nhau, gây khó khăn cho:

| Issue | Impact |
|-------|--------|
| Khó onboarding developer mới | Phải học cấu trúc riêng của từng service |
| Khó di chuyển code giữa services | Logic chung bị duplicate |
| Khó debug và maintain | Không có convention thống nhất |
| Tốn thời gian code review | Mỗi PR có style khác nhau |

### Example: Structure Differences

```
cart-service:                      user-service:
├── cmd/server/main.go            ├── cmd/server/main.go
├── internal/api/                  ├── internal/api/
│   ├── handlers.go               │   ├── handler/
│   ├── router.go                 │   │   ├── profile_handler.go
│   ├── middleware.go             │   │   ├── address_handler.go
│   └── product_client.go         │   │   └── preferences_handler.go
├── internal/workers/             │   ├── middleware/
│   ├── outbox_relay/             │   ├── router/
│   ├── postgres_sync/            │   └── dto/
│   └── cronjobs/                 ├── internal/domain/
├── internal/redis/               │   ├── entity/
├── internal/common/              │   ├── service/
│   ├── constants/                │   ├── port/
│   ├── keybuilder/               │   ├── vo/
│   ├── circuitbreaker/           │   └── event/
│   └── event/                    ├── internal/infrastructure/
└── internal/config/                 │   ├── postgres/
                                      │   ├── redis/
                                      │   └── kafka/
                                      └── internal/kafka/
```

---

## 2. Design Principles

### 2.1 Architecture Pattern

Sử dụng kết hợp **Layered Architecture** và **Hexagonal Architecture (Ports & Adapters)**:

```
┌─────────────────────────────────────────────────────────────────┐
│                         ENTRY POINTS                            │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐            │
│  │ HTTP Server │  │ gRPC Server │  │ Kafka Worker│            │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘            │
└─────────┼─────────────────┼─────────────────┼───────────────────┘
          │                 │                 │
          ▼                 ▼                 ▼
┌─────────────────────────────────────────────────────────────────┐
│                       API LAYER (Ports)                         │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │ Handlers │ Middleware │ Router │ DTO │ Validators      │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────────┐
│                     DOMAIN LAYER (Core)                         │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐            │
│  │  Entities   │  │  Services   │  │   Ports     │            │
│  │ (Models)    │  │ (Use Cases) │  │(Interfaces) │            │
│  └─────────────┘  └─────────────┘  └─────────────┘            │
│  ┌─────────────┐  ┌─────────────┐                              │
│  │Value Objects│  │   Events    │                              │
│  └─────────────┘  └─────────────┘                              │
└─────────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────────┐
│                  INFRASTRUCTURE LAYER (Adapters)                │
│  ┌───────────┐  ┌───────────┐  ┌───────────┐  ┌───────────┐   │
│  │ Postgres  │  │   Redis   │  │   Kafka   │  │  gRPC     │   │
│  │ Repository│  │ Repository│  │ Publisher │  │  Client   │   │
│  └───────────┘  └───────────┘  └───────────┘  └───────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 Key Principles

| Principle | Description |
|-----------|-------------|
| **Structure before Framework** | Business logic không phụ thuộc vào HTTP, Redis, Kafka |
| **Interface as Contract** | Dùng interfaces để define contracts, implementations ở infrastructure |
| **Dependency Inversion** | Domain không biết gì về infrastructure |
| **Single Responsibility** | Mỗi package chỉ làm một việc |
| **Predictable Structure** | Developer biết file ở đâu khi cần |

### 2.3 Dependency Rules

```
cmd/           → internal/
internal/api/  → internal/domain/, internal/common/
internal/domain/ → internal/common/ (KHÔNG ngược lại)
internal/infrastructure/ → internal/domain/ (implemented interfaces)
internal/workers/ → internal/domain/, internal/infrastructure/, internal/common/
```

---

## 3. Proposed Standard Layout

### 3.1 Complete Directory Structure

```
{service-name}/
├── cmd/                          # Entry points (ONE per binary)
│   └── server/
│       └── main.go               # Application entry point
│
├── internal/                      # Private application code
│   ├── api/                      # API layer (HTTP/gRPC handlers)
│   │   ├── handler/              # Request handlers (ONE file per entity)
│   │   │   ├── profile_handler.go
│   │   │   ├── address_handler.go
│   │   │   └── health_handler.go
│   │   ├── middleware/            # HTTP middleware
│   │   │   ├── auth.go
│   │   │   ├── logging.go
│   │   │   ├── recovery.go
│   │   │   ├── ratelimit.go
│   │   │   └── tracing.go
│   │   ├── router/               # Route definitions
│   │   │   └── router.go
│   │   ├── dto/                  # Data Transfer Objects
│   │   │   ├── request/         # Incoming requests
│   │   │   │   ├── create_profile.go
│   │   │   │   └── update_profile.go
│   │   │   └── response/        # Outgoing responses
│   │   │       ├── profile.go
│   │   │       └── error.go
│   │   └── validator/            # Request validators
│   │       └── validator.go
│   │
│   ├── domain/                   # Domain layer (CORE BUSINESS LOGIC)
│   │   ├── entity/              # Domain entities (ONE file per aggregate)
│   │   │   ├── profile.go
│   │   │   ├── address.go
│   │   │   └── errors.go
│   │   ├── service/              # Domain services (use cases)
│   │   │   ├── profile_service.go
│   │   │   └── address_service.go
│   │   ├── port/                 # Interfaces (ports in hexagonal)
│   │   │   ├── profile_repository.go
│   │   │   ├── address_repository.go
│   │   │   ├── cache.go
│   │   │   └── publisher.go
│   │   ├── vo/                   # Value Objects
│   │   │   ├── phone.go
│   │   │   └── email.go
│   │   └── event/                # Domain events
│   │       ├── profile_events.go
│   │       └── domain_events.go
│   │
│   ├── infrastructure/            # Infrastructure adapters
│   │   ├── postgres/            # PostgreSQL repositories
│   │   │   ├── connection.go    # DB connection management
│   │   │   ├── profile_repo.go  # Implementation of port.ProfileRepository
│   │   │   ├── address_repo.go
│   │   │   └── migrations/      # SQL migrations (optional)
│   │   │       ├── 001_create_profiles.up.sql
│   │   │       └── 001_create_profiles.down.sql
│   │   ├── redis/               # Redis cache
│   │   │   ├── connection.go
│   │   │   └── cache_repo.go
│   │   ├── kafka/               # Kafka consumers/producers
│   │   │   ├── config.go
│   │   │   ├── consumer.go
│   │   │   ├── producer.go
│   │   │   └── handler/         # Event handlers
│   │   │       └── user_event_handler.go
│   │   └── grpc/                # gRPC clients (external services)
│   │       └── product_client.go
│   │
│   ├── workers/                  # Background workers (ONE dir per worker type)
│   │   ├── outbox_relay/        # Outbox pattern relay worker
│   │   │   ├── worker.go
│   │   │   ├── consumer.go
│   │   │   ├── producer.go
│   │   │   └── metrics.go
│   │   ├── sync_worker/         # Database sync worker
│   │   │   ├── worker.go
│   │   │   └── batch_processor.go
│   │   └── cronjobs/            # Scheduled jobs
│   │       ├── abandoned_marker.go
│   │       └── archival_cleaner.go
│   │
│   └── common/                   # Shared utilities (service-agnostic)
│       ├── constants/            # Magic numbers and strings
│       │   └── constants.go
│       ├── errors/               # Shared error types
│       │   └── errors.go
│       ├── keybuilder/           # Redis key builders
│       │   └── keybuilder.go
│       ├── circuitbreaker/       # Circuit breaker pattern
│       │   └── circuitbreaker.go
│       ├── event/                # Event building utilities
│       │   └── event_builder.go
│       └── validator/            # Cross-cutting validators
│           └── validator.go
│
├── configs/                      # Configuration files
│   ├── application.yaml          # Main config
│   └── .env.example             # Environment template
│
├── scripts/                      # Operational scripts
│   └── compliance-worker.go     # Standalone workers
│
├── migrations/                   # Database migrations (if not in infrastructure)
│   └── 001_initial_schema.up.sql
│
├── test/                         # Test utilities and mocks
│   ├── mocks/
│   │   ├── profile_repo_mock.go
│   │   └── cache_mock.go
│   └── integration/
│       └── integration_test.go
│
├── go.mod                        # Module definition
├── go.sum                        # Dependencies checksum
├── Dockerfile                    # Container image
├── Makefile                      # Build automation
├── docker-compose.yaml           # Local development
└── README.md                     # Service documentation
```

### 3.2 Naming Conventions

| Element | Convention | Example |
|---------|------------|---------|
| Directory | lowercase, snake_case | `outbox_relay`, `profile_service` |
| File | lowercase, snake_case | `profile_handler.go`, `cache_repo.go` |
| Package | lowercase, single word or snake_case | `handler`, `profile_service` |
| Type/Struct | PascalCase | `ProfileHandler`, `ProfileRepository` |
| Interface | PascalCase, ends with er or Interface | `ProfileRepository`, `CacheInterface` |
| Function | PascalCase | `NewProfileService`, `GetProfile` |
| Variable | camelCase | `profileID`, `userID` |
| Constant | PascalCase or SCREAMING_SNAKE | `MaxRetries` or `MAX_RETRIES` |
| Test file | same as source + `_test.go` | `profile_handler_test.go` |

---

## 4. Directory Details

### 4.1 `cmd/` - Entry Points

**Purpose:** Chứa entry points cho các binaries. Mỗi binary có một thư mục riêng.

```
cmd/
└── server/
    └── main.go           # Khởi tạo ứng dụng, wiring dependencies
```

**Rules:**
- Mỗi `main.go` chỉ làm: đọc config, khởi tạo dependencies, start server
- KHÔNG có business logic ở đây
- Có thể có `wire.go` để dependency injection (tuỳ chọn)

**Example `main.go`:**

```go
func main() {
    cfg := config.Load()
    logger := logging.New(cfg.Logging)
    
    // Infrastructure
    db := postgres.NewConnection(cfg.Database)
    redis := redis.NewClient(cfg.Redis)
    kafkaProducer := kafka.NewProducer(cfg.Kafka)
    
    // Repositories (implementations)
    profileRepo := postgres.NewProfileRepository(db)
    cache := redis.NewCache(redis)
    
    // Domain services
    profileService := domain.NewProfileService(profileRepo, cache)
    
    // Handlers
    profileHandler := api.NewProfileHandler(profileService)
    
    // Router
    router := api.NewRouter(profileHandler, cfg)
    
    // Start server
    server := http.NewServer(cfg.Server, router)
    server.Start()
}
```

### 4.2 `internal/api/` - API Layer

**Purpose:** Xử lý HTTP/gRPC requests và responses.

#### 4.2.1 `handler/` - Request Handlers

**ONE file per entity/aggregate:**

```
handler/
├── profile_handler.go      # Profile-related endpoints
├── address_handler.go      # Address-related endpoints
└── health_handler.go       # Health check endpoints
```

**Pattern: Handler Struct**

```go
type ProfileHandler struct {
    service *domain.ProfileService
    logger  *logging.Logger
}

func NewProfileHandler(service *domain.ProfileService, logger *logging.Logger) *ProfileHandler {
    return &ProfileHandler{
        service: service,
        logger:  logger,
    }
}

// Mỗi handler method nhận context và trả về error
func (h *ProfileHandler) CreateProfile(ctx context.Context, req *dto.CreateProfileRequest) (*dto.ProfileResponse, error) {
    // Validate request
    if err := validator.Validate(req); err != nil {
        return nil, err
    }
    
    // Call domain service
    profile, err := h.service.CreateProfile(ctx, req.ToEntity())
    if err != nil {
        return nil, err
    }
    
    return dto.NewProfileResponse(profile), nil
}
```

#### 4.2.2 `middleware/` - HTTP Middleware

```
middleware/
├── auth.go         # Authentication
├── logging.go      # Request/response logging
├── recovery.go     # Panic recovery
├── ratelimit.go    # Rate limiting
└── tracing.go      # Distributed tracing
```

**Middleware Chain Pattern:**

```go
func NewMiddlewareStack(handler http.Handler) http.Handler {
    return middleware.Chain(
        middleware.Recovery(),
        middleware.Logging(),
        middleware.Tracing(),
        middleware.Auth(),
        handler,
    )
}
```

#### 4.2.3 `dto/` - Data Transfer Objects

```
dto/
├── request/
│   ├── create_profile.go
│   └── update_profile.go
└── response/
    ├── profile.go
    └── error.go
```

**Request DTO Pattern:**

```go
type CreateProfileRequest struct {
    FirstName string `json:"first_name" validate:"required,max=100"`
    LastName  string `json:"last_name" validate:"required,max=100"`
    Email     string `json:"email" validate:"required,email"`
}

func (r *CreateProfileRequest) ToEntity() *entity.Profile {
    return &entity.Profile{
        FirstName: r.FirstName,
        LastName:  r.LastName,
        Email:     r.Email,
    }
}
```

**Response DTO Pattern:**

```go
type ProfileResponse struct {
    ID        string    `json:"id"`
    FirstName string    `json:"first_name"`
    LastName  string    `json:"last_name"`
    Email     string    `json:"email"`
    CreatedAt time.Time `json:"created_at"`
}

func NewProfileResponse(profile *entity.Profile) *ProfileResponse {
    return &ProfileResponse{
        ID:        profile.ID.String(),
        FirstName: profile.FirstName,
        LastName:  profile.LastName,
        Email:     profile.Email,
        CreatedAt: profile.CreatedAt,
    }
}
```

### 4.3 `internal/domain/` - Domain Layer

**Purpose:** Chứa pure business logic, KHÔNG phụ thuộc vào infrastructure.

#### 4.3.1 `entity/` - Domain Entities

**ONE file per aggregate root:**

```go
// entity/profile.go
type Profile struct {
    id        uuid.UUID
    firstName string
    lastName  string
    email     Email
    createdAt time.Time
    updatedAt time.Time
}

// Constructor với validation
func NewProfile(firstName, lastName string, email Email) (*Profile, error) {
    if firstName == "" {
        return nil, ErrInvalidFirstName
    }
    return &Profile{
        id:        uuid.New(),
        firstName: firstName,
        lastName:  lastName,
        email:     email,
        createdAt: time.Now(),
    }, nil
}

// Business methods
func (p *Profile) UpdateName(firstName, lastName string) error {
    if firstName == "" {
        return ErrInvalidFirstName
    }
    p.firstName = firstName
    p.lastName = lastName
    p.updatedAt = time.Now()
    return nil
}
```

#### 4.3.2 `port/` - Interface Definitions (Ports)

**Define interfaces ở domain, implement ở infrastructure:**

```
port/
├── profile_repository.go    # Profile data access
├── address_repository.go    # Address data access
├── cache.go                 # Cache operations
├── publisher.go             # Event publishing
└── outbox.go                # Outbox pattern
```

**Port Interface Pattern:**

```go
// port/profile_repository.go
package port

type ProfileRepository interface {
    // Query methods
    GetByID(ctx context.Context, id uuid.UUID) (*entity.Profile, error)
    GetByEmail(ctx context.Context, email string) (*entity.Profile, error)
    List(ctx context.Context, limit, offset int) ([]*entity.Profile, error)
    
    // Command methods
    Create(ctx context.Context, profile *entity.Profile) error
    Update(ctx context.Context, profile *entity.Profile) error
    Delete(ctx context.Context, id uuid.UUID) error
}

// port/cache.go
type Cache interface {
    Get(ctx context.Context, key string) ([]byte, error)
    Set(ctx context.Context, key string, value []byte, ttl time.Duration) error
    Delete(ctx context.Context, key string) error
}
```

#### 4.3.3 `service/` - Domain Services (Use Cases)

**Mỗi service xử lý một bounded context:**

```go
// service/profile_service.go
type ProfileService struct {
    repo  port.ProfileRepository
    cache port.Cache
    evt   port.EventPublisher
}

func NewProfileService(repo port.ProfileRepository, cache port.Cache, evt port.EventPublisher) *ProfileService {
    return &ProfileService{
        repo: repo,
        cache: cache,
        evt: evt,
    }
}

func (s *ProfileService) CreateProfile(ctx context.Context, req *dto.CreateProfileRequest) (*entity.Profile, error) {
    // 1. Check if email already exists
    existing, err := s.repo.GetByEmail(ctx, req.Email)
    if err != nil && err != port.ErrNotFound {
        return nil, err
    }
    if existing != nil {
        return nil, ErrEmailAlreadyExists
    }
    
    // 2. Create entity
    profile, err := entity.NewProfile(req.FirstName, req.LastName, req.Email)
    if err != nil {
        return nil, err
    }
    
    // 3. Persist
    if err := s.repo.Create(ctx, profile); err != nil {
        return nil, err
    }
    
    // 4. Publish event
    if err := s.evt.Publish(ctx, events.NewProfileCreated(profile)); err != nil {
        // Log error but don't fail - event can be retried
        s.logError(err)
    }
    
    return profile, nil
}
```

#### 4.3.4 `event/` - Domain Events

```go
// event/profile_events.go
type ProfileCreated struct {
    EventBase
    ProfileID uuid.UUID `json:"profile_id"`
    Email     string    `json:"email"`
}

func NewProfileCreated(profile *entity.Profile) *ProfileCreated {
    return &ProfileCreated{
        EventBase: NewEventBase("PROFILE_CREATED", profile.ID.String()),
        ProfileID: profile.ID,
        Email:     profile.Email.String(),
    }
}
```

### 4.4 `internal/infrastructure/` - Infrastructure Adapters

**Purpose:** Implement các interfaces định nghĩa ở `domain/port/`.

#### 4.4.1 `postgres/` - PostgreSQL Implementation

```
postgres/
├── connection.go           # DB connection pool
├── profile_repo.go         # Implements port.ProfileRepository
├── address_repo.go
├── outbox_repo.go          # Outbox pattern implementation
└── migrations/            # Optional: SQL files
```

**Repository Implementation Pattern:**

```go
// postgres/profile_repo.go
type ProfileRepository struct {
    db *gorm.DB
}

func NewProfileRepository(db *gorm.DB) port.ProfileRepository {
    return &ProfileRepository{db: db}
}

func (r *ProfileRepository) GetByID(ctx context.Context, id uuid.UUID) (*entity.Profile, error) {
    var model ProfileModel
    if err := r.db.WithContext(ctx).First(&model, "id = ?", id).Error; err != nil {
        if errors.Is(err, gorm.ErrRecordNotFound) {
            return nil, port.ErrNotFound
        }
        return nil, err
    }
    return model.ToEntity(), nil
}

func (r *ProfileRepository) Create(ctx context.Context, profile *entity.Profile) error {
    model := NewProfileModel(profile)
    return r.db.WithContext(ctx).Create(model).Error
}

// Model (ORM representation)
type ProfileModel struct {
    ID        uuid.UUID `gorm:"type:uuid;primaryKey"`
    FirstName string    `gorm:"size:100;not null"`
    LastName  string    `gorm:"size:100;not null"`
    Email     string    `gorm:"size:255;uniqueIndex;not null"`
    CreatedAt time.Time
    UpdatedAt time.Time
}

func (m *ProfileModel) ToEntity() *entity.Profile {
    return &entity.Profile{
        ID:        m.ID,
        FirstName: m.FirstName,
        LastName:  m.LastName,
        Email:     entity.Email(m.Email),
        CreatedAt: m.CreatedAt,
        UpdatedAt: m.UpdatedAt,
    }
}

func NewProfileModel(p *entity.Profile) *ProfileModel {
    return &ProfileModel{
        ID:        p.ID,
        FirstName: p.FirstName,
        LastName:  p.LastName,
        Email:     p.Email.String(),
    }
}
```

#### 4.4.2 `redis/` - Redis Implementation

```
redis/
├── connection.go           # Redis client setup
├── cache_repo.go          # Implements port.Cache
└── scripts/               # Lua scripts (nếu cần)
```

#### 4.4.3 `kafka/` - Kafka Implementation

```
kafka/
├── config.go              # Kafka configuration
├── consumer.go            # Base consumer
├── producer.go            # Event producer
└── handler/               # Event handlers
    ├── user_event_handler.go
    └── order_event_handler.go
```

### 4.5 `internal/workers/` - Background Workers

**ONE directory per worker type:**

```
workers/
├── outbox_relay/          # Relay events from Redis to Kafka
│   ├── worker.go
│   ├── consumer.go
│   ├── producer.go
│   └── metrics.go
├── sync_worker/           # Sync data to cold storage
│   ├── worker.go
│   └── batch_processor.go
└── cronjobs/              # Scheduled jobs
    ├── abandoned_marker.go
    └── archival_cleaner.go
```

**Worker Pattern:**

```go
// workers/outbox_relay/worker.go
type OutboxRelayWorker struct {
    redis      *redis.Client
    producer   *kafka.Producer
    consumer   *kafka.Consumer
    logger     *logging.Logger
    metrics   *Metrics
}

func NewOutboxRelayWorker(...) *OutboxRelayWorker {
    return &OutboxRelayWorker{...}
}

func (w *OutboxRelayWorker) Start(ctx context.Context) error {
    w.logger.Info("Starting outbox relay worker")
    
    // Start consumer
    go w.consume(ctx)
    
    // Start batch processor
    go w.processBatches(ctx)
    
    <-ctx.Done()
    return w.Shutdown()
}

func (w *OutboxRelayWorker) consume(ctx context.Context) {
    for {
        select {
        case <-ctx.Done():
            return
        case streamKey := <-w.notificationChan:
            w.enqueueProcess(streamKey)
        }
    }
}
```

### 4.6 `internal/common/` - Shared Utilities

**Service-agnostic utilities có thể share giữa các services:**

```
common/
├── constants/              # Magic numbers and strings
│   └── constants.go
├── errors/                 # Shared error types
│   └── errors.go
├── keybuilder/             # Redis key builders
│   └── keybuilder.go
├── circuitbreaker/         # Circuit breaker
│   └── circuitbreaker.go
├── event/                   # Event building utilities
│   └── event_builder.go
└── validator/               # Cross-cutting validators
    └── validator.go
```

---

## 5. Comparison: Current vs Proposed

### 5.1 Cart Service

| Aspect | Current | Proposed |
|--------|---------|----------|
| Handlers | `handlers.go` (all in one) | `handler/` (one file per entity) |
| Workers | Flat structure | `workers/` with subdirs per type |
| Redis scripts | `redis/scripts.lua` | `redis/scripts/` with Go wrapper |
| Config | `internal/config/config.go` | `configs/application.yaml` |
| Common | `common/` | `common/` (keep) |

### 5.2 User Service

| Aspect | Current | Proposed |
|--------|---------|----------|
| DTO location | `api/dto/` | `api/dto/request/`, `api/dto/response/` |
| Kafka handlers | `kafka/handler/` | `infrastructure/kafka/handler/` |
| Scripts | `scripts/` at root | `scripts/` (keep) |
| Domain events | `domain/event/` | `domain/event/` (keep) |

### 5.3 Benefits of Proposed Structure

1. **Predictability:** Developer biết file ở đâu
2. **Scalability:** Mỗi team có thể làm việc trên layer riêng
3. **Testability:** Dễ mock dependencies
4. **Maintainability:** Thay đổi local trong layer, không affect toàn bộ
5. **Onboarding:** Cấu trúc nhất quán, chỉ cần học một lần

---

## 6. Migration Guide

### Phase 1: Create Common Layout Template

1. Tạo một service mới với cấu trúc chuẩn làm template
2. Extract các common components vào `internal/common/`
3. Document các patterns và conventions

### Phase 2: Migrate Existing Services (Incremental)

**Cart Service:**
```
Current:                              Target:
├── cmd/server/main.go        →      ├── cmd/server/main.go
├── internal/api/                    ├── internal/api/
│   ├── handlers.go          →      │   ├── handler/cart_handler.go
│   ├── router.go            →      │   ├── router/router.go
│   ├── middleware.go         →      │   ├── middleware/
│   └── product_client.go    →      │   └── dto/
├── internal/workers/                 ├── internal/workers/
│   ├── outbox_relay/        →      │   ├── outbox_relay/
│   ├── postgres_sync/        →      │   ├── sync_worker/
│   └── cronjobs/            →      │   └── cronjobs/
├── internal/redis/                   ├── internal/infrastructure/
│   └── scripts.lua           →      │   ├── redis/
├── internal/common/                  │   ├── kafka/
│   ├── constants/            →      │   └── postgres/
│   ├── keybuilder/           →      └── internal/common/
│   ├── circuitbreaker/       →
│   └── event/                →
└── internal/config/                 └── configs/application.yaml
```

**User Service:**
```
Current:                              Target:
├── cmd/server/main.go        →      ├── cmd/server/main.go
├── internal/api/                    ├── internal/api/
│   ├── handler/               →      │   ├── handler/
│   │   ├── profile_handler.go →     │   ├── middleware/
│   │   └── address_handler.go →     │   ├── router/
│   ├── middleware/            →      │   └── dto/
│   ├── router/               →
│   └── dto/                  →
├── internal/domain/                  ├── internal/domain/
│   ├── entity/               →      │   ├── entity/
│   ├── service/              →      │   ├── service/
│   ├── port/                 →      │   ├── port/
│   ├── vo/                   →      │   └── vo/
│   └── event/                →      └── event/
├── internal/infrastructure/          ├── internal/infrastructure/
│   ├── postgres/             →      │   ├── postgres/
│   ├── redis/                →      │   ├── redis/
│   └── kafka/                →      │   └── kafka/
└── internal/kafka/                   └── configs/application.yaml
    ├── consumer.go
    ├── handler/
    └── config.go
```

### Phase 3: Enforce Standards

1. Thêm linting rule để reject imports cross-layer không hợp lệ
2. Tạo `golangci-lint` configuration cho layout rules
3. Code review checklist bao gồm structure verification

---

## 7. Code Examples

### 7.1 Complete Handler Example

```go
// internal/api/handler/profile_handler.go
package handler

import (
    "context"
    "errors"
    "net/http"
    
    "user-service/internal/common/errors"
    "user-service/internal/domain/port"
    "user-service/internal/domain/service"
    "user-service/internal/api/dto/request"
    "user-service/internal/api/dto/response"
    "user-service/internal/api/middleware"
)

type ProfileHandler struct {
    service *service.ProfileService
    logger  *logging.Logger
}

func NewProfileHandler(svc *service.ProfileService, logger *logging.Logger) *ProfileHandler {
    return &ProfileHandler{service: svc, logger: logger}
}

// CreateProfile handles POST /profiles
func (h *ProfileHandler) CreateProfile(w http.ResponseWriter, r *http.Request) {
    ctx := r.Context()
    
    // 1. Parse request
    var req request.CreateProfileRequest
    if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
        middleware.Error(w, r, apperrors.ErrInvalidRequest)
        return
    }
    
    // 2. Validate
    if err := req.Validate(); err != nil {
        middleware.Error(w, r, err)
        return
    }
    
    // 3. Execute business logic
    profile, err := h.service.CreateProfile(ctx, req.ToEntity())
    if err != nil {
        h.handleError(w, r, err)
        return
    }
    
    // 4. Return response
    middleware.JSON(w, r, http.StatusCreated, response.NewProfileResponse(profile))
}

func (h *ProfileHandler) handleError(w http.ResponseWriter, r *http.Request, err error) {
    var appErr *apperrors.Error
    if errors.As(err, &appErr) {
        middleware.Error(w, r, appErr)
        return
    }
    h.logger.Error(ctx, "unexpected error", "error", err)
    middleware.Error(w, r, apperrors.ErrInternal)
}
```

### 7.2 Complete Repository Example

```go
// internal/infrastructure/postgres/profile_repo.go
package postgres

import (
    "context"
    "errors"
    
    "github.com/google/uuid"
    "gorm.io/gorm"
    
    "user-service/internal/domain/entity"
    "user-service/internal/domain/port"
)

var _ port.ProfileRepository = (*ProfileRepository)(nil)

type ProfileRepository struct {
    db *gorm.DB
}

func NewProfileRepository(db *gorm.DB) *ProfileRepository {
    return &ProfileRepository{db: db}
}

func (r *ProfileRepository) GetByID(ctx context.Context, id uuid.UUID) (*entity.Profile, error) {
    var m ProfileModel
    if err := r.db.WithContext(ctx).First(&m, "id = ?", id).Error; err != nil {
        if errors.Is(err, gorm.ErrRecordNotFound) {
            return nil, port.ErrNotFound
        }
        return nil, err
    }
    return m.ToEntity(), nil
}

func (r *ProfileRepository) Create(ctx context.Context, p *entity.Profile) error {
    m := NewProfileModel(p)
    return r.db.WithContext(ctx).Create(m).Error
}

func (r *ProfileRepository) Update(ctx context.Context, p *entity.Profile) error {
    m := NewProfileModel(p)
    return r.db.WithContext(ctx).Save(m).Error
}

func (r *ProfileRepository) Delete(ctx context.Context, id uuid.UUID) error {
    return r.db.WithContext(ctx).Delete(&ProfileModel{}, "id = ?", id).Error
}
```

### 7.3 Complete Domain Service Example

```go
// internal/domain/service/profile_service.go
package service

import (
    "context"
    "errors"
    
    "github.com/google/uuid"
    
    "user-service/internal/domain/entity"
    "user-service/internal/domain/event"
    "user-service/internal/domain/port"
)

var (
    ErrEmailExists    = errors.New("email already exists")
    ErrProfileNotFound = errors.New("profile not found")
)

type ProfileService struct {
    repo   port.ProfileRepository
    cache  port.Cache
    events port.EventPublisher
}

func NewProfileService(
    repo port.ProfileRepository,
    cache port.Cache,
    events port.EventPublisher,
) *ProfileService {
    return &ProfileService{
        repo:   repo,
        cache:  cache,
        events: events,
    }
}

func (s *ProfileService) CreateProfile(ctx context.Context, req *entity.Profile) (*entity.Profile, error) {
    // Check email uniqueness
    existing, err := s.repo.GetByEmail(ctx, req.Email.String())
    if err != nil && !errors.Is(err, port.ErrNotFound) {
        return nil, err
    }
    if existing != nil {
        return nil, ErrEmailExists
    }
    
    // Create
    if err := s.repo.Create(ctx, req); err != nil {
        return nil, err
    }
    
    // Publish event (async-safe)
    s.events.Publish(ctx, event.NewProfileCreated(req))
    
    return req, nil
}

func (s *ProfileService) GetProfile(ctx context.Context, id uuid.UUID) (*entity.Profile, error) {
    // Try cache first
    if s.cache != nil {
        cached, err := s.cache.Get(ctx, "profile:"+id.String())
        if err == nil && cached != nil {
            return entity.ProfileFromCache(cached)
        }
    }
    
    // Fetch from DB
    profile, err := s.repo.GetByID(ctx, id)
    if err != nil {
        return nil, err
    }
    
    // Update cache
    if s.cache != nil {
        s.cache.Set(ctx, "profile:"+id.String(), profile.ToCache(), 5*time.Minute)
    }
    
    return profile, nil
}
```

---

## 8. Best Practices Checklist

### 8.1 Project Structure

- [ ] Có `cmd/server/main.go` là entry point duy nhất
- [ ] Business logic trong `internal/domain/`, không có trong `cmd/`
- [ ] Interfaces định nghĩa trong `domain/port/`
- [ ] Implementations trong `infrastructure/`
- [ ] Handlers trong `api/handler/`, một file per entity

### 8.2 Dependency Rules

- [ ] `domain/` không import `infrastructure/`
- [ ] `api/` không import `infrastructure/` trực tiếp (qua domain)
- [ ] `workers/` import `domain/` và `infrastructure/`

### 8.3 Naming Conventions

- [ ] Package names ngắn gọn, lowercase
- [ ] Interface names có suffix phù hợp (`Repository`, `Publisher`, `Service`)
- [ ] Error types có suffix `Error` hoặc `Err`

### 8.4 Configuration

- [ ] Config từ `configs/application.yaml`
- [ ] Environment variables override file config
- [ ] Secrets không hardcoded

### 8.5 Testing

- [ ] Unit tests cạnh file source (`*_test.go`)
- [ ] Mocks cho interfaces trong `test/mocks/`
- [ ] Integration tests trong `test/integration/`

### 8.6 Documentation

- [ ] Package-level comments cho public APIs
- [ ] README.md cho mỗi service
- [ ] API documentation (Swagger/OpenAPI)

---

## Appendix A: Quick Reference

### Layer Responsibilities

| Layer | Responsibility | Examples |
|-------|----------------|----------|
| `api/handler/` | HTTP request/response | Parse JSON, call domain, format response |
| `domain/service/` | Business logic | Validation, orchestration, business rules |
| `domain/port/` | Contracts | Interfaces for data access, external services |
| `domain/entity/` | Business models | Profile, Order, Cart with methods |
| `infrastructure/` | External integrations | DB queries, Redis ops, Kafka produce |

### Import Rules (Visual)

```
cmd/ ────────────────┐
                     │
internal/ ───────────┤
  ├── api/ ──────────┤
  │     └─→ domain/ ─┤
  ├── domain/ ───────┤
  │     ├─→ common/ ◄┘
  │     └─→ port/ ───┘
  ├── infrastructure/─┤
  │     └─→ domain/ ◄┘
  └── workers/ ───────┘
        ├─→ domain/ ◄┘
        └─→ infrastructure/ ◄┘
```

### Common Patterns

| Pattern | Where | Purpose |
|---------|-------|---------|
| Repository | `domain/port/` + `infrastructure/postgres/` | Data access abstraction |
| Factory | `domain/entity/` | Object creation with validation |
| Builder | `common/event/` | Complex object construction |
| Strategy | `infrastructure/` | Interchangeable algorithms |
| Observer | `domain/event/` | Event notification |

---

## Appendix B: Migration Checklist Template

### For Each Service

```
[ ] 1. Create backup of current structure
[ ] 2. Create new directory structure
[ ] 3. Move cmd/server/main.go
[ ] 4. Move/rename domain/ entities
[ ] 5. Move/rename domain/ services
[ ] 6. Move/rename domain/ port (interfaces)
[ ] 7. Move/rename infrastructure/ implementations
[ ] 8. Move/rename api/ handlers to handler/
[ ] 9. Move api/ middleware to middleware/
[ ] 10. Move DTOs to dto/request/ and dto/response/
[ ] 11. Move workers/ to new structure
[ ] 12. Move common/ utilities
[ ] 13. Create/update configs/application.yaml
[ ] 14. Update imports
[ ] 15. Update main.go wiring
[ ] 16. Run tests
[ ] 17. Run linter
[ ] 18. Update README
```

---

*Document maintained by: Platform Team*  
*Last updated: May 17, 2026*
