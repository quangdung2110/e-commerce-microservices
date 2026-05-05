# User Service - Implementation Plan

**Phiên bản:** 1.0  
**Ngày tạo:** 2026-05-05  
**Trạng thái:** Approved  
**Service:** User Service (Profile Service)  
**Kiến trúc:** Microservice Event-Driven với Outbox Pattern

---

## Mục Lục

1. [Tổng Quan Nghiệp Vụ](#1-tổng-quan-nghiệp-vụ)
2. [Lựa Chọn Công Nghệ](#2-lựa-chọn-công-nghệ)
3. [Kiến Trúc Module](#3-kiến-trúc-module)
4. [Cấu Trúc Thư Mục](#4-cấu-trúc-thư-mục)
5. [Database Migrations](#5-database-migrations)
6. [Redis Caching Plan](#6-redis-caching-plan)
7. [Outbox Pattern & Kafka](#7-outbox-pattern--kafka)
8. [API Endpoints](#8-api-endpoints)
9. [Kế Hoạch Triển Khai](#9-kế-hoạch-triển-khai)
10. [Kế Hoạch Kiểm Thử](#10-kế-hoạch-kiểm-thử)
11. [Deployment & DevOps](#11-deployment--devops)
12. [Checklist Xác Minh](#12-checklist-xác-minh)

---

## 1. Tổng Quan Nghiệp Vụ

### 1.1 Mô Tả Service

User Service (Profile Service) chịu trách nhiệm quản lý thông tin hồ sơ khách hàng, sổ địa chỉ giao hàng, và cài đặt cá nhân trong hệ thống E-Commerce Platform. Service này tách biệt hoàn toàn với Auth Service để đảm bảo Single Responsibility Principle.

### 1.2 Các Workflows Cốt Lõi

| # | Workflow | Trigger | Action |
|:--|:---------|:--------|:-------|
| 1 | Khởi Tạo Hồ Sơ | Kafka `USER_VERIFIED_SUCCESS` | Tạo profile + preferences (idempotent) |
| 2 | CRUD Sổ Địa Chỉ | API Call | Create/Read/Update/Delete addresses + Redis cache |
| 3 | Cập Nhật Hồ Sơ | API Call | UPDATE profile + Outbox event |
| 4 | Cập Nhật Preferences | API Call | UPDATE preferences + Outbox event |
| 5 | Đồng Bộ Chat Service | CDC Event | Projection sender_name |
| 6 | Xóa Tài Khoản (GDPR) | Kafka `USER_DELETED` | Hard delete all user data |

### 1.3 Event Flow Summary

```
┌─────────────┐         ┌─────────────┐         ┌─────────────┐
│ Auth Svc    │────────▶│   Kafka     │────────▶│ User Svc    │
│ (Producer)  │ t.auth   │ t.auth.events│         │ (Consumer)  │
└─────────────┘         └─────────────┘         └─────────────┘
                                                         │
                                                         ▼
┌─────────────┐         ┌─────────────┐         ┌─────────────┐
│ Chat Svc    │◀────────│   Kafka     │◀────────│   Postgres  │
│ (Consumer)  │ t.user  │ t.user.events│         │   (Debezium)│
└─────────────┘         └─────────────┘         └─────────────┘
```

### 1.4 Database Schema Overview

| Bảng | Mối quan hệ | Mục đích |
|:-----|:------------|:---------|
| `user_profiles` | Primary | Lưu hồ sơ khách hàng (1-1 với Auth) |
| `user_addresses` | FK → profiles | Sổ địa chỉ giao hàng (Hard Delete) |
| `user_preferences` | FK → profiles | Cài đặt ngôn ngữ, thông báo (1-1) |
| `outbox_events` | Internal | Transactional Outbox cho CDC |

---

## 2. Lựa Chọn Công Nghệ

### 2.1 Stack Đề Xuất: **Go + Gin Framework**

| Thành phần | Lựa chọn | Lý do |
|:-----------|:---------|:------|
| **Ngôn ngữ** | Go 1.21+ | Performance cao, concurrency xuất sắc, memory footprint thấp |
| **Web Framework** | Gin v1.9+ | Nhẹ, nhanh, middleware ecosystem phong phú |
| **ORM/Query** | sqlx + pgx | Zero overhead, prepared statements, connection pooling |
| **Kafka Client** | segmentio/kafka-go | Native Go, high-performance, battle-tested |
| **Redis Client** | go-redis/redis v9 | Cluster mode, pipeline, Pub/Sub support |
| **Migrations** | golang-migrate | Versioned SQL migrations, rollbacks |
| **Validation** | go-playground/validator | Struct-based validation |
| **Config** | viper | YAML/TOML support, env variable binding |
| **Testing** | testify + testcontainers-go | Integration tests với real infra |
| **DI Container** | uber-go/fx | Compile-time dependency injection |

### 2.2 Tại Sao Không Java Spring Boot?

| Tiêu chí | Go + Gin | Java Spring Boot |
|:---------|:---------|:-----------------|
| Cold Start | ~50ms | ~2-5s |
| Memory | ~10-20MB/container | ~200-400MB/container |
| Deployment Size | ~15MB binary | ~200MB+ JAR |
| Throughput | 50K-100K req/s | 20K-40K req/s |
| Kafka Native | segmentio/kafka-go | spring-kafka (wrapper) |
| Concurrency Model | Goroutines (cheap) | Threads (expensive) |
| **Phù hợp cho Microservice** | ✅ **Highly recommended** | ⚠️ Overkill cho service nhỏ |

**Kết luận:** Go + Gin phù hợp hơn với:
- Service nhỏ, focused responsibility
- Event-driven workload (Kafka consumer)
- Container-based deployment (K8s)
- Cost optimization (memory/CPU)

### 2.3 Infrastructure Dependencies

| Dependency | Version | Purpose |
|:-----------|:--------|:--------|
| PostgreSQL | 15+ | Primary database |
| Redis | 7.2+ | L2 Cache cho addresses |
| Kafka | 3.6+ | Event streaming |
| Debezium | 2.5+ | CDC for Outbox Pattern |

---

## 3. Kiến Trúc Module

### 3.1 Module Breakdown

Dự án được chia thành **4 module chính** có thể phát triển song song:

```
┌─────────────────────────────────────────────────────────────┐
│                     user-service                             │
├─────────────┬─────────────┬─────────────┬───────────────────┤
│  Module A  │  Module B   │  Module C   │     Module D       │
│   Domain   │   Infra     │  API/REST   │  Kafka Consumer    │
│   (Core)   │  (DB/Redis) │  (HTTP)     │   (Worker)         │
├─────────────┼─────────────┼─────────────┼───────────────────┤
│ • Entities │ • Postgres  │ • Gin       │ • Kafka Producer   │
│ • ValueObj │ • Redis     │ • Handlers  │ • Kafka Consumer   │
│ • DomainEvt│ • Outbox    │ • Middleware│ • Idempotency     │
│ • Ports    │ • Migrations│ • DTOs      │ • Event Handlers   │
└─────────────┴─────────────┴─────────────┴───────────────────┘
```

### 3.2 Module A: Domain (Core)

**Trách nhiệm:** Định nghĩa business logic thuần, không phụ thuộc infrastructure.

```go
// Ports (Interfaces) - Module A cung cấp cho Module B, C, D

// Profile Repository Port
type ProfileRepository interface {
    Create(ctx context.Context, profile *Profile) error
    GetByID(ctx context.Context, id uuid.UUID) (*Profile, error)
    Update(ctx context.Context, profile *Profile) error
    Delete(ctx context.Context, id uuid.UUID) error
    Exists(ctx context.Context, id uuid.UUID) (bool, error)
}

// Address Repository Port
type AddressRepository interface {
    Create(ctx context.Context, addr *Address) error
    GetByID(ctx context.Context, id uuid.UUID) (*Address, error)
    GetByUserID(ctx context.Context, userID uuid.UUID) ([]*Address, error)
    Update(ctx context.Context, addr *Address) error
    Delete(ctx context.Context, id uuid.UUID) error
    SetDefault(ctx context.Context, userID, addrID uuid.UUID) error
}

// Preferences Repository Port
type PreferencesRepository interface {
    Create(ctx context.Context, prefs *Preferences) error
    GetByUserID(ctx context.Context, userID uuid.UUID) (*Preferences, error)
    Update(ctx context.Context, prefs *Preferences) error
}

// Outbox Repository Port
type OutboxRepository interface {
    Create(ctx context.Context, event *OutboxEvent) error
    GetPendingEvents(ctx context.Context, limit int) ([]*OutboxEvent, error)
}

// Cache Port
type AddressCache interface {
    Get(ctx context.Context, userID uuid.UUID) ([]*Address, error)
    Set(ctx context.Context, userID uuid.UUID, addresses []*Address, ttl time.Duration) error
    Delete(ctx context.Context, userID uuid.UUID) error
}
```

**Dependencies:** None (pure domain logic)

### 3.3 Module B: Infrastructure

**Trách nhiệm:** Implement các ports từ Domain, kết nối PostgreSQL, Redis.

#### 3.3.1 PostgreSQL Implementation

```go
// Profile Repository Implementation
type PostgresProfileRepository struct {
    db *sqlx.DB
}

func (r *PostgresProfileRepository) Create(ctx context.Context, p *Profile) error {
    query := `
        INSERT INTO user_profiles (id, first_name, last_name, phone_number, date_of_birth, gender, created_at, updated_at)
        VALUES ($1, $2, $3, $4, $5, $6, NOW(), NOW())
        ON CONFLICT (id) DO NOTHING
        RETURNING created_at, updated_at`
    
    return r.db.QueryRowxContext(ctx, query,
        p.ID, p.FirstName, p.LastName, p.PhoneNumber, p.DateOfBirth, p.Gender,
    ).Scan(&p.CreatedAt, &p.UpdatedAt)
}
```

#### 3.3.2 Redis Implementation

```go
// Redis Address Cache Implementation
type RedisAddressCache struct {
    client *redis.Client
    ttl12h = 12 * time.Hour
    ttl30m = 30 * time.Minute
}

func (c *RedisAddressCache) Get(ctx context.Context, userID uuid.UUID) ([]*Address, error) {
    key := fmt.Sprintf("user:caches:addresses:%s", userID.String())
    data, err := c.client.Get(ctx, key).Bytes()
    if err == redis.Nil {
        return nil, nil // Cache miss
    }
    if err != nil {
        return nil, err
    }
    
    var addresses []*Address
    if err := json.Unmarshal(data, &addresses); err != nil {
        return nil, err
    }
    return addresses, nil
}

func (c *RedisAddressCache) Set(ctx context.Context, userID uuid.UUID, addresses []*Address, ttl time.Duration) error {
    key := fmt.Sprintf("user:caches:addresses:%s", userID.String())
    data, err := json.Marshal(addresses)
    if err != nil {
        return err
    }
    return c.client.Set(ctx, key, data, ttl).Err()
}
```

**Dependencies:** PostgreSQL, Redis

### 3.4 Module C: API/REST (HTTP Layer)

**Trách nhiệm:** Handle HTTP requests, authentication, validation, response formatting.

#### 3.4.1 Public APIs Provided

| Method | Path | Mô tả |
|:-------|:-----|:-------|
| `GET` | `/api/v1/users/me/profile` | Lấy hồ sơ user hiện tại |
| `PUT` | `/api/v1/users/me/profile` | Cập nhật hồ sơ |
| `GET` | `/api/v1/users/me/addresses` | Lấy danh sách địa chỉ (cache) |
| `POST` | `/api/v1/users/me/addresses` | Thêm địa chỉ mới |
| `PUT` | `/api/v1/users/me/addresses/{id}` | Cập nhật địa chỉ |
| `DELETE` | `/api/v1/users/me/addresses/{id}` | Xóa địa chỉ |
| `POST` | `/api/v1/users/me/addresses/{id}/default` | Set địa chỉ mặc định |
| `GET` | `/api/v1/users/me/preferences` | Lấy preferences |
| `PUT` | `/api/v1/users/me/preferences` | Cập nhật preferences |
| `GET` | `/health` | Health check |
| `GET` | `/ready` | Readiness check |

#### 3.4.2 Middleware Stack

```
Request → Logging → Recovery → Auth(JWT) → RateLimit → Validation → Handler
                                                                      ↓
Response ← ErrorHandler ← JSONMarshal ← Handler ← Business Logic
```

**Dependencies:** Module A (Domain)

### 3.5 Module D: Kafka Consumer (Worker)

**Trách nhiệm:** Xử lý events từ Kafka topics `t.auth.events`.

#### 3.5.1 Consumer Groups

| Consumer Group | Topic | Purpose |
|:---------------|:------|:--------|
| `user-svc-profile-creator` | `t.auth.events` | Xử lý USER_VERIFIED_SUCCESS |
| `user-svc-account-deletion` | `t.auth.events` | Xử lý USER_DELETED |

#### 3.5.2 Event Handlers

```go
// UserVerifiedHandler - Idempotent profile creation
type UserVerifiedHandler struct {
    profileRepo      ProfileRepository
    preferencesRepo  PreferencesRepository
    db               *sqlx.DB
}

func (h *UserVerifiedHandler) Handle(ctx context.Context, msg *kafka.Message) error {
    var event AuthEvent
    if err := json.Unmarshal(msg.Value, &event); err != nil {
        return err // Don't retry malformed messages
    }
    
    if event.EventType != "USER_VERIFIED_SUCCESS" {
        return nil // Skip non-relevant events
    }
    
    // Transaction for atomicity
    tx, err := h.db.BeginTxx(ctx, nil)
    if err != nil {
        return err
    }
    defer tx.Rollback()
    
    // Create profile (idempotent - ON CONFLICT DO NOTHING)
    profile := &Profile{ID: event.Payload.UserID}
    if err := h.profileRepo.CreateTx(tx, profile); err != nil {
        return err
    }
    
    // Create default preferences (idempotent)
    prefs := &Preferences{UserID: event.Payload.UserID}
    if err := h.preferencesRepo.CreateTx(tx, prefs); err != nil {
        return err
    }
    
    return tx.Commit()
}

// UserDeletedHandler - GDPR account deletion
type UserDeletedHandler struct {
    addressRepo     AddressRepository
    profileRepo     ProfileRepository
    preferencesRepo PreferencesRepository
    outboxRepo      OutboxRepository
    db              *sqlx.DB
}

func (h *UserDeletedHandler) Handle(ctx context.Context, msg *kafka.Message) error {
    // Implementation: Delete in correct order with outbox
}
```

**Dependencies:** Module A (Domain), Module B (Infrastructure)

---

## 4. Cấu Trúc Thư Mục

### 4.1 Clean Architecture Layout

```
user-service/
├── cmd/
│   └── server/
│       └── main.go                 # Application entrypoint
│
├── internal/
│   ├── domain/                     # Module A: Domain (Core)
│   │   ├── entity/
│   │   │   ├── profile.go          # Profile entity
│   │   │   ├── address.go          # Address entity
│   │   │   ├── preferences.go       # Preferences entity
│   │   │   └── outbox.go           # OutboxEvent entity
│   │   ├── vo/                      # Value Objects
│   │   │   ├── phone.go            # Phone number value object
│   │   │   ├── name.go              # Name value object
│   │   │   └── gender.go            # Gender enum
│   │   ├── port/                    # Interfaces (Ports)
│   │   │   ├── profile_repository.go
│   │   │   ├── address_repository.go
│   │   │   ├── preferences_repository.go
│   │   │   ├── outbox_repository.go
│   │   │   └── address_cache.go
│   │   ├── service/                 # Domain Services
│   │   │   ├── profile_service.go
│   │   │   ├── address_service.go
│   │   │   └── preferences_service.go
│   │   └── event/                   # Domain Events
│   │       ├── user_profile_updated.go
│   │       └── user_preferences_updated.go
│   │
│   ├── infrastructure/              # Module B: Infrastructure
│   │   ├── postgres/
│   │   │   ├── connection.go        # DB connection pool
│   │   │   ├── profile_repo.go      # Profile repository impl
│   │   │   ├── address_repo.go      # Address repository impl
│   │   │   ├── preferences_repo.go  # Preferences repository impl
│   │   │   └── outbox_repo.go       # Outbox repository impl
│   │   ├── redis/
│   │   │   ├── connection.go        # Redis client
│   │   │   └── address_cache.go     # Redis cache impl
│   │   └── config/
│   │       └── config.go            # Configuration loading
│   │
│   ├── api/                         # Module C: HTTP Layer
│   │   ├── router/
│   │   │   └── router.go            # Gin router setup
│   │   ├── middleware/
│   │   │   ├── auth.go              # JWT authentication
│   │   │   ├── logging.go           # Request logging
│   │   │   ├── recovery.go          # Panic recovery
│   │   │   └── ratelimit.go         # Rate limiting
│   │   ├── handler/
│   │   │   ├── profile_handler.go   # Profile endpoints
│   │   │   ├── address_handler.go   # Address endpoints
│   │   │   ├── preferences_handler.go
│   │   │   └── health_handler.go    # Health/Ready checks
│   │   ├── dto/
│   │   │   ├── request/
│   │   │   │   ├── update_profile.go
│   │   │   │   ├── create_address.go
│   │   │   │   └── update_preferences.go
│   │   │   └── response/
│   │   │       ├── profile.go
│   │   │       ├── address.go
│   │   │       ├── preferences.go
│   │   │       └── error.go
│   │   └── validator/
│   │       └── validator.go         # Custom validators
│   │
│   └── kafka/                       # Module D: Kafka Consumer
│       ├── consumer/
│       │   ├── consumer.go         # Base consumer
│       │   └── consumer_group.go   # Consumer group manager
│       ├── handler/
│       │   ├── user_verified_handler.go
│       │   └── user_deleted_handler.go
│       └── event/
│           ├── auth_event.go        # Consumed events
│           └── user_event.go       # Produced events (outbox)
│
├── migrations/
│   ├── 000001_create_user_profiles.up.sql
│   ├── 000001_create_user_profiles.down.sql
│   ├── 000002_create_user_addresses.up.sql
│   ├── 000002_create_user_addresses.down.sql
│   ├── 000003_create_user_preferences.up.sql
│   ├── 000003_create_user_preferences.down.sql
│   ├── 000004_create_outbox_events.up.sql
│   └── 000004_create_outbox_events.down.sql
│
├── pkg/
│   ├──apperrors/                    # Application errors
│   │   └── errors.go
│   ├── logger/                      # Structured logging
│   │   └── logger.go
│   └── tracing/                     # OpenTelemetry tracing
│       └── tracing.go
│
├── configs/
│   ├── config.yaml                  # Configuration file
│   └── debezium/
│       └── debezium-connector.json  # Debezium CDC config
│
├── scripts/
│   ├── wait-for-db.sh              # Startup dependency check
│   └── cleanup-outbox.sql          # Outbox cleanup cron
│
├── docker/
│   ├── Dockerfile
│   └── docker-compose.yaml         # Local development
│
├── tests/
│   ├── unit/
│   │   ├── domain/
│   │   ├── service/
│   │   └── handler/
│   └── integration/
│       ├── postgres_test.go
│       ├── redis_test.go
│       └── kafka_test.go
│
├── Makefile
├── go.mod
├── go.sum
└── README.md
```

### 4.2 Dependency Graph

```
main.go
  └── fx.NewApplication
        ├── api.Module (HTTP Server)
        │     └── handler.Module
        │           └── domain.Service
        │                 └── domain.Port
        │
        ├── infrastructure.Module (DB + Redis)
        │     └── domain.Port implementations
        │
        └── kafka.Module (Consumer Workers)
              └── handler.Module
                    └── domain.Service
```

---

## 5. Database Migrations

### 5.1 Migration 001: Create user_profiles

```sql
-- 000001_create_user_profiles.up.sql

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

CREATE TABLE user_profiles (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    phone_number VARCHAR(20) UNIQUE,
    date_of_birth DATE,
    gender VARCHAR(10) NOT NULL DEFAULT 'UNKNOWN',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    CONSTRAINT chk_gender CHECK (gender IN ('MALE', 'FEMALE', 'OTHER', 'UNKNOWN')),
    CONSTRAINT chk_phone_format CHECK (phone_number IS NULL OR phone_number ~ '^\+?[0-9]{9,15}$')
);

-- Indexes for common queries
CREATE INDEX idx_user_profiles_phone ON user_profiles(phone_number);
CREATE INDEX idx_user_profiles_created_at ON user_profiles(created_at DESC);

-- Trigger for auto-updating updated_at
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_user_profiles_updated_at
    BEFORE UPDATE ON user_profiles
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

COMMENT ON TABLE user_profiles IS 'Stores customer profile information - 1:1 mapping with auth.users';
```

### 5.2 Migration 002: Create user_addresses

```sql
-- 000002_create_user_addresses.up.sql

CREATE TABLE user_addresses (
    id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id UUID NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    recipient_name VARCHAR(200) NOT NULL,
    phone_number VARCHAR(20) NOT NULL,
    address_line VARCHAR(500) NOT NULL,
    ward_id VARCHAR(20) NOT NULL,
    district_id VARCHAR(20) NOT NULL,
    city_id VARCHAR(20) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    CONSTRAINT fk_user_addresses_user_profile 
        FOREIGN KEY (user_id) 
        REFERENCES user_profiles(id) 
        ON DELETE CASCADE,
    CONSTRAINT chk_address_phone_format 
        CHECK (phone_number ~ '^\+?[0-9]{9,15}$')
);

-- Indexes for common queries
CREATE INDEX idx_user_addresses_user_id ON user_addresses(user_id);
CREATE INDEX idx_user_addresses_ward ON user_addresses(ward_id);
CREATE INDEX idx_user_addresses_district ON user_addresses(district_id);
CREATE INDEX idx_user_addresses_city ON user_addresses(city_id);
CREATE INDEX idx_user_addresses_user_default ON user_addresses(user_id, is_default) WHERE is_default = TRUE;

-- CRITICAL: Partial Unique Index for default address constraint
CREATE UNIQUE INDEX idx_unique_default_address 
ON user_addresses (user_id) 
WHERE is_default = TRUE;

-- Composite index for listing addresses (default first)
CREATE INDEX idx_user_addresses_user_list 
ON user_addresses (user_id, is_default DESC, created_at DESC);

-- Trigger for auto-updating updated_at
CREATE TRIGGER update_user_addresses_updated_at
    BEFORE UPDATE ON user_addresses
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

COMMENT ON TABLE user_addresses IS 'Stores user shipping addresses - Hard Delete policy (Order Service snapshots)';
COMMENT ON INDEX idx_unique_default_address IS 'Ensures only 1 default address per user at database level';
```

### 5.3 Migration 003: Create user_preferences

```sql
-- 000003_create_user_preferences.up.sql

CREATE TABLE user_preferences (
    user_id UUID PRIMARY KEY,
    language VARCHAR(10) NOT NULL DEFAULT 'vi',
    currency VARCHAR(3) NOT NULL DEFAULT 'VND',
    email_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    in_app_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    marketing_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    CONSTRAINT fk_user_preferences_profile 
        FOREIGN KEY (user_id) 
        REFERENCES user_profiles(id) 
        ON DELETE CASCADE,
    CONSTRAINT chk_language CHECK (language IN ('vi', 'en', 'zh', 'ja', 'ko')),
    CONSTRAINT chk_currency CHECK (currency IN ('VND', 'USD', 'EUR', 'JPY', 'CNY'))
);

-- Index for admin queries
CREATE INDEX idx_user_preferences_language ON user_preferences(language);

-- Trigger for auto-updating updated_at
CREATE TRIGGER update_user_preferences_updated_at
    BEFORE UPDATE ON user_preferences
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

COMMENT ON TABLE user_preferences IS 'Stores user app preferences for notifications and display';
```

### 5.4 Migration 004: Create outbox_events

```sql
-- 000004_create_outbox_events.up.sql

CREATE TABLE outbox_events (
    event_id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    event_type VARCHAR(100) NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    partition_key UUID NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    
    CONSTRAINT chk_event_type CHECK (
        event_type IN ('USER_PROFILE_UPDATED', 'USER_PREFERENCES_UPDATED', 'USER_PROFILE_DELETED')
    )
);

-- Indexes for Debezium polling
CREATE INDEX idx_outbox_events_created_at ON outbox_events(created_at ASC);
CREATE INDEX idx_outbox_events_aggregate ON outbox_events(aggregate_type, aggregate_id);
CREATE INDEX idx_outbox_events_unprocessed ON outbox_events(created_at) WHERE payload IS NOT NULL;

-- Index for cleanup job
CREATE INDEX idx_outbox_events_cleanup ON outbox_events(created_at) 
WHERE created_at < NOW() - INTERVAL '14 days';

COMMENT ON TABLE outbox_events IS 'Transactional Outbox for CDC via Debezium';
COMMENT ON INDEX idx_outbox_events_unprocessed IS 'Debezium polls this index to pick up new events';
```

### 5.5 Migration Rollback Scripts

```sql
-- 000004_create_outbox_events.down.sql
DROP INDEX IF EXISTS idx_outbox_events_cleanup;
DROP INDEX IF EXISTS idx_outbox_events_unprocessed;
DROP INDEX IF EXISTS idx_outbox_events_aggregate;
DROP INDEX IF EXISTS idx_outbox_events_created_at;
DROP TABLE IF EXISTS outbox_events;

-- 000003_create_user_preferences.down.sql
DROP INDEX IF EXISTS idx_user_preferences_language;
DROP TRIGGER IF EXISTS update_user_preferences_updated_at ON user_preferences;
DROP TABLE IF EXISTS user_preferences;

-- 000002_create_user_addresses.down.sql
DROP INDEX IF EXISTS idx_unique_default_address;
DROP INDEX IF EXISTS idx_user_addresses_user_list;
DROP INDEX IF EXISTS idx_user_addresses_city;
DROP INDEX IF EXISTS idx_user_addresses_district;
DROP INDEX IF EXISTS idx_user_addresses_ward;
DROP INDEX IF EXISTS idx_user_addresses_user_id;
DROP TRIGGER IF EXISTS update_user_addresses_updated_at ON user_addresses;
DROP TABLE IF EXISTS user_addresses;

-- 000001_create_user_profiles.down.sql
DROP TRIGGER IF EXISTS update_user_profiles_updated_at ON user_profiles;
DROP INDEX IF EXISTS idx_user_profiles_created_at;
DROP INDEX IF EXISTS idx_user_profiles_phone;
DROP TABLE IF EXISTS user_profiles;
```

---

## 6. Redis Caching Plan

### 6.1 Cache Strategy: Cache-Aside Pattern

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Cache-Aside Flow for Addresses                    │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  1. READ (GET /addresses)                                          │
│  ┌─────────┐     ┌─────────┐     ┌─────────┐     ┌─────────┐     │
│  │ Client  │────▶│   API   │────▶│  Redis  │     │   DB    │     │
│  └─────────┘     └────┬────┘     └────┬────┘     └────┬────┘     │
│                       │                │               │           │
│                       │  GET key       │               │           │
│                       │───────────────▶│               │           │
│                       │                │               │           │
│                       │◀─Cache HIT────│               │           │
│                       │  return data  │               │           │
│                       │                │               │           │
│                       │  Cache MISS   │               │           │
│                       │────────────────│               │           │
│                       │───────────────────────────────▶│           │
│                       │                                │           │
│                       │◀──────────────────────────────│           │
│                       │  return data                  │           │
│                       │                                │           │
│                       │  SET key TTL=12h               │           │
│                       │───────────────▶│               │           │
│                                                                      │
│  2. WRITE (POST/PUT/DELETE /addresses)                             │
│  ┌─────────┐     ┌─────────┐     ┌─────────┐     ┌─────────┐     │
│  │ Client  │────▶│   API   │────▶│   DB    │────▶│  Redis  │     │
│  └─────────┘     └────┬────┘     └────┬────┘     └────┬────┘     │
│                       │                │               │           │
│                       │  DB WRITE      │               │           │
│                       │────────────────▶│               │           │
│                       │  COMMIT        │               │           │
│                       │────────────────▶│               │           │
│                       │                │               │           │
│                       │  DEL key        │               │           │
│                       │───────────────────────────────▶│           │
│                       │                                │ DELETE    │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### 6.2 Key Pattern & TTL Configuration

| Key Pattern | Data Type | TTL | Giá trị |
|:------------|:----------|:----|:--------|
| `user:caches:addresses:{user_id}` | String (JSON Array) | 12 giờ (43200s) | Full address list |
| `user:caches:addresses:{user_id}:empty` | String | 30 phút (1800s) | `[]` (empty cache sentinel) |

### 6.3 Cache Implementation

```go
// internal/infrastructure/redis/address_cache.go

package redis

import (
    "context"
    "encoding/json"
    "fmt"
    "time"
    
    "github.com/google/uuid"
    "github.com/redis/go-redis/v9"
    
    "user-service/internal/domain/entity"
)

const (
    KeyPrefixAddresses = "user:caches:addresses"
    TTL12Hours         = 12 * time.Hour
    TTL30Minutes       = 30 * time.Minute
)

type AddressCache interface {
    Get(ctx context.Context, userID uuid.UUID) ([]*entity.Address, error)
    Set(ctx context.Context, userID uuid.UUID, addresses []*entity.Address) error
    Delete(ctx context.Context, userID uuid.UUID) error
}

type RedisAddressCache struct {
    client *redis.Client
}

func NewRedisAddressCache(client *redis.Client) *RedisAddressCache {
    return &RedisAddressCache{client: client}
}

func (c *RedisAddressCache) key(userID uuid.UUID) string {
    return fmt.Sprintf("%s:%s", KeyPrefixAddresses, userID.String())
}

func (c *RedisAddressCache) Get(ctx context.Context, userID uuid.UUID) ([]*entity.Address, error) {
    key := c.key(userID)
    
    data, err := c.client.Get(ctx, key).Bytes()
    if err == redis.Nil {
        return nil, nil // Cache miss - caller should query DB
    }
    if err != nil {
        return nil, fmt.Errorf("redis get: %w", err)
    }
    
    var addresses []*entity.Address
    if err := json.Unmarshal(data, &addresses); err != nil {
        return nil, fmt.Errorf("json unmarshal: %w", err)
    }
    
    return addresses, nil
}

func (c *RedisAddressCache) Set(ctx context.Context, userID uuid.UUID, addresses []*entity.Address) error {
    key := c.key(userID)
    
    data, err := json.Marshal(addresses)
    if err != nil {
        return fmt.Errorf("json marshal: %w", err)
    }
    
    // Apply shorter TTL for empty results to prevent cache penetration
    ttl := TTL12Hours
    if len(addresses) == 0 {
        ttl = TTL30Minutes
    }
    
    return c.client.Set(ctx, key, data, ttl).Err()
}

func (c *RedisAddressCache) Delete(ctx context.Context, userID uuid.UUID) error {
    key := c.key(userID)
    return c.client.Del(ctx, key).Err()
}
```

### 6.4 Cache Invalidation Map

| Operation | Cache Action | Trigger Point |
|:----------|:-------------|:--------------|
| `POST /addresses` | `DEL key` | After DB INSERT + COMMIT |
| `PUT /addresses/{id}` | `DEL key` | After DB UPDATE + COMMIT |
| `DELETE /addresses/{id}` | `DEL key` | After DB DELETE + COMMIT |
| `POST /addresses/{id}/default` | `DEL key` | After DB UPDATE + COMMIT |
| `USER_DELETED` event | `DEL key` | After DB DELETE cascade |

### 6.5 Cache Penetration Prevention

```go
// Thundering Herd Prevention Strategy

// 1. Singleflight Pattern - deduplicate concurrent requests for same key
import "golang.org/x/sync/singleflight"

type AddressService struct {
    repo      AddressRepository
    cache     AddressCache
    sfGroup   singleflight.Group
}

func (s *AddressService) GetByUserID(ctx context.Context, userID uuid.UUID) ([]*entity.Address, error) {
    // Try cache first
    cached, err := s.cache.Get(ctx, userID)
    if err == nil && cached != nil {
        return cached, nil
    }
    
    // Singleflight to prevent thundering herd
    result, err, _ := s.sfGroup.Do(userID.String(), func() (interface{}, error) {
        // Double-check cache (another goroutine might have populated it)
        cached, err := s.cache.Get(ctx, userID)
        if err == nil && cached != nil {
            return cached, nil
        }
        
        // Query DB
        addresses, err := s.repo.GetByUserID(ctx, userID)
        if err != nil {
            return nil, err
        }
        
        // Cache result (empty array gets short TTL)
        if err := s.cache.Set(ctx, userID, addresses); err != nil {
            log.Printf("cache set failed: %v", err) // Log but don't fail
        }
        
        return addresses, nil
    })
    
    return result.([]*entity.Address), err
}
```

### 6.6 Redis Configuration

```yaml
# Recommended Redis Config for Cache-Aside
maxmemory: 256mb
maxmemory-policy: allkeys-lru

# Persistence (RDB + AOF for cache warmth recovery)
save: "900 1 300 100 60 10000"
appendonly: yes
appendfsync: everysec
```

---

## 7. Outbox Pattern & Kafka

### 7.1 Transactional Outbox Flow

```
┌─────────────────────────────────────────────────────────────────────────┐
│                     Transactional Outbox Pattern                         │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  API Request: PUT /profile { first_name: "Dung" }                       │
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                     SINGLE TRANSACTION                           │   │
│  │                                                                   │   │
│  │  1. UPDATE user_profiles SET first_name = 'Dung'                 │   │
│  │  2. INSERT INTO outbox_events (event_type, payload, ...)         │   │
│  │                                                                   │   │
│  │                              COMMIT ─────────────────────────────│───┼──▶
│  │                                                                  │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│                                    │                                    │
│                                    ▼                                    │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                    PostgreSQL Commit                              │   │
│  │                                                                   │   │
│  │  ✅ Both UPDATE and INSERT are committed atomically              │   │
│  │  ✅ If any fails, both are rolled back                           │   │
│  │                                                                   │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│                                    │                                    │
│                                    ▼                                    │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                    Debezium CDC Connector                        │   │
│  │                                                                   │   │
│  │  Polls: SELECT * FROM outbox_events WHERE id > last_offset       │   │
│  │  Publishes to: t.user.events topic                               │   │
│  │                                                                   │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
│                                    │                                    │
│                                    ▼                                    │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                    Kafka Topic: t.user.events                   │   │
│  │                                                                   │   │
│  │  Partition Key: aggregate_id (user_id)                          │   │
│  │  Message: { event_type: "USER_PROFILE_UPDATED", payload: {...} } │   │
│  │                                                                   │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

### 7.2 Debezium CDC Configuration

```json
// configs/debezium/debezium-connector.json

{
  "name": "user-service-outbox-connector",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.SourceConnector",
    "database.hostname": "postgres",
    "database.port": "5432",
    "database.user": "${env:DB_USER}",
    "database.password": "${env:DB_PASSWORD}",
    "database.dbname": "user_service",
    "database.server.name": "user_svc_db",
    
    "table.include.list": "public.outbox_events",
    
    "topic.prefix": "user-svc",
    "schema.include.list": "public",
    
    "publication.autocreate.mode": "filtered",
    "slot.name": "debezium_user_service",
    
    "transforms": "outbox",
    "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
    "transforms.outbox.route.topic.replacement": "t.user.events",
    "transforms.outbox.route.by.field": "aggregate_type",
    "transforms.outbox.route.by.value": "USER",
    "transforms.outbox.route.by.field.replace": "aggregate_id",
    "transforms.outbox.key.envelope": "both",
    "transforms.outbox.value.fields.additional.placement": "type:event_type",
    
    "snapshot.mode": "initial",
    
    "topic.creation.default.replication.factor": 3,
    "topic.creation.default.partitions": 6,
    "topic.creation.outbox.replication.factor": 3,
    "topic.creation.outbox.partitions": 6,
    
    "poll.interval.ms": 1000,
    "max.batch.size": 1024,
    
    "key.converter": "org.apache.kafka.connect.json.JsonConverter",
    "value.converter": "org.apache.kafka.connect.json.JsonConverter",
    "key.converter.schemas.enable": "false",
    "value.converter.schemas.enable": "false"
  }
}
```

### 7.3 Outbox Repository Implementation

```go
// internal/infrastructure/postgres/outbox_repo.go

package postgres

import (
    "context"
    "encoding/json"
    "time"
    
    "github.com/google/uuid"
    "github.com/jmoiron/sqlx"
    
    "user-service/internal/domain/entity"
)

type OutboxRepository interface {
    Create(ctx context.Context, event *entity.OutboxEvent) error
    CreateTx(ctx context.Context, tx *sqlx.Tx, event *entity.OutboxEvent) error
    GetPendingEvents(ctx context.Context, limit int) ([]*entity.OutboxEvent, error)
    DeleteOldEvents(ctx context.Context, olderThan time.Time) (int64, error)
}

type PostgresOutboxRepository struct {
    db *sqlx.DB
}

func (r *PostgresOutboxRepository) CreateTx(ctx context.Context, tx *sqlx.Tx, event *entity.OutboxEvent) error {
    payloadJSON, err := json.Marshal(event.Payload)
    if err != nil {
        return err
    }
    
    query := `
        INSERT INTO outbox_events (event_id, event_type, aggregate_type, aggregate_id, partition_key, payload, created_at)
        VALUES ($1, $2, $3, $4, $5, $6, NOW())`
    
    _, err = tx.ExecContext(ctx, query,
        event.EventID,
        event.EventType,
        event.AggregateType,
        event.AggregateID,
        event.PartitionKey,
        payloadJSON,
    )
    
    return err
}

func (r *PostgresOutboxRepository) Create(ctx context.Context, event *entity.OutboxEvent) error {
    payloadJSON, err := json.Marshal(event.Payload)
    if err != nil {
        return err
    }
    
    query := `
        INSERT INTO outbox_events (event_id, event_type, aggregate_type, aggregate_id, partition_key, payload, created_at)
        VALUES ($1, $2, $3, $4, $5, $6, NOW())`
    
    _, err = r.db.ExecContext(ctx, query,
        event.EventID,
        event.EventType,
        event.AggregateType,
        event.AggregateID,
        event.PartitionKey,
        payloadJSON,
    )
    
    return err
}

func (r *PostgresOutboxRepository) GetPendingEvents(ctx context.Context, limit int) ([]*entity.OutboxEvent, error) {
    query := `
        SELECT event_id, event_type, aggregate_type, aggregate_id, partition_key, payload, created_at
        FROM outbox_events
        ORDER BY created_at ASC
        LIMIT $1`
    
    rows, err := r.db.QueryxContext(ctx, query, limit)
    if err != nil {
        return nil, err
    }
    defer rows.Close()
    
    var events []*entity.OutboxEvent
    for rows.Next() {
        var e entity.OutboxEvent
        var payloadJSON []byte
        
        if err := rows.Scan(&e.EventID, &e.EventType, &e.AggregateType, &e.AggregateID, &e.PartitionKey, &payloadJSON, &e.CreatedAt); err != nil {
            return nil, err
        }
        
        if err := json.Unmarshal(payloadJSON, &e.Payload); err != nil {
            return nil, err
        }
        
        events = append(events, &e)
    }
    
    return events, rows.Err()
}
```

### 7.4 Event Payload Structures

```go
// internal/kafka/event/user_event.go

package event

import "github.com/google/uuid"

// UserProfileUpdatedEvent - Published to t.user.events
type UserProfileUpdatedEvent struct {
    EventID       uuid.UUID `json:"event_id"`
    AggregateType string    `json:"aggregate_type"`
    AggregateID   uuid.UUID `json:"aggregate_id"`
    PartitionKey  uuid.UUID `json:"partition_key"`
    EventType     string    `json:"event_type"`
    Timestamp     string    `json:"timestamp"`
    Payload       ProfilePayload `json:"payload"`
}

type ProfilePayload struct {
    ID          uuid.UUID `json:"id"`
    FirstName   string    `json:"first_name"`
    LastName    string    `json:"last_name"`
    PhoneNumber string    `json:"phone_number,omitempty"`
    DateOfBirth string    `json:"date_of_birth,omitempty"`
    Gender      string    `json:"gender"`
}

// UserPreferencesUpdatedEvent - Published to t.user.events
type UserPreferencesUpdatedEvent struct {
    EventID       uuid.UUID            `json:"event_id"`
    AggregateType string               `json:"aggregate_type"`
    AggregateID   uuid.UUID            `json:"aggregate_id"`
    PartitionKey  uuid.UUID            `json:"partition_key"`
    EventType     string               `json:"event_type"`
    Timestamp     string               `json:"timestamp"`
    Payload       PreferencesPayload   `json:"payload"`
}

type PreferencesPayload struct {
    ID               uuid.UUID `json:"id"`
    Language         string    `json:"language"`
    Currency         string    `json:"currency"`
    EmailEnabled     bool      `json:"email_enabled"`
    InAppEnabled     bool      `json:"in_app_enabled"`
    MarketingEnabled bool      `json:"marketing_enabled"`
}
```

### 7.5 Outbox Cleanup Cron

```sql
-- scripts/cleanup-outbox.sql
-- Run daily via cron job

-- Delete events older than 14 days
DELETE FROM outbox_events 
WHERE created_at < NOW() - INTERVAL '14 days';

-- Return deleted count for monitoring
SELECT 
    'Deleted outbox events' AS operation,
    COUNT(*) AS deleted_count,
    NOW() AS executed_at
FROM outbox_events 
WHERE created_at < NOW() - INTERVAL '14 days';

-- Reclaim space (optional, based on vacuum strategy)
-- VACUUM outbox_events;

-- Schedule: 0 2 * * * (2 AM daily)
```

---

## 8. API Endpoints

### 8.1 Profile Endpoints

#### GET /api/v1/users/me/profile

**Mô tả:** Lấy hồ sơ người dùng hiện tại

**Request Headers:**
```
Authorization: Bearer <jwt_token>
```

**Response 200 OK:**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "first_name": "Nguyen",
  "last_name": "Quang Dung",
  "phone_number": "0987654321",
  "date_of_birth": "1997-10-19",
  "gender": "MALE",
  "created_at": "2026-04-21T10:00:00Z",
  "updated_at": "2026-04-22T08:05:00Z"
}
```

**Response 404 Not Found:**
```json
{
  "error": {
    "code": "PROFILE_NOT_FOUND",
    "message": "Hồ sơ người dùng không tồn tại"
  }
}
```

---

#### PUT /api/v1/users/me/profile

**Mô tả:** Cập nhật hồ sơ người dùng (transactional outbox)

**Request Headers:**
```
Authorization: Bearer <jwt_token>
Content-Type: application/json
```

**Request Body:**
```json
{
  "first_name": "Nguyen",
  "last_name": "Quang Dung",
  "phone_number": "0987654321",
  "date_of_birth": "1997-10-19",
  "gender": "MALE"
}
```

**Validation Rules:**
| Field | Rules |
|:------|:------|
| `first_name` | Max 100 chars, Unicode supported |
| `last_name` | Max 100 chars, Unicode supported |
| `phone_number` | Regex: `^\+?[0-9]{9,15}$`, unique in DB |
| `date_of_birth` | ISO 8601 date, not in future |
| `gender` | Enum: `MALE`, `FEMALE`, `OTHER`, `UNKNOWN` |

**Response 200 OK:**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "first_name": "Nguyen",
  "last_name": "Quang Dung",
  "phone_number": "0987654321",
  "date_of_birth": "1997-10-19",
  "gender": "MALE",
  "created_at": "2026-04-21T10:00:00Z",
  "updated_at": "2026-04-22T08:05:00Z"
}
```

**Response 409 Conflict (phone duplicate):**
```json
{
  "error": {
    "code": "PHONE_NUMBER_EXISTS",
    "message": "Số điện thoại đã được sử dụng bởi người dùng khác"
  }
}
```

**Response 400 Bad Request:**
```json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "Dữ liệu không hợp lệ",
    "details": [
      { "field": "phone_number", "message": "Số điện thoại không đúng định dạng" }
    ]
  }
}
```

### 8.2 Address Endpoints

#### GET /api/v1/users/me/addresses

**Mô tả:** Lấy danh sách địa chỉ (Cache-Aside, Redis)

**Response 200 OK:**
```json
{
  "addresses": [
    {
      "id": "660e8400-e29b-41d4-a716-446655440001",
      "is_default": true,
      "recipient_name": "Nguyen Van A",
      "phone_number": "0912345678",
      "address_line": "123 Đường ABC, Tòa nhà XYZ",
      "ward_id": "001",
      "district_id": "001",
      "city_id": "001",
      "created_at": "2026-04-21T10:00:00Z",
      "updated_at": "2026-04-22T08:05:00Z"
    },
    {
      "id": "770e8400-e29b-41d4-a716-446655440002",
      "is_default": false,
      "recipient_name": "Nguyen Van B",
      "phone_number": "0923456789",
      "address_line": "456 Đường DEF",
      "ward_id": "002",
      "district_id": "002",
      "city_id": "001",
      "created_at": "2026-04-20T10:00:00Z",
      "updated_at": "2026-04-20T10:00:00Z"
    }
  ],
  "total": 2,
  "cached": true,
  "cache_ttl_seconds": 43200
}
```

---

#### POST /api/v1/users/me/addresses

**Mô tả:** Thêm địa chỉ mới

**Request Body:**
```json
{
  "recipient_name": "Nguyen Van A",
  "phone_number": "0912345678",
  "address_line": "123 Đường ABC, Tòa nhà XYZ",
  "ward_id": "001",
  "district_id": "001",
  "city_id": "001"
}
```

**Validation Rules:**
| Field | Rules |
|:------|:------|
| `recipient_name` | Required, max 200 chars |
| `phone_number` | Required, regex `^\+?[0-9]{9,15}$` |
| `address_line` | Required, max 500 chars |
| `ward_id` | Required, max 20 chars |
| `district_id` | Required, max 20 chars |
| `city_id` | Required, max 20 chars |

**Business Logic:**
- Nếu đây là địa chỉ đầu tiên của user → auto set `is_default = true`
- Sau khi insert → invalidate Redis cache

**Response 201 Created:**
```json
{
  "id": "660e8400-e29b-41d4-a716-446655440001",
  "is_default": true,
  "recipient_name": "Nguyen Van A",
  "phone_number": "0912345678",
  "address_line": "123 Đường ABC, Tòa nhà XYZ",
  "ward_id": "001",
  "district_id": "001",
  "city_id": "001",
  "created_at": "2026-04-21T10:00:00Z",
  "updated_at": "2026-04-21T10:00:00Z"
}
```

---

#### PUT /api/v1/users/me/addresses/{id}

**Mô tả:** Cập nhật địa chỉ

**Authorization:** Address must belong to authenticated user

**Request Body:** (partial update supported)
```json
{
  "recipient_name": "Nguyen Van A (Updated)",
  "phone_number": "0912345679",
  "address_line": "456 Đường XYZ",
  "ward_id": "003",
  "district_id": "003",
  "city_id": "002"
}
```

**Response 200 OK:** Updated address object

**Response 404 Not Found:**
```json
{
  "error": {
    "code": "ADDRESS_NOT_FOUND",
    "message": "Địa chỉ không tồn tại hoặc không thuộc về bạn"
  }
}
```

---

#### DELETE /api/v1/users/me/addresses/{id}

**Mô tả:** Xóa địa chỉ (Hard Delete)

**Business Logic:**
- Không cho xóa địa chỉ mặc định duy nhất (409 Conflict)
- Nếu xóa địa chỉ default và còn địa chỉ khác → auto-promote địa chỉ cũ nhất

**Response 204 No Content:** Success

**Response 409 Conflict:**
```json
{
  "error": {
    "code": "CANNOT_DELETE_ONLY_DEFAULT_ADDRESS",
    "message": "Không thể xóa địa chỉ mặc định duy nhất. Vui lòng thêm địa chỉ khác trước."
  }
}
```

---

#### POST /api/v1/users/me/addresses/{id}/default

**Mô tả:** Đặt địa chỉ làm mặc định

**Business Logic:**
- Sử dụng transaction để đảm bảo atomicity
- Database partial unique index đảm bảo constraint

**Response 200 OK:** Updated address object

### 8.3 Preferences Endpoints

#### GET /api/v1/users/me/preferences

**Response 200 OK:**
```json
{
  "user_id": "550e8400-e29b-41d4-a716-446655440000",
  "language": "vi",
  "currency": "VND",
  "email_enabled": true,
  "in_app_enabled": true,
  "marketing_enabled": true,
  "updated_at": "2026-04-21T10:00:00Z"
}
```

---

#### PUT /api/v1/users/me/preferences

**Mô tả:** Cập nhật preferences (transactional outbox → Kafka)

**Request Body:**
```json
{
  "language": "en",
  "currency": "USD",
  "email_enabled": true,
  "in_app_enabled": true,
  "marketing_enabled": false
}
```

**Response 200 OK:** Updated preferences object

### 8.4 Health Check Endpoints

#### GET /health

**Mô tả:** Liveness check

**Response 200 OK:**
```json
{
  "status": "healthy",
  "timestamp": "2026-04-22T08:05:00Z"
}
```

---

#### GET /ready

**Mô tả:** Readiness check (checks DB + Redis + Kafka connectivity)

**Response 200 OK:**
```json
{
  "status": "ready",
  "checks": {
    "database": "connected",
    "redis": "connected",
    "kafka": "connected"
  },
  "timestamp": "2026-04-22T08:05:00Z"
}
```

**Response 503 Service Unavailable:**
```json
{
  "status": "not_ready",
  "checks": {
    "database": "connected",
    "redis": "disconnected",
    "kafka": "connected"
  },
  "timestamp": "2026-04-22T08:05:00Z"
}
```

---

## 9. Kế Hoạch Triển Khai

### 9.1 Thứ Tự Implementation (Dependency-Based)

```
Phase 1: Foundation (Week 1-2)
├── 1.1 Project Setup & Config
│   ├── Initialize Go module
│   ├── Setup viper config
│   ├── Setup fx dependency injection
│   └── Write docker-compose.yaml
│
├── 1.2 Database Layer
│   ├── Setup sqlx connection pool
│   ├── Write all 4 migrations
│   ├── Implement ProfileRepository
│   ├── Implement AddressRepository
│   ├── Implement PreferencesRepository
│   └── Implement OutboxRepository
│
└── 1.3 Redis Layer
    ├── Setup go-redis client
    └── Implement AddressCache

Phase 2: Domain & Business Logic (Week 2-3)
├── 2.1 Domain Entities & Value Objects
│   ├── Profile entity with validation
│   ├── Address entity with validation
│   ├── Preferences entity
│   └── OutboxEvent entity
│
├── 2.2 Domain Services
│   ├── ProfileService (create, read, update)
│   ├── AddressService (CRUD + cache)
│   └── PreferencesService (read, update)
│
└── 2.3 Transaction Support
    ├── ProfileService with outbox transaction
    └── AddressService with outbox transaction

Phase 3: API Layer (Week 3-4)
├── 3.1 HTTP Handlers
│   ├── ProfileHandler
│   ├── AddressHandler
│   ├── PreferencesHandler
│   └── HealthHandler
│
├── 3.2 Middleware
│   ├── JWT Auth middleware
│   ├── Logging middleware
│   ├── Recovery middleware
│   └── Rate limit middleware
│
└── 3.3 Router Setup
    ├── API v1 routes
    └── OpenAPI documentation

Phase 4: Kafka Integration (Week 4-5)
├── 4.1 Kafka Consumer
│   ├── Consumer group manager
│   ├── UserVerifiedHandler (idempotent)
│   └── UserDeletedHandler (GDPR)
│
├── 4.2 Debezium Setup
│   ├── Outbox CDC configuration
│   └── Connector registration
│
└── 4.3 Idempotency Guards
    ├── ON CONFLICT DO NOTHING
    └── Duplicate event detection

Phase 5: Testing & Polish (Week 5-6)
├── 5.1 Unit Tests
│   ├── Domain service tests
│   ├── Repository tests (mocked)
│   └── Handler tests (mocked)
│
├── 5.2 Integration Tests
│   ├── PostgreSQL with testcontainers
│   ├── Redis with testcontainers
│   └── Kafka with testcontainers
│
├── 5.3 Load Testing
│   └── k6 script for address endpoints
│
└── 5.4 Documentation
    ├── API documentation
    └── Deployment guide
```

### 9.2 Module Dependencies Matrix

| Module | Depends On | Can Parallel With |
|:-------|:-----------|:------------------|
| `domain/entity` | - | All |
| `domain/port` | `domain/entity` | All |
| `infrastructure/postgres` | `domain/port` | `infrastructure/redis` |
| `infrastructure/redis` | `domain/port` | `infrastructure/postgres` |
| `domain/service` | `domain/port` | - |
| `api/handler` | `domain/service` | `kafka/handler` |
| `api/middleware` | - | `api/handler` |
| `kafka/handler` | `domain/service`, `infrastructure/postgres` | `api/handler` |

### 9.3 Parallel Development Strategy

**Team Structure (2-3 developers):**

```
Developer A: Domain + Infrastructure (Week 1-2)
├── Database migrations
├── Repository implementations
├── Redis cache implementation
└── Base entities and value objects

Developer B: API Layer (Week 2-3)
├── HTTP handlers
├── Middleware
├── DTOs and validation
└── Router setup

Developer A: Domain Services (Week 3)
├── ProfileService
├── AddressService
├── PreferencesService
└── Transaction management

Developer C: Kafka Integration (Week 3-4)
├── Consumer implementation
├── Event handlers
├── Idempotency logic
└── Debezium configuration
```

---

## 10. Kế Hoạch Kiểm Thử

### 10.1 Unit Test Structure

```
tests/
├── unit/
│   ├── domain/
│   │   ├── entity/
│   │   │   ├── profile_test.go
│   │   │   ├── address_test.go
│   │   │   └── preferences_test.go
│   │   ├── service/
│   │   │   ├── profile_service_test.go
│   │   │   ├── address_service_test.go
│   │   │   └── preferences_service_test.go
│   │   └── vo/
│   │       ├── phone_test.go
│   │       └── name_test.go
│   │
│   ├── handler/
│   │   ├── profile_handler_test.go
│   │   ├── address_handler_test.go
│   │   └── preferences_handler_test.go
│   │
│   └── mocks/
│       ├── profile_repo_mock.go
│       ├── address_repo_mock.go
│       ├── preferences_repo_mock.go
│       └── address_cache_mock.go
```

### 10.2 Unit Test Examples

```go
// tests/unit/domain/service/address_service_test.go

package service

import (
    "context"
    "testing"
    "time"
    
    "github.com/google/uuid"
    "github.com/stretchr/testify/assert"
    "github.com/stretchr/testify/mock"
    
    "user-service/internal/domain/entity"
    "user-service/internal/domain/service"
)

// Mock Repositories
type MockAddressRepository struct {
    mock.Mock
}

func (m *MockAddressRepository) Create(ctx context.Context, addr *entity.Address) error {
    args := m.Called(ctx, addr)
    return args.Error(0)
}

func (m *MockAddressRepository) GetByID(ctx context.Context, id uuid.UUID) (*entity.Address, error) {
    args := m.Called(ctx, id)
    if args.Get(0) == nil {
        return nil, args.Error(1)
    }
    return args.Get(0).(*entity.Address), args.Error(1)
}

func (m *MockAddressRepository) GetByUserID(ctx context.Context, userID uuid.UUID) ([]*entity.Address, error) {
    args := m.Called(ctx, userID)
    return args.Get(0).([]*entity.Address), args.Error(1)
}

func (m *MockAddressRepository) Update(ctx context.Context, addr *entity.Address) error {
    args := m.Called(ctx, addr)
    return args.Error(0)
}

func (m *MockAddressRepository) Delete(ctx context.Context, id uuid.UUID) error {
    args := m.Called(ctx, id)
    return args.Error(0)
}

func (m *MockAddressRepository) SetDefault(ctx context.Context, userID, addrID uuid.UUID) error {
    args := m.Called(ctx, userID, addrID)
    return args.Error(0)
}

type MockAddressCache struct {
    mock.Mock
}

func (m *MockAddressCache) Get(ctx context.Context, userID uuid.UUID) ([]*entity.Address, error) {
    args := m.Called(ctx, userID)
    if args.Get(0) == nil {
        return nil, args.Error(1)
    }
    return args.Get(0).([]*entity.Address), args.Error(1)
}

func (m *MockAddressCache) Set(ctx context.Context, userID uuid.UUID, addresses []*entity.Address) error {
    args := m.Called(ctx, userID, addresses)
    return args.Error(0)
}

func (m *MockAddressCache) Delete(ctx context.Context, userID uuid.UUID) error {
    args := m.Called(ctx, userID)
    return args.Error(0)
}

// Test Cases
func TestAddressService_Create_FirstAddressSetsDefault(t *testing.T) {
    // Setup
    mockRepo := new(MockAddressRepository)
    mockCache := new(MockAddressCache)
    svc := service.NewAddressService(mockRepo, mockCache)
    
    userID := uuid.New()
    
    // Mock: user has no addresses
    mockRepo.On("GetByUserID", mock.Anything, userID).Return([]*entity.Address{}, nil)
    mockRepo.On("Create", mock.Anything, mock.AnythingOfType("*entity.Address")).Return(nil)
    mockCache.On("Delete", mock.Anything, userID).Return(nil)
    
    // Execute
    addr := &entity.Address{
        UserID:        userID,
        RecipientName: "Test User",
        PhoneNumber:   "0912345678",
        AddressLine:   "123 Test St",
        WardID:        "001",
        DistrictID:    "001",
        CityID:        "001",
    }
    
    err := svc.Create(context.Background(), addr)
    
    // Assert
    assert.NoError(t, err)
    assert.True(t, addr.IsDefault, "First address should be default")
    mockRepo.AssertExpectations(t)
    mockCache.AssertExpectations(t)
}

func TestAddressService_Create_SubsequentAddress_NotDefault(t *testing.T) {
    // Setup
    mockRepo := new(MockAddressRepository)
    mockCache := new(MockAddressCache)
    svc := service.NewAddressService(mockRepo, mockCache)
    
    userID := uuid.New()
    existingAddr := &entity.Address{
        ID:        uuid.New(),
        UserID:    userID,
        IsDefault: true,
    }
    
    // Mock: user already has an address
    mockRepo.On("GetByUserID", mock.Anything, userID).Return([]*entity.Address{existingAddr}, nil)
    mockRepo.On("Create", mock.Anything, mock.AnythingOfType("*entity.Address")).Return(nil)
    mockCache.On("Delete", mock.Anything, userID).Return(nil)
    
    // Execute
    newAddr := &entity.Address{
        UserID:        userID,
        RecipientName: "Test User 2",
        PhoneNumber:   "0923456789",
        AddressLine:   "456 Test Ave",
        WardID:        "002",
        DistrictID:    "002",
        CityID:        "001",
    }
    
    err := svc.Create(context.Background(), newAddr)
    
    // Assert
    assert.NoError(t, err)
    assert.False(t, newAddr.IsDefault, "Subsequent address should NOT be default")
}

func TestAddressService_GetByUserID_CacheHit(t *testing.T) {
    // Setup
    mockRepo := new(MockAddressRepository)
    mockCache := new(MockAddressCache)
    svc := service.NewAddressService(mockRepo, mockCache)
    
    userID := uuid.New()
    cachedAddresses := []*entity.Address{
        {ID: uuid.New(), UserID: userID, IsDefault: true},
    }
    
    // Mock: cache hit
    mockCache.On("Get", mock.Anything, userID).Return(cachedAddresses, nil)
    
    // Execute
    addresses, err := svc.GetByUserID(context.Background(), userID)
    
    // Assert
    assert.NoError(t, err)
    assert.Equal(t, cachedAddresses, addresses)
    mockRepo.AssertNotCalled(t, "GetByUserID") // Should not call DB
    mockCache.AssertExpectations(t)
}

func TestAddressService_GetByUserID_CacheMiss(t *testing.T) {
    // Setup
    mockRepo := new(MockAddressRepository)
    mockCache := new(MockAddressCache)
    svc := service.NewAddressService(mockRepo, mockCache)
    
    userID := uuid.New()
    dbAddresses := []*entity.Address{
        {ID: uuid.New(), UserID: userID, IsDefault: true},
    }
    
    // Mock: cache miss, DB hit
    mockCache.On("Get", mock.Anything, userID).Return(nil, nil) // nil, nil = cache miss
    mockRepo.On("GetByUserID", mock.Anything, userID).Return(dbAddresses, nil)
    mockCache.On("Set", mock.Anything, userID, dbAddresses).Return(nil)
    
    // Execute
    addresses, err := svc.GetByUserID(context.Background(), userID)
    
    // Assert
    assert.NoError(t, err)
    assert.Equal(t, dbAddresses, addresses)
    mockCache.AssertExpectations(t)
    mockRepo.AssertExpectations(t)
}
```

### 10.3 Integration Test Structure

```go
// tests/integration/postgres_test.go

package integration

import (
    "context"
    "testing"
    "time"
    
    "github.com/stretchr/testify/assert"
    "github.com/stretchr/testify/require"
    "github.com/testcontainers/testcontainers-go"
    "github.com/testcontainers/testcontainers-go/modules/postgres"
    "github.com/testcontainers/testcontainers-go/wait"
    
    "github.com/jmoiron/sqlx"
    
    "user-service/internal/domain/entity"
    "user-service/internal/infrastructure/postgres"
)

func TestPostgresProfileRepository_Integration(t *testing.T) {
    if testing.Short() {
        t.Skip("Skipping integration test in short mode")
    }
    
    // Setup PostgreSQL container
    ctx := context.Background()
    
    pgContainer, err := postgres.Run(ctx,
        "postgres:15-alpine",
        postgres.WithDatabase("test_user_service"),
        postgres.WithUsername("test"),
        postgres.WithPassword("test"),
        testcontainers.WithWaitStrategy(
            wait.ForLog("database system is ready to accept connections").
                WithOccurrence(2).
                WithTimeout(10*time.Second),
        ),
    )
    require.NoError(t, err)
    defer func() {
        require.NoError(t, pgContainer.Terminate(ctx))
    }()
    
    // Get connection string
    connStr, err := pgContainer.ConnectionString(ctx, "sslmode=disable")
    require.NoError(t, err)
    
    // Connect
    db, err := sqlx.Connect("pgx", connStr)
    require.NoError(t, err)
    defer db.Close()
    
    // Run migrations
    runMigrations(t, db)
    
    // Setup repository
    repo := postgres.NewPostgresProfileRepository(db)
    
    // Test Create (idempotent)
    t.Run("Create_Idempotent", func(t *testing.T) {
        userID := "550e8400-e29b-41d4-a716-446655440000"
        
        profile1 := &entity.Profile{
            ID:        parseUUID(t, userID),
            FirstName: "Test",
            LastName:  "User",
        }
        
        err := repo.Create(ctx, profile1)
        assert.NoError(t, err)
        
        // Create again - should not error (ON CONFLICT DO NOTHING)
        profile2 := &entity.Profile{
            ID: parseUUID(t, userID),
        }
        
        err = repo.Create(ctx, profile2)
        assert.NoError(t, err)
        
        // Verify only one record exists
        profile, err := repo.GetByID(ctx, parseUUID(t, userID))
        assert.NoError(t, err)
        assert.Equal(t, "Test", profile.FirstName)
    })
}

func TestPostgresAddressRepository_Integration(t *testing.T) {
    // Setup similar to above
    
    t.Run("Delete_LastDefaultAddress_Fails", func(t *testing.T) {
        // Setup: Create user with one default address
        userID := uuid.New()
        
        // ... setup code ...
        
        // Execute delete
        err := addressRepo.Delete(ctx, addressID)
        
        // Assert: Should return error for last default address
        assert.Error(t, err)
        assert.Contains(t, err.Error(), "cannot delete")
    })
    
    t.Run("Delete_DefaultAddress_PromotesAnother", func(t *testing.T) {
        // Setup: Create user with default address and another address
        userID := uuid.New()
        
        // ... setup code ...
        
        // Execute delete on default
        err := addressRepo.Delete(ctx, defaultAddressID)
        
        // Assert: Should succeed and promote other address
        assert.NoError(t, err)
    })
}
```

### 10.4 Redis Integration Test

```go
// tests/integration/redis_test.go

package integration

import (
    "context"
    "testing"
    
    "github.com/alicebob/miniredis/v2"
    "github.com/redis/go-redis/v9"
    "github.com/stretchr/testify/assert"
    
    "user-service/internal/infrastructure/redis"
)

func TestRedisAddressCache_Integration(t *testing.T) {
    // Use miniredis for testing
    mr, err := miniredis.Run()
    if err != nil {
        t.Fatalf("Failed to start miniredis: %v", err)
    }
    defer mr.Close()
    
    client := redis.NewClient(&redis.Options{
        Addr: mr.Addr(),
    })
    defer client.Close()
    
    cache := redis.NewRedisAddressCache(client)
    ctx := context.Background()
    userID := parseUUID(t, "550e8400-e29b-41d4-a716-446655440000")
    
    t.Run("SetAndGet", func(t *testing.T) {
        addresses := []*entity.Address{
            {ID: uuid.New(), UserID: userID, IsDefault: true},
        }
        
        err := cache.Set(ctx, userID, addresses)
        assert.NoError(t, err)
        
        cached, err := cache.Get(ctx, userID)
        assert.NoError(t, err)
        assert.Equal(t, addresses, cached)
    })
    
    t.Run("CacheMiss", func(t *testing.T) {
        result, err := cache.Get(ctx, uuid.New())
        assert.NoError(t, err)
        assert.Nil(t, result)
    })
    
    t.Run("EmptyArray_ShortTTL", func(t *testing.T) {
        // When setting empty array, should use short TTL
        err := cache.Set(ctx, userID, []*entity.Address{})
        assert.NoError(t, err)
        
        // Verify TTL is 30 minutes, not 12 hours
        ttl := mr.TTL(mr.KEYS[0])
        assert.True(t, ttl <= 30*60*1_000_000_000) // 30 minutes in nanoseconds
    })
    
    t.Run("Delete", func(t *testing.T) {
        err := cache.Delete(ctx, userID)
        assert.NoError(t, err)
        
        result, err := cache.Get(ctx, userID)
        assert.NoError(t, err)
        assert.Nil(t, result)
    })
}
```

### 10.5 Kafka Integration Test

```go
// tests/integration/kafka_test.go

package integration

import (
    "context"
    "testing"
    "time"
    
    "github.com/IBM/sarama"
    "github.com/stretchr/testify/assert"
    "github.com/testcontainers/testcontainers-go"
    "github.com/testcontainers/testcontainers-go/modules/kafka"
    
    "user-service/internal/kafka/event"
)

func TestKafkaConsumer_Integration(t *testing.T) {
    if testing.Short() {
        t.Skip("Skipping integration test in short mode")
    }
    
    ctx := context.Background()
    
    // Start Kafka container
    kafkaContainer, err := kafka.Run(ctx,
        "confluentinc/cp-kafka:7.5.0",
        testcontainers.WithEnv(map[string]string{
            "KAFKA_AUTO_CREATE_TOPICS_ENABLE": "true",
            "KAFKA_PROCESS_ROLES":            "broker,controller",
            "KAFKA_NODE_ID":                  "1",
            "KAFKA_CONTROLLER_QUORUM_VOTERS": "1@kafka:9092",
            "KAFKA_CONTROLLER_LISTENER_NAMES": "CONTROLLER",
            "KAFKA_LISTENERS":                "INTERNAL://:9092,CONTROLLER://:9093",
            "KAFKA_ADVERTISED_LISTENERS":     "INTERNAL://localhost:9092",
            "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP": "CONTROLLER:PLAINTEXT,INTERNAL:PLAINTEXT",
        }),
    )
    require.NoError(t, err)
    defer func() {
        require.NoError(t, kafkaContainer.Terminate(ctx))
    }()
    
    brokers, err := kafkaContainer.Brokers(ctx)
    require.NoError(t, err)
    
    // Setup consumer group
    config := sarama.NewConfig()
    config.Consumer.Group.Rebalance.GroupStrategies = []sarama.BalanceStrategy{
        sarama.NewBalanceStrategyRoundRobin(),
    }
    
    consumerGroup, err := sarama.NewConsumerGroup(brokers, "test-group", config)
    require.NoError(t, err)
    defer consumerGroup.Close()
    
    t.Run("UserVerifiedSuccess_CreatesProfile", func(t *testing.T) {
        // Produce test event
        producer, err := sarama.NewSyncProducer(brokers, nil)
        require.NoError(t, err)
        defer producer.Close()
        
        authEvent := event.AuthEvent{
            EventID:       uuid.New(),
            AggregateType: "USER",
            AggregateID:   uuid.New(),
            PartitionKey:  uuid.New(),
            EventType:     "USER_VERIFIED_SUCCESS",
            OccurredAt:    time.Now().Format(time.RFC3339),
            Payload: event.AuthPayload{
                UserID: uuid.New(),
                Roles:  []string{"USER"},
            },
        }
        
        payload, _ := json.Marshal(authEvent)
        
        producer.SendMessage(&sarama.ProducerMessage{
            Topic: "t.auth.events",
            Key:   sarama.StringEncoder(authEvent.Payload.UserID.String()),
            Value: sarama.ByteEncoder(payload),
        })
        
        // Wait and verify profile was created
        time.Sleep(2 * time.Second)
        
        profile, err := profileRepo.GetByID(ctx, authEvent.Payload.UserID)
        assert.NoError(t, err)
        assert.Equal(t, authEvent.Payload.UserID, profile.ID)
    })
    
    t.Run("IdempotentProcessing", func(t *testing.T) {
        userID := uuid.New()
        
        // Produce same event twice
        for i := 0; i < 2; i++ {
            producer.SendMessage(&sarama.ProducerMessage{
                Topic: "t.auth.events",
                Key:   sarama.StringEncoder(userID.String()),
                Value: sarama.ByteEncoder([]byte(`{"event_type":"USER_VERIFIED_SUCCESS","payload":{"user_id":"` + userID.String() + `"}}`)),
            })
        }
        
        time.Sleep(3 * time.Second)
        
        // Verify only ONE profile exists
        profile, err := profileRepo.GetByID(ctx, userID)
        assert.NoError(t, err)
        
        prefs, err := preferencesRepo.GetByUserID(ctx, userID)
        assert.NoError(t, err)
        assert.NotNil(t, prefs)
    })
}
```

### 10.6 Edge Case Test Matrix

| Test Case | Scenario | Expected Result |
|:----------|:---------|:----------------|
| Create first address | User has 0 addresses | is_default = true |
| Create second address | User has 1 address | is_default = false |
| Delete only address | User has 1 address (default) | 409 Conflict |
| Delete default with others | User has 2+ addresses | Success + promote oldest |
| Set default to current | Address is already default | No-op, success |
| Update another user's address | Attempt to modify | 404 Not Found |
| Empty address list cache | User has no addresses | Cache `[]` with 30min TTL |
| Concurrent set default | Two requests simultaneously | Only one succeeds (DB constraint) |
| Phone number duplicate | Update phone to existing | 409 Conflict |
| Invalid phone format | Phone: "abc123" | 400 Bad Request |
| Profile not found | GET profile before creation | 404 Not Found |
| Kafka consumer restart | Resume from offset | No duplicate processing |

---

## 11. Deployment & DevOps

### 11.1 Environment Variables

```bash
# Application
APP_ENV=production
APP_NAME=user-service
APP_PORT=8080
APP_GRACEFUL_TIMEOUT=30s

# Database
DB_HOST=postgres
DB_PORT=5432
DB_NAME=user_service
DB_USER=${DB_USER}
DB_PASSWORD=${DB_PASSWORD}
DB_MAX_OPEN_CONNS=25
DB_MAX_IDLE_CONNS=5
DB_CONN_MAX_LIFETIME=5m

# Redis
REDIS_HOST=redis
REDIS_PORT=6379
REDIS_PASSWORD=${REDIS_PASSWORD}
REDIS_DB=0
REDIS_POOL_SIZE=10

# Kafka
KAFKA_BROKERS=kafka:9092,kafka-2:9092,kafka-3:9092
KAFKA_CONSUMER_GROUP=user-service
KAFKA_AUTH_EVENTS_TOPIC=t.auth.events
KAFKA_USER_EVENTS_TOPIC=t.user.events
KAFKA_OFFSET_RESET=earliest
KAFKA_SESSION_TIMEOUT=30s
KAFKA_HEARTBEAT_INTERVAL=3s

# Debezium (for CDC)
DEBEZIUM_ENABLED=true
DEBEZIUM_CONNECT_URL=http://debezium:8083

# Observability
OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4317
OTEL_SERVICE_NAME=user-service
LOG_LEVEL=info
LOG_FORMAT=json

# Security
JWT_SECRET=${JWT_SECRET}
JWT_ISSUER=user-service
```

### 11.2 Docker Configuration

```dockerfile
# docker/Dockerfile

FROM golang:1.21-alpine AS builder

# Install build dependencies
RUN apk add --no-cache git ca-certificates

WORKDIR /app

# Copy go mod files
COPY go.mod go.sum ./
RUN go mod download

# Copy source code
COPY . .

# Build binary
RUN CGO_ENABLED=0 GOOS=linux go build \
    -ldflags="-w -s" \
    -o user-service \
    ./cmd/server

# Final stage
FROM alpine:3.19

RUN apk add --no-cache ca-certificates tzdata

WORKDIR /app

# Copy binary from builder
COPY --from=builder /app/user-service .
COPY --from=builder /app/migrations ./migrations
COPY --from=builder /app/configs ./configs

# Create non-root user
RUN adduser -D -g '' appuser
USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/health || exit 1

ENTRYPOINT ["./user-service"]
```

### 11.3 Docker Compose (Local Development)

```yaml
# docker/docker-compose.yaml

version: '3.8'

services:
  user-service:
    build:
      context: ..
      dockerfile: docker/Dockerfile
    ports:
      - "8080:8080"
    environment:
      - APP_ENV=development
      - DB_HOST=postgres
      - DB_PORT=5432
      - DB_NAME=user_service
      - DB_USER=postgres
      - DB_PASSWORD=postgres
      - REDIS_HOST=redis
      - REDIS_PORT=6379
      - KAFKA_BROKERS=kafka:9092
      - KAFKA_CONSUMER_GROUP=user-service-dev
      - KAFKA_OFFSET_RESET=earliest
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
      kafka:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "wget", "--no-verbose", "--tries=1", "--spider", "http://localhost:8080/health"]
      interval: 30s
      timeout: 10s
      retries: 3
    networks:
      - user-svc-network

  postgres:
    image: postgres:15-alpine
    ports:
      - "5432:5432"
    environment:
      - POSTGRES_DB=user_service
      - POSTGRES_USER=postgres
      - POSTGRES_PASSWORD=postgres
    volumes:
      - postgres_data:/var/lib/postgresql/data
      - ../migrations:/docker-entrypoint-initdb.d
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres -d user_service"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - user-svc-network

  redis:
    image: redis:7.2-alpine
    ports:
      - "6379:6379"
    command: redis-server --maxmemory 256mb --maxmemory-policy allkeys-lru
    volumes:
      - redis_data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - user-svc-network

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    ports:
      - "9092:9092"
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: CONTROLLER:PLAINTEXT,INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT
      KAFKA_LISTENERS: INTERNAL://:9092,EXTERNAL://:9094,CONTROLLER://:9093
      KAFKA_ADVERTISED_LISTENERS: INTERNAL://kafka:9092,EXTERNAL://localhost:9094
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"
    volumes:
      - kafka_data:/var/lib/kafka/data
    healthcheck:
      test: ["CMD", "kafka-topics", "--bootstrap-server", "localhost:9092", "--list"]
      interval: 30s
      timeout: 10s
      retries: 5
    networks:
      - user-svc-network

  kafka-setup:
    image: confluentinc/cp-kafka:7.5.0
    depends_on:
      kafka:
        condition: service_healthy
    entrypoint: [ '/bin/sh', '-c' ]
    command: |
      "
      # Create topics
      kafka-topics --create --if-not-exists --bootstrap-server kafka:9092 --replication-factor 1 --partitions 6 --topic t.auth.events
      kafka-topics --create --if-not-exists --bootstrap-server kafka:9092 --replication-factor 1 --partitions 6 --topic t.user.events
      
      # List topics to verify
      kafka-topics --list --bootstrap-server kafka:9092
      "
    networks:
      - user-svc-network

volumes:
  postgres_data:
  redis_data:
  kafka_data:

networks:
  user-svc-network:
    driver: bridge
```

### 11.4 Kubernetes Deployment

```yaml
# k8s/deployment.yaml

apiVersion: apps/v1
kind: Deployment
metadata:
  name: user-service
  labels:
    app: user-service
spec:
  replicas: 3
  selector:
    matchLabels:
      app: user-service
  template:
    metadata:
      labels:
        app: user-service
      annotations:
        prometheus.io/scrape: "true"
        prometheus.io/port: "8080"
        prometheus.io/path: "/metrics"
    spec:
      serviceAccountName: user-service
      containers:
        - name: user-service
          image: user-service:latest
          ports:
            - containerPort: 8080
              name: http
          env:
            - name: APP_ENV
              value: "production"
            - name: APP_PORT
              value: "8080"
            - name: DB_HOST
              value: "postgres-primary"
            - name: DB_PORT
              value: "5432"
            - name: DB_NAME
              value: "user_service"
            - name: DB_USER
              valueFrom:
                secretKeyRef:
                  name: postgres-secrets
                  key: username
            - name: DB_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: postgres-secrets
                  key: password
            - name: REDIS_HOST
              value: "redis-cluster"
            - name: KAFKA_BROKERS
              value: "kafka-0:9092,kafka-1:9092,kafka-2:9092"
            - name: KAFKA_CONSUMER_GROUP
              value: "user-service-prod"
          resources:
            requests:
              memory: "128Mi"
              cpu: "100m"
            limits:
              memory: "512Mi"
              cpu: "500m"
          livenessProbe:
            httpGet:
              path: /health
              port: 8080
            initialDelaySeconds: 10
            periodSeconds: 30
            timeoutSeconds: 5
            failureThreshold: 3
          readinessProbe:
            httpGet:
              path: /ready
              port: 8080
            initialDelaySeconds: 5
            periodSeconds: 10
            timeoutSeconds: 3
            failureThreshold: 3
          lifecycle:
            preStop:
              exec:
                command: ["/bin/sh", "-c", "sleep 10"]
      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: topology.kubernetes.io/zone
          whenUnsatisfiable: ScheduleAnyway
          labelSelector:
            matchLabels:
              app: user-service
```

### 11.5 Database Secrets

```yaml
# k8s/secrets.yaml

apiVersion: v1
kind: Secret
metadata:
  name: postgres-secrets
type: Opaque
stringData:
  username: user_service
  password: <generate-strong-password>

---
apiVersion: v1
kind: Secret
metadata:
  name: redis-secrets
type: Opaque
stringData:
  password: <generate-strong-password>

---
apiVersion: v1
kind: Secret
metadata:
  name: kafka-secrets
type: Opaque
stringData:
  sasl-username: user_service
  sasl-password: <generate-strong-password>
```

### 11.6 Health Check Implementation

```go
// internal/api/handler/health_handler.go

package handler

import (
    "context"
    "net/http"
    "time"
    
    "github.com/gin-gonic/gin"
    "github.com/jmoiron/sqlx"
    "github.com/redis/go-redis/v9"
)

type HealthHandler struct {
    db    *sqlx.DB
    redis *redis.Client
    kafka *kafka.Brokers
}

type HealthResponse struct {
    Status    string `json:"status"`
    Timestamp string `json:"timestamp"`
}

type ReadinessResponse struct {
    Status  string            `json:"status"`
    Checks  map[string]string `json:"checks"`
    Timestamp string          `json:"timestamp"`
}

func (h *HealthHandler) Health(c *gin.Context) {
    c.JSON(http.StatusOK, HealthResponse{
        Status:    "healthy",
        Timestamp: time.Now().Format(time.RFC3339),
    })
}

func (h *HealthHandler) Ready(c *gin.Context) {
    ctx, cancel := context.WithTimeout(c.Request.Context(), 5*time.Second)
    defer cancel()
    
    checks := make(map[string]string)
    allHealthy := true
    
    // Check PostgreSQL
    if err := h.db.PingContext(ctx); err != nil {
        checks["database"] = "disconnected"
        allHealthy = false
    } else {
        checks["database"] = "connected"
    }
    
    // Check Redis
    if _, err := h.redis.Ping(ctx).Result(); err != nil {
        checks["redis"] = "disconnected"
        allHealthy = false
    } else {
        checks["redis"] = "connected"
    }
    
    // Check Kafka (broker connectivity)
    if !h.kafka.Healthy() {
        checks["kafka"] = "disconnected"
        allHealthy = false
    } else {
        checks["kafka"] = "connected"
    }
    
    status := "ready"
    statusCode := http.StatusOK
    if !allHealthy {
        status = "not_ready"
        statusCode = http.StatusServiceUnavailable
    }
    
    c.JSON(statusCode, ReadinessResponse{
        Status:    status,
        Checks:    checks,
        Timestamp: time.Now().Format(time.RFC3339),
    })
}
```

---

## 12. Checklist Xác Minh

### Module A: Domain (Core) - ĐÃ HOÀN THÀNH ✅

| Component | Status | Notes |
|:----------|:------:|:------|
| **Entities** | | |
| Profile Entity | ✅ | Full validation + business logic |
| Address Entity | ✅ | Auto-default + ownership check |
| Preferences Entity | ✅ | Default values + validation |
| OutboxEvent Entity | ✅ | CDC payload support |
| **Value Objects** | | |
| PhoneNumber VO | ✅ | Regex validation |
| Name VO | ✅ | Unicode + length validation |
| Gender VO | ✅ | Enum: MALE, FEMALE, OTHER, UNKNOWN |
| **Ports (Interfaces)** | | |
| ProfileRepository | ✅ | CRUD + transaction support |
| AddressRepository | ✅ | CRUD + SetDefault + Promote |
| PreferencesRepository | ✅ | CRUD + transaction support |
| OutboxRepository | ✅ | Create + GetPending + Cleanup |
| AddressCache | ✅ | Get + Set + Delete |
| **Domain Services** | | |
| ProfileService | ✅ | Create, Get, Update, Delete, Outbox |
| AddressService | ✅ | CRUD + Cache-Aside + Singleflight |
| PreferencesService | ✅ | Create, Get, Update + Outbox |
| **Domain Events** | | |
| Domain Events | ✅ | ProfileCreated, AddressCreated, etc. |
| Auth Events | ✅ | USER_VERIFIED_SUCCESS, USER_DELETED |

### 12.1 Workflow Coverage Checklist

> ⚠️ Module B (Infrastructure), C (API), D (Kafka) chua implement

| # | Workflow | API/Event | Status | Notes |
|:--|:---------|:----------|:-------|:------|
| 1 | Profile Creation | USER_VERIFIED_SUCCESS (Kafka) | ? | Module D pending |
| 2.1 | Create Address | POST /api/v1/users/me/addresses | ? | Module B ready |
| 2.2 | Read Addresses | GET /api/v1/users/me/addresses | ? | Module B ready |
| 2.3 | Update Address | PUT /api/v1/users/me/addresses/{id} | ? | Module B ready |
| 2.4 | Delete Address | DELETE /api/v1/users/me/addresses/{id} | ? | Module B ready |
| 2.5 | Set Default | POST /api/v1/users/me/addresses/{id}/default | ? | Module B ready |
| 3 | Update Profile | PUT /api/v1/users/me/profile | ? | Module B ready |
| 4 | Update Preferences | PUT /api/v1/users/me/preferences | ? | Module B ready |
| 5 | Chat Sync | USER_PROFILE_UPDATED (CDC) | ? | Module D pending |
| 6 | GDPR Delete | USER_DELETED (Kafka) | ? | Module B ready |

### 12.2 Database Schema Checklist

| # | Component | Status | Notes |
|:--|:----------|:-------|:------|
| 1 | `user_profiles` table | ☐ | Migration implemented |
| 2 | `user_addresses` table | ☐ | Migration implemented |
| 3 | `user_preferences` table | ☐ | Migration implemented |
| 4 | `outbox_events` table | ☐ | Migration implemented |
| 5 | Partial unique index | ☐ | Migration implemented |
| 6 | `updated_at` triggers | ☐ | Migration implemented |
| 7 | Migrations rollback | ☐ | Migration implemented |

### 12.3 Caching Checklist

| # | Feature | Status | Notes |
|:--|:--------|:-------|:------|
| 1 | Address cache key pattern | ✅ | Pattern defined |
| 2 | TTL 12 hours | ✅ | Implemented in service |
| 3 | TTL 30 minutes | ✅ | Implemented in service |
| 4 | Cache invalidation | ✅ | Implemented in service |
| 5 | Singleflight pattern | ✅ | Implemented in service |
| 6 | Cache miss → DB → Cache | ✅ | Implemented in service |

### 12.4 Kafka/CDC Checklist

| # | Feature | Status | Notes |
|:--|:--------|:-------|:------|
| 1 | Consumer group | ☐ | Module D pending |
| 2 | Idempotent handlers | ✅ | Logic defined |
| 3 | Outbox table | ? | Migration implemented |
| 4 | Debezium connector | ☐ | Module D pending |
| 5 | Event schemas | ✅ | All 4 events defined |
| 6 | Topic creation | ☐ | Module D pending |

### 12.5 API Checklist

| # | Endpoint | Method | Status | Notes |
|:--|:---------|:-------|:-------|:------|
| 1 | `/api/v1/users/me/profile` | GET | ☐ | Module C pending |
| 2 | `/api/v1/users/me/profile` | PUT | ☐ | Module C pending |
| 3 | `/api/v1/users/me/addresses` | GET | ☐ | Module C pending |
| 4 | `/api/v1/users/me/addresses` | POST | ☐ | Module C pending |
| 5 | `/api/v1/users/me/addresses/{id}` | PUT | ☐ | Module C pending |
| 6 | `/api/v1/users/me/addresses/{id}` | DELETE | ☐ | Module C pending |
| 7 | `/api/v1/users/me/addresses/{id}/default` | POST | ☐ | Module C pending |
| 8 | `/api/v1/users/me/preferences` | GET | ☐ | Module C pending |
| 9 | `/api/v1/users/me/preferences` | PUT | ☐ | Module C pending |
| 10 | `/health` | GET | ✅ | Implemented |
| 11 | `/ready` | GET | ✅ | Implemented |

### 12.6 Security Checklist

| # | Feature | Status | Notes |
|:--|:--------|:-------|:------|
| 1 | JWT Authentication | ☐ | Module C pending |
| 2 | Authorization check | ✅ | Address ownership in service |
| 3 | Input validation | ✅ | VO validation implemented |
| 4 | SQL injection prevention | ? | Parameterized queries |
| 5 | Rate limiting | ☐ | Module C pending |
| 6 | Sensitive data logging | ☐ | Module C pending |

### 12.7 Observability Checklist

| # | Feature | Status | Notes |
|:--|:--------|:-------|:------|
| 1 | Structured logging | ✅ | zap.Logger configured |
| 2 | Health endpoints | ✅ | /health, /ready |
| 3 | Metrics endpoint | ☐ | Module C pending |
| 4 | Distributed tracing | ☐ | Module B pending |
| 5 | Request logging | ✅ | Middleware configured |

### 12.8 Testing Checklist

| # | Test Type | Status | Notes |
|:--|:----------|:-------|:------|
| 1 | Unit tests - Domain | ☐ | Module A pending |
| 2 | Unit tests - Handlers | ☐ | Module C pending |
| 3 | Unit tests - Mocks | ☐ | Module A pending |
| 4 | Integration - PostgreSQL | ☐ | Module B pending |
| 5 | Integration - Redis | ☐ | Module B pending |
| 6 | Integration - Kafka | ☐ | Module D pending |
| 7 | Edge case tests | ☐ | Module A pending |

### 12.9 Deployment Checklist

| # | Item | Status | Notes |
|:--|:-----|:-------|:------|
| 1 | Docker image | ☐ | Pending |
| 2 | docker-compose | ☐ | Pending |
| 3 | Kubernetes manifests | ☐ | Pending |
| 4 | Secrets management | ☐ | Pending |
| 5 | Environment variables | ☐ | Pending |
| 6 | Health checks | ✅ | Implemented |
| 7 | Graceful shutdown | ✅ | Signal handling in main.go |
| 8 | Database migrations | ☐ | Pending |

---

## Phụ Lục

### A. Go Module Dependencies

```go
// go.mod

module user-service

go 1.21

require (
    // Web Framework
    github.com/gin-gonic/gin v1.9.1
    
    // Database
    github.com/jmoiron/sqlx v1.3.5
    github.com/jackc/pgx/v5 v5.5.1
    
    // Redis
    github.com/redis/go-redis/v9 v9.4.0
    
    // Kafka
    github.com/segmentio/kafka-go v0.4.47
    github.com/IBM/sarama v1.43.0
    
    // Dependency Injection
    go.uber.org/fx v1.21.0
    
    // Configuration
    github.com/spf13/viper v1.18.2
    
    // Validation
    github.com/go-playground/validator/v10 v10.17.0
    
    // Logging
    go.uber.org/zap v1.26.0
    
    // UUID
    github.com/google/uuid v1.6.0
    
    // Testing
    github.com/stretchr/testify v1.8.4
    github.com/testcontainers/testcontainers-go v0.27.0
    github.com/alicebob/miniredis/v2 v2.31.1
    github.com/golang/mock v1.6.0
    
    // Migrations
    github.com/golang-migrate/migrate/v4 v4.17.0
    
    // Traces
    go.opentelemetry.io/otel v1.22.0
    go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracegrpc v1.22.0
)
```

### B. Makefile Commands

```makefile
# Makefile

.PHONY: build test lint run docker-build docker-up docker-down migrate-up migrate-down

# Build
build:
	go build -o bin/user-service ./cmd/server

# Test
test:
	go test -v -short ./...

test-integration:
	go test -v -tags=integration ./tests/integration/...

test-coverage:
	go test -v -coverprofile=coverage.out ./...
	go tool cover -html=coverage.out -o coverage.html

# Lint
lint:
	golangci-lint run ./...

# Run locally
run:
	go run ./cmd/server

# Docker
docker-build:
	docker build -f docker/Dockerfile -t user-service:latest .

docker-up:
	docker compose -f docker/docker-compose.yaml up -d

docker-down:
	docker compose -f docker/docker-compose.yaml down

# Migrations
migrate-up:
	migrate -path migrations -database "postgres://postgres:postgres@localhost:5432/user_service?sslmode=disable" up

migrate-down:
	migrate -path migrations -database "postgres://postgres:postgres@localhost:5432/user_service?sslmode=disable" down 1

migrate-create:
	migrate create -ext sql -dir migrations -seq $(NAME)

# Development
dev:
	reflex -s -r '\.go$$' -R 'vendor/**' -R 'bin/**' go run ./cmd/server

# Generate mocks
mocks:
	mockgen -source=internal/domain/port/profile_repository.go -destination=tests/mocks/profile_repo_mock.go
	mockgen -source=internal/domain/port/address_repository.go -destination=tests/mocks/address_repo_mock.go
	mockgen -source=internal/domain/port/address_cache.go -destination=tests/mocks/address_cache_mock.go

# Dependencies
deps:
	go mod download
	go mod tidy
```

---

**Document End - Implementation Plan v1.0**
