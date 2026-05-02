# Auth Service — Implementation Plan

> **Phiên bản:** 1.0
> **Ngày:** 2026-05-02
> **Tác giả:** Software Architecture Team
> **Trạng thái:** Draft — Chờ phê duyệt
> **Công nghệ nền tảng:** Go 1.22 + Gin Framework + PostgreSQL + Redis + Kafka + Debezium CDC

---

## Mục lục

1. [Tổng quan & Mục tiêu](#1-tổng-quan--mục-tiêu)
2. [Công nghệ và Thư viện đề xuất](#2-công-nghệ-và-thư-viện-đề-xuất)
3. [Kiến trúc thư mục (Package Structure)](#3-kiến-trúc-thư-mục-package-structure)
4. [Kế hoạch phát triển theo Module](#4-kế-hoạch-phát-triển-theo-module)
5. [Các bước triển khai cụ thể (Milestones)](#5-các-bước-triển-khai-cụ-thể-milestones)
6. [Kế hoạch kiểm thử](#6-kế-hoạch-kiểm-thử)
7. [Hướng dẫn vận hành & Cấu hình](#7-hướng-dẫn-vận-hành--cấu-hình)
8. [Rủi ro và Phương án giảm thiểu](#8-rủi-ro-và-phương-án-giảm-thiểu)
9. [Phụ lục](#9-phụ-lục)

---

## 1. Tổng quan & Mục tiêu

### 1.1. Bối cảnh

Auth Service là **Identity Provider** trung tâm của hệ thống E-Commerce Microservices, chịu trách nhiệm:

- Đăng ký / Xác thực Email / Đăng nhập người dùng
- Quản lý vòng đời Dual Token (Access Token JWT + Opaque Refresh Token)
- Cấp phát và xoay vòng khóa JWKS cho API Gateway
- Quản lý phiên đa thiết bị (Session Management)
- Ghi nhật ký kiểm toán bảo mật (Audit Logging)
- Phát và tiêu thụ sự kiện qua Kafka

Hệ thống đã có codebase Go/Gin với một số chức năng nền tảng (login, logout, token refresh, password change/reset, session management, rate limiting). Tuy nhiên, một số cơ chế bảo mật quan trọng (Grace Period 10s, JWKS rotation, Outbox Pattern, Background Workers, Kafka Consumer, Distributed Lock) **chưa được triển khai hoặc chưa hoàn chỉnh**.

### 1.2. Mục tiêu chiến lược

| # | Mục tiêu | Độ ưu tiên |
|---|-----------|------------|
| 1 | Hoàn thiện **Grace Period 10s** bằng Redis TTL cho Refresh Token Rotation | Cao |
| 2 | Triển khai **JWKS + Key Rotation** (RSA asymmetric key pairs) | Cao |
| 3 | Chuyển đổi sang **Outbox Pattern + Debezium CDC** thay direct Kafka publish | Cao |
| 4 | Triển khai **Kafka Consumer** cho ADMIN_FORCE_LOGOUT / USER_BANNED | Cao |
| 5 | Triển khai **Background Jobs** (cleanup token, audit log, key rotation) với Distributed Lock | Trung bình |
| 6 | Triển khai **Audit Logging Service** đầy đủ | Trung bình |
| 7 | Triển khai **Redis Pub/Sub** cho JWKS cache invalidation | Trung bình |
| 8 | Bổ sung **Security Enhancements** (2FA stub, session count limit, impossible travel với grace period) | Thấp |

### 1.3. Thiết kế bảo mật tổng quan

```
┌─────────────────────────────────────────────────────────────────────┐
│                          CLIENT (SPA/Mobile)                         │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│                         API GATEWAY                                  │
│  • Verify JWT signature bằng JWKS cache (Caffeine, TTL 10 phút)     │
│  • Check Redis: auth:denylist:{jti} — Denylist Access Token          │
│  • Check Redis: auth:revoked_before:{user_id} — Force Logout         │
│  • Redis Pub/Sub listener: auth:jwks:update → invalidate cache       │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        AUTH SERVICE (Go/Gin)                         │
│                                                                      │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐     │
│  │  Controllers │  │   Services   │  │   Repositories       │     │
│  │  (Handlers)  │──│  (Use Cases) │──│  (PG + Redis)        │     │
│  └──────────────┘  └──────────────┘  └──────────────────────┘     │
│         │                  │                      │                   │
│         ▼                  ▼                      ▼                   │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐     │
│  │ JWKS Manager │  │ Token Service │  │ Outbox Event Table   │     │
│  │ (RSA Keys)   │  │ (Rotation +   │  │       ↓               │     │
│  │              │  │  Grace Period)│  │  Debezium CDC         │     │
│  └──────────────┘  └──────────────┘  │  (WAL → Kafka)        │     │
│         │                  │          └──────────────────────┘     │
│         ▼                  ▼                      │                   │
│  ┌──────────────┐  ┌──────────────┐              │                   │
│  │ Redis Cache  │  │  Redis TTL   │  ┌──────────────────────┐     │
│  │ (Caffeine L1)│  │  Grace Period│  │    Kafka             │     │
│  │              │  │  Denylist    │  │  Publishers/Consumers │     │
│  └──────────────┘  │  Rate Limit  │  └──────────────────────┘     │
│         │           │  OTP Store   │                                │
│         ▼           │  Reset PW    │  ┌──────────────────────┐     │
│  ┌──────────────┐  └──────────────┘  │  Background Jobs      │     │
│  │ Redis        │         │          │  (ShedLock/Lock)    │     │
│  │ Pub/Sub      │         │          └──────────────────────┘     │
│  │ (auth:jwks:  │         │                                         │
│  │  update)     │         ▼                                         │
│  └──────────────┘  ┌──────────────┐                                │
│                    │ Audit Log    │                                 │
│                    │ Service      │                                 │
│                    └──────────────┘                                 │
│                           │                                         │
│                           ▼                                         │
│  ┌──────────────────────────────────────────────────────────┐       │
│  │              PostgreSQL (Source of Truth)                │       │
│  │  users │ refresh_tokens │ outbox_events │ jwt_keys     │       │
│  │  audit_logs │ login_attempts │ password_reset_tokens   │       │
│  └──────────────────────────────────────────────────────────┘       │
└─────────────────────────────────────────────────────────────────────┘
```

### 1.4. Token Lifecycle tổng quan

```
Access Token (JWT)
  ├── Format:   JWT RS256 (self-contained, jti bắt buộc)
  ├── TTL:      15 phút
  ├── Signature: RSA private key (kid from header)
  ├── Verify:   API Gateway dùng JWKS public key
  └── Revoke:  Redis: SET auth:denylist:{jti} 1 EX {remaining_seconds}

Refresh Token (Opaque)
  ├── Format:   Random string (UUID v4 / crypto/rand)
  ├── Storage:  SHA-256 hash in PostgreSQL; raw string → HttpOnly Cookie
  ├── TTL:      30 ngày
  ├── Rotation: Mỗi lần refresh sinh cặp token mới
  └── Revoke:   DB: SET revoked_at = NOW()

Grace Period (Refresh Rotation Guard)
  ├── Problem:  Nhiều request /refresh cùng lúc từ client
  ├── Solution: Redis key auth:refresh_grace:{old_rt_hash}
  ├── TTL:      10 giây
  └── Logic:    HIT → trả lại tokens mới; MISS → token theft

User Revision Timestamp (Force Logout All)
  └── Redis: SET auth:revoked_before:{user_id} {timestamp}
      → Gateway chặn mọi JWT có iat ≤ revoked_before
```

---

## 2. Công nghệ và Thư viện đề xuất

### 2.1. Ngôn ngữ và Framework

| Component | Technology | Version | Ghi chú |
|-----------|-----------|---------|---------|
| Ngôn ngữ | Go | 1.22+ | Hiện tại dùng Gin; giữ nguyên |
| HTTP Framework | Gin | v1.10.0 | Giữ nguyên |
| JWT Library | golang-jwt/jwt | v5.3.1 | Mở rộng hỗ trợ RS256 + JWKS |
| ORM/Query Builder | pgx/v5 | v5.5.5 | Giữ nguyên, bổ sung sqlc |

### 2.2. Database & Cache

| Component | Technology | Version | Ghi chú |
|-----------|-----------|---------|---------|
| Primary DB | PostgreSQL | 15+ | Giữ nguyên connection pool |
| Cache / Session | Redis | 7+ | go-redis/v9, giữ nguyên |
| Query Code Gen | sqlc | 1.25+ | Thay thế manual SQL strings |
| CDC (Outbox) | Debezium | 2.x | PostgreSQL WAL connector |
| Distributed Lock | Redisson | — | Hoặc dùng Redigo + Lua script |

### 2.3. Messaging & Events

| Component | Technology | Version | Ghi chú |
|-----------|-----------|---------|---------|
| Message Broker | Apache Kafka | 3.x | Đã có confluent-kafka-go/v2 |
| Event Schema | JSON | — | CloudEvents spec |
| Pub/Sub Signal | Redis Pub/Sub | — | cho JWKS update notification |

### 2.4. Security

| Component | Library | Mục đích |
|-----------|---------|---------|
| Password Hashing | golang.org/x/crypto/argon2 | Thay bcrypt cho mật khẩu mới |
| Token Hashing | SHA-256 | Refresh token storage |
| RSA Key Gen | crypto/rsa (stdlib) | JWKS key pairs |
| JWKS Cache | caffeine | In-memory L1 cache |

### 2.5. Background Jobs

| Component | Library | Ghi chú |
|-----------|---------|---------|
| Scheduler | robfig/cron/v3 | Thay thế manual ticker |
| Distributed Lock | go-redis + Redisson-style Lua | Lock key prefix: `lock:` |

### 2.6. Testing

| Component | Library | Ghi chú |
|-----------|---------|---------|
| Unit Test | standard testing + testify | |
| Integration Test | testcontainers/testcontainers-go | PostgreSQL, Redis, Kafka |
| Load Test | k6 | Script mẫu trong docs |
| Mock |gomock / testify/mock | |

### 2.7. Operations & Observability

| Component | Library |
|-----------|---------|
| Structured Logging | uber-go/zap |
| Config Management | spf13/viper (đã có) |
| Health Checks | /health, /ready endpoints |
| Metrics (tuỳ chọn) | prometheus/client_golang |

---

## 3. Kiến trúc thư mục (Package Structure)

Dưới đây là cấu trúc thư mục **mở rộng** dựa trên codebase hiện tại. Các thư mục mới được đánh dấu `(NEW)`:

```
go/services/auth-service/
├── cmd/
│   └── server/
│       ├── main.go              ← Bổ sung: khởi tạo workers, consumers
│       └── wire.go               ← Dependency injection
│
├── config/
│   └── application.yaml          ← Mở rộng thêm config cho workers, kafka
│
├── internal/
│   ├── config/
│   │   └── config.go             ← Mở rộng: JWKS config, worker config, kafka config
│   │
│   ├── domain/
│   │   ├── entity/               ← Đã có: refresh_token, session, token, otp, ...
│   │   │   ├── audit_log.go      ← (NEW) Entity cho audit log
│   │   │   ├── jwt_key.go        ← (NEW) Entity cho JWKS key
│   │   │   └── outbox_event.go   ← (NEW) Entity cho outbox
│   │   ├── repository/
│   │   │   ├── auth_repository.go    ← Mở rộng: audit log, jwt key
│   │   │   ├── cache_repository.go   ← Mở rộng: grace period, revoke-before
│   │   │   ├── outbox_repository.go  ← (NEW) Outbox event repository
│   │   │   └── audit_repository.go   ← (NEW) Audit log repository
│   │   ├── serr/
│   │   │   └── errors.go
│   │   └── types/
│   │       ├── blacklist_types.go    ← Mở rộng: GracePeriodData struct
│   │       ├── event_types.go        ← (NEW) Kafka event type constants
│   │       └── token_types.go        ← (NEW) JWKS key status enum
│   │
│   ├── usecase/                  ← Business logic chính
│   │   ├── common/
│   │   │   ├── interfaces.go     ← Mở rộng interface
│   │   │   └── model.go
│   │   ├── dto/
│   │   │   └── auth_dto.go       ← Mở rộng DTO cho audit, JWKS
│   │   ├── authentication/
│   │   │   ├── login.go          ← Đã có: bổ sung audit log write
│   │   │   ├── logout.go         ← Đã có: mở rộng audit + Kafka event
│   │   │   └── refresh.go        ← CRITICAL: Sửa lại Grace Period 10s Redis TTL
│   │   ├── password/
│   │   │   ├── change.go         ← Bổ sung: revoke_before + audit
│   │   │   └── reset.go          ← Bổ sung: outbox event + audit
│   │   ├── registration/
│   │   │   ├── managed.go        ← Mở rộng: outbox event
│   │   │   └── personal.go
│   │   ├── session/
│   │   │   └── manager.go
│   │   ├── security/
│   │   │   ├── token_generator.go    ← CRITICAL: Đổi sang RS256
│   │   │   └── password_encoder.go
│   │   ├── audit/                    ← (NEW) Audit logging use case
│   │   │   ├── service.go            ← Ghi log, query log
│   │   │   └── dto.go
│   │   ├── jwks/                     ← (NEW) JWKS management
│   │   │   ├── service.go            ← Generate key, get JWKS
│   │   │   ├── cache.go              ← Caffeine L1 cache
│   │   │   └── rotator.go            ← Key rotation logic
│   │   └── grace/                    ← (NEW) Grace period helper
│   │       └── service.go            ← Redis grace cache operations
│   │
│   ├── delivery/
│   │   └── http/
│   │       └── v1/
│   │           ├── handler/
│   │           │   ├── handler_authentication.go
│   │           │   ├── handler_password.go
│   │           │   ├── handler_registration.go
│   │           │   ├── handler_session.go
│   │           │   ├── handler_audit.go       ← (NEW) Audit endpoints
│   │           │   └── handler_jwks.go       ← (NEW) JWKS endpoint
│   │           ├── middleware/
│   │           │   ├── activity_tracker.go
│   │           │   ├── auth.go
│   │           │   ├── cors.go
│   │           │   ├── logger.go
│   │           │   ├── middleware.go
│   │           │   ├── rate_limit.go         ← Mở rộng thêm các pattern
│   │           │   ├── recovery.go
│   │           │   └── request_id.go
│   │           └── router/
│   │               ├── auth_routes.go
│   │               ├── password_routes.go
│   │               ├── registration_routes.go
│   │               ├── session_routes.go
│   │               ├── audit_routes.go       ← (NEW)
│   │               └── jwks_routes.go         ← (NEW)
│   │
│   ├── infrastructure/
│   │   ├── database/
│   │   │   └── postgres/
│   │   │       └── postgre_conn.go
│   │   ├── event/
│   │   │   ├── kafka_event_publisher.go  ← Đã có: chuyển sang outbox adapter
│   │   │   ├── outbox_publisher.go      ← (NEW) Insert vào outbox table
│   │   │   ├── kafka_consumer.go         ← (NEW) Consumer cho admin events
│   │   │   └── event_registry.go        ← (NEW) Event type → payload builder
│   │   ├── external/
│   │   │   ├── notification_service_client.go
│   │   │   └── user_service_client.go
│   │   └── redis_pubsub/                 ← (NEW)
│   │       ├── publisher.go              ← Redis Pub/Sub cho JWKS update
│   │       └── subscriber.go             ← Consumer cho local cache invalidation
│   │
│   ├── repository/
│   │   ├── pg_adapter/
│   │   │   ├── generated/               ← sqlc generated code
│   │   │   │   ├── db.go
│   │   │   │   ├── models.go
│   │   │   │   ├── querier.go
│   │   │   │   └── *...sql.go
│   │   │   ├── login_attempt_repository.go
│   │   │   ├── password_reset_repository.go
│   │   │   ├── session_repository.go     ← Mở rộng: FOR UPDATE, family_id
│   │   │   ├── audit_repository.go       ← (NEW)
│   │   │   ├── jwt_key_repository.go     ← (NEW)
│   │   │   └── outbox_repository.go      ← (NEW)
│   │   └── redis_adapter/
│   │       ├── blacklist_repository.go   ← Mở rộng: grace period cache
│   │       ├── otp_repository.go
│   │       ├── ratelimit_repository.go
│   │       ├── refresh_token_repository.go
│   │       ├── registration_session_repository.go
│   │       ├── grace_cache.go            ← (NEW) auth:refresh_grace:{hash}
│   │       ├── revoke_before_cache.go    ← (NEW) auth:revoked_before:{user_id}
│   │       └── reset_pw_cache.go
│   │
│   ├── worker/                          ← (NEW) Background jobs
│   │   ├── scheduler.go                 ← Cron scheduler với ShedLock
│   │   ├── token_cleanup.go             ← Cleanup expired/revoked tokens
│   │   ├── audit_cleanup.go            ← Cleanup old audit logs
│   │   └── key_rotation.go             ← JWKS key rotation worker
│   │
│   └── utils/
│       ├── crypto.go                    ← Mở rộng: RSA key gen/encrypt
│       ├── mask.go
│       ├── password.go
│       ├── random.go
│       └── hash.go                      ← (NEW) SHA-256 wrapper cho token hash
│
├── sql/
│   ├── schema/
│   │   ├── 001_auth_tables.sql          ← Đã có (sessions, login_attempts, ...)
│   │   ├── 002_add_last_activity.sql    ← Đã có
│   │   ├── 003_audit_logs.sql           ← (NEW) Bảng audit_logs
│   │   ├── 004_jwt_keys.sql            ← (NEW) Bảng jwt_keys
│   │   ├── 005_outbox_events.sql       ← (NEW) Bảng outbox_events
│   │   ├── 006_add_family_id.sql       ← (NEW) Thêm family_id cho refresh_tokens
│   │   └── 007_add_grace_fields.sql   ← (NEW) Bổ sung grace period tracking
│   ├── queries/
│   │   ├── login_attempts.sql
│   │   ├── password_reset_tokens.sql
│   │   ├── sessions.sql                ← Mở rộng: FOR UPDATE query
│   │   ├── audit_logs.sql             ← (NEW)
│   │   ├── jwt_keys.sql               ← (NEW)
│   │   └── outbox_events.sql          ← (NEW)
│   └── migrations/                      ← (NEW) Flyway/Liquibase migration files
│       └── V1__initial_schema.sql
│
├── config/
│   ├── application.yaml                 ← Mở rộng
│   └── logback.xml                      ← (hoặc zap JSON config)
│
├── deployments/                         ← (NEW)
│   ├── docker-compose.yml               ← Dev environment
│   ├── debezium/
│   │   ├── connector.json              ← Debezium PostgreSQL connector config
│   │   └── README.md
│   └── kafka/
│       └── topics.json                  ← Kafka topic definition
│
├── docs/
│   └── api/                             ← OpenAPI spec
│       └── auth-api.yaml
│
├── scripts/                             ← (NEW)
│   ├── init_jwt_keys.sql                ← Khởi tạo cặp key RSA ban đầu
│   ├── rotate_key.sql                  ← Script xoay key thủ công
│   └── seed_debezium.sh
│
└── tests/
    ├── integration/                     ← Integration test với testcontainers
    │   ├── postgres_test.go
    │   ├── redis_test.go
    │   ├── kafka_test.go
    │   ├── grace_period_test.go        ← CRITICAL: test race condition
    │   ├── token_theft_test.go
    │   ├── jwks_test.go
    │   └── outbox_test.go
    └── e2e/                            ← E2E test scenarios
        └── auth_flow_test.go
```

---

## 4. Kế hoạch phát triển theo Module

### Module 1: Project Initialization & Common Foundation

**Trưởng nhóm:** DevOps / Platform Team
**Ước lượng:** 3–5 ngày

#### 1.1 Mục tiêu

Thiết lập nền tảng hạ tầng để các module khác phát triển song song. Module này đảm bảo codebase có cấu hình nhất quán, thư viện chuẩn, và các tiện ích dùng chung.

#### 1.2 Các file cần tạo / cập nhật

| File | Hành động | Mô tả |
|------|-----------|--------|
| `go.mod` | Cập nhật | Thêm dependencies mới (cron, caffeine, argon2, sqlc, testcontainers, redisson-style lock) |
| `config/application.yaml` | Mở rộng | Thêm các config block mới |
| `internal/config/config.go` | Mở rộng | Struct cho worker, kafka, redis pub/sub, jwks |
| `internal/utils/hash.go` | Tạo mới | SHA-256 wrapper: `HashToken(token string) string` |
| `internal/domain/types/event_types.go` | Tạo mới | Event type constants: `USER_OTP_REQUESTED`, `USER_VERIFIED_SUCCESS`, `USER_DELETED`, `TOKEN_THEFT_DETECTED`, `PASSWORD_RESET_REQUESTED` |
| `internal/domain/types/token_types.go` | Tạo mới | Enum: `KeyStatus` (ACTIVE, PHASE_OUT, RETIRED), `ActorType`, `SecurityLevel`, `LogCategory` |
| `internal/domain/serr/errors.go` | Mở rộng | Thêm các error code mới: `ErrTokenTheft`, `ErrGracePeriodExpired`, `ErrKeyNotFound` |
| `internal/usecase/common/interfaces.go` | Mở rộng | Thêm interface `GraceCacheRepository`, `AuditRepository`, `JwksService`, `OutboxRepository` |
| `config/constants.go` hoặc `internal/domain/constants.go` | Tạo mới | Tập hợp tất cả constant: Redis key prefix, Kafka topic, TTL values, role names |

#### 1.3 Chi tiết các thành phần

**Constants cần định nghĩa:**

```go
// internal/domain/constants.go

package domain

// Redis Key Prefixes
const (
    RedisKeyPrefixDenylist        = "auth:denylist:"          // auth:denylist:{jti}
    RedisKeyPrefixRevokedBefore   = "auth:revoked_before:"    // auth:revoked_before:{user_id}
    RedisKeyPrefixRefreshGrace    = "auth:refresh_grace:"     // auth:refresh_grace:{old_rt_hash}
    RedisKeyPrefixRateLimitLogin  = "auth:rate_limit:login:"  // auth:rate_limit:login:{ip}
    RedisKeyPrefixRateLimitReg    = "auth:rate_limit:register:"
    RedisKeyPrefixRateLimitForgot = "auth:rate_limit:forgot_pw:"
    RedisKeyPrefixRateLimitResend = "auth:rate_limit:resend_otp:"
    RedisKeyPrefixOTP             = "auth:otp:register:"      // auth:otp:register:{email}
    RedisKeyPrefixResetPW         = "auth:reset_pw:"          // auth:reset_pw:{token_hash}
    RedisKeyPrefixLock            = "lock:"                   // Distributed lock prefix
    RedisChannelJwksUpdate        = "auth:jwks:update"        // Redis Pub/Sub channel
)

// Token TTLs
const (
    AccessTokenTTL        = 15 * time.Minute
    RefreshTokenTTL       = 30 * 24 * time.Hour // 30 days
    GracePeriodTTL        = 10 * time.Second
    RateLimitWindow       = 5 * time.Minute
    OtpTTL                = 5 * time.Minute
    ResetPasswordTTL      = 15 * time.Minute
    RevokedBeforeTTL      = 16 * time.Minute // Access TTL + 1 min buffer
)

// Kafka Topics
const (
    KafkaTopicAuthEvents  = "t.auth.events"
    KafkaTopicAdminEvents = "t.admin.events"
)

// JWT
const (
    JwtAlgorithm         = "RS256"
    JwtIssuer            = "auth-service"
    KeyRotationInterval   = 90 * 24 * time.Hour // 3 months
    PhaseOutGraceDays     = 7                  // days to keep PHASE_OUT key
)

// Database cleanup intervals
const (
    TokenCleanupRetention = 7 * 24 * time.Hour // 7 days after expiry/revoke
    AuditLogRetentionUSER = 180 * 24 * time.Hour // 6 months
    OutboxRetentionDays   = 14
)

// Rate limits
const (
    MaxLoginAttempts     = 10
    MaxLoginBlockMinutes = 60
    MaxRegisterPerHour   = 5
    MaxForgotPwPerHour   = 3
    MaxResendOtpPerMin   = 1
)
```

#### 1.4 Cấu hình application.yaml mở rộng

```yaml
auth:
  jwt:
    algorithm: RS256
    issuer: auth-service
    access-token-ttl: 15m
    refresh-token-ttl: 720h   # 30 days
    grace-period-ttl: 10s
  
  keys:
    rotation-interval: 2160h  # 90 days
    phase-out-days: 7
  
  redis:
    revoked-before-ttl: 16m   # access TTL + 1 min

kafka:
  brokers:
    - localhost:9092
  consumer:
    group-id: auth-service-consumer
    auto-offset-reset: earliest
  topics:
    auth-events: t.auth.events
    admin-events: t.admin.events

debezium:
  connector-name: auth-outbox-connector
  database-host: localhost
  database-port: 5432
  database-name: auth_db

workers:
  enabled: true
  token-cleanup:
    cron: "0 2 * * *"      # 2 AM daily
    retention-days: 7
  audit-cleanup:
    cron: "0 3 * * *"      # 3 AM daily
    retention-days: 180
  key-rotation:
    cron: "0 0 1 */3 *"    # 00:00 on 1st of every 3rd month
```

#### 1.5 Phụ thuộc giữa các module

```
Module 1 (Foundation)
    ├── Outputs: constants, config structs, error codes
    └── Consumed by: TẤT CẢ các module còn lại
```

#### 1.6 Bước thực hiện chi tiết

1. **Bước 1.1:** Cập nhật `go.mod` với các dependency mới:
   - `github.com/robfig/cron/v3` — Cron scheduler
   - `github.com/bluele/gcache` hoặc `github.com/coocood/freecache` — Thay Caffeine vì Go không có Caffeine chính thức; đề xuất `github.com/dgraph-io/ristretto` (高性能, pure Go)
   - `golang.org/x/crypto/argon2` — Thay thế bcrypt cho password hashing
   - `github.com/sqlc-dev/sqlc` — SQL code generation
   - `github.com/testcontainers/testcontainers-go` — Integration test containers
   - `github.com/golang/mock` hoặc `github.com/stretchr/testify/mock` — Mocking
   - `github.com/googleapis/gax-go/v2` — GRPC helper (nếu cần)

2. **Bước 1.2:** Tạo `internal/domain/constants.go` — tập trung mọi constant tránh magic number

3. **Bước 1.3:** Cập nhật `internal/config/config.go` — thêm structs cho Kafka, Workers, Debezium, JWKS

4. **Bước 1.4:** Tạo `internal/utils/hash.go` — SHA-256 cho token hash

5. **Bước 1.5:** Tạo `internal/domain/types/event_types.go` và `token_types.go`

6. **Bước 1.6:** Mở rộng `internal/domain/serr/errors.go` với error codes mới

7. **Bước 1.7:** Cập nhật `config/application.yaml` và parse trong `config.go`

---

### Module 2: Database Migration & Entities

**Trưởng nhóm:** Backend Team 1
**Ước lượng:** 4–6 ngày
**Lưu ý:** Có thể làm song song với Module 1

#### 2.1 Mục tiêu

Tạo toàn bộ schema database theo thiết kế, bao gồm các bảng mới cần thiết cho JWKS, Audit Log, Outbox Pattern, và các cột bổ sung cho Refresh Token.

#### 2.2 Các file cần tạo

| File | Mô tả |
|------|--------|
| `sql/schema/003_audit_logs.sql` | Bảng `audit_logs` đầy đủ theo thiết kế |
| `sql/schema/004_jwt_keys.sql` | Bảng `jwt_keys` cho keystore |
| `sql/schema/005_outbox_events.sql` | Bảng `outbox_events` theo Debezium spec |
| `sql/schema/006_add_family_id.sql` | Thêm `family_id`, `replaced_by` vào bảng `auth_sessions`/`refresh_tokens` |
| `sql/schema/007_add_grace_fields.sql` | Cột tracking grace period |
| `sql/queries/audit_logs.sql` | SQL queries cho audit log |
| `sql/queries/jwt_keys.sql` | SQL queries cho JWKS |
| `sql/queries/outbox_events.sql` | SQL queries cho outbox |
| `internal/domain/entity/audit_log.go` | Entity struct |
| `internal/domain/entity/jwt_key.go` | Entity struct |
| `internal/domain/entity/outbox_event.go` | Entity struct |
| `internal/repository/pg_adapter/audit_repository.go` | Repository implementation |
| `internal/repository/pg_adapter/jwt_key_repository.go` | Repository implementation |
| `internal/repository/pg_adapter/outbox_repository.go` | Repository implementation |
| `deployments/debezium/connector.json` | Debezium connector config |
| `scripts/init_jwt_keys.sql` | Script khởi tạo cặp key RSA đầu tiên |

#### 2.3 Chi tiết bảng mới

**Bảng `audit_logs`** (theo `auth_db_design.md`):

```sql
CREATE TABLE IF NOT EXISTS audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id VARCHAR(255) NOT NULL,
    actor_type VARCHAR(50) NOT NULL,  -- USER, ADMIN, SYSTEM, GUEST
    event_type VARCHAR(100) NOT NULL,  -- USER_LOGGED_IN, USER_PASSWORD_CHANGED, ...
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    ip_address VARCHAR(45),
    device_id VARCHAR(255),
    device_info VARCHAR(255),
    security_level VARCHAR(50) NOT NULL DEFAULT 'INFO',  -- INFO, WARNING, CRITICAL
    payload JSONB,
    log_category VARCHAR(50) NOT NULL DEFAULT 'USER',  -- USER, SYSTEM
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_logs_actor_id ON audit_logs(actor_id);
CREATE INDEX idx_audit_logs_event_type ON audit_logs(event_type);
CREATE INDEX idx_audit_logs_occurred_at ON audit_logs(occurred_at);
CREATE INDEX idx_audit_logs_security_level ON audit_logs(security_level);
CREATE INDEX idx_audit_logs_log_category ON audit_logs(log_category);
-- Composite index cho admin query
CREATE INDEX idx_audit_logs_category_occurred ON audit_logs(log_category, occurred_at DESC);
```

**Bảng `jwt_keys`** (theo `auth_db_design.md`):

```sql
CREATE TABLE IF NOT EXISTS jwt_keys (
    kid VARCHAR(100) PRIMARY KEY,
    public_key TEXT NOT NULL,
    private_key_encrypted TEXT NOT NULL,  -- Mã hóa bằng application-level key hoặc KMS
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE, PHASE_OUT, RETIRED
    algorithm VARCHAR(20) NOT NULL DEFAULT 'RS256',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    rotated_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE,
    version INT NOT NULL DEFAULT 1  -- Version counter cho optimistic locking
);

CREATE INDEX idx_jwt_keys_status ON jwt_keys(status);
```

**Bảng `outbox_events`** (Debezium-compatible):

```sql
CREATE TABLE IF NOT EXISTS outbox_events (
    event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type VARCHAR(100) NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    partition_key UUID NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    metadata JSONB  -- Debezium metadata
);

CREATE INDEX idx_outbox_events_created_at ON outbox_events(created_at);
CREATE INDEX idx_outbox_events_aggregate ON outbox_events(aggregate_type, aggregate_id);
CREATE INDEX idx_outbox_events_partition ON outbox_events(partition_key);
```

**Migration thêm vào bảng sessions/refresh_tokens hiện có:**

```sql
-- Thêm family_id để track token lineage
ALTER TABLE auth_sessions ADD COLUMN IF NOT EXISTS family_id UUID DEFAULT gen_random_uuid();
ALTER TABLE auth_sessions ADD COLUMN IF NOT EXISTS replaced_by VARCHAR(255);
ALTER TABLE auth_sessions ADD COLUMN IF NOT EXISTS grace_expires_at TIMESTAMP WITH TIME ZONE;
```

#### 2.4 Mã hóa Private Key

**Chiến lược bảo mật `private_key` trong bảng `jwt_keys`:**

1. **Tùy chọn 1 — Application-Level Encryption (Khuyến nghị cho giai đoạn đầu):**
   - Sinh master key 256-bit từ environment variable hoặc Vault
   - Dùng AES-256-GCM encrypt private key trước khi lưu vào DB
   - Decrypt khi cần ký JWT

2. **Tùy chọn 2 — KMS (cho production):**
   - Dùng AWS KMS hoặc HashiCorp Vault để sinh và lưu khóa
   - Auth Service chỉ giữ reference ID, không lưu private key plaintext

3. **Triển khai mẫu (Tùy chọn 1):**

```go
// internal/utils/crypto.go

const (
    masterKeyEnvVar = "AUTH_MASTER_KEY" // 32-byte hex-encoded key
)

func init() {
    // Validate master key on startup
    key := os.Getenv(masterKeyEnvVar)
    if len(key) != 64 {
        panic("AUTH_MASTER_KEY must be 64 hex characters (256 bits)")
    }
}

func EncryptPrivateKey(privateKeyPEM string) (string, error) {
    key, _ := hex.DecodeString(os.Getenv(masterKeyEnvVar))
    block, _ := aes.NewCipher(key)
    gcm, _ := cipher.NewGCM(block)
    nonce := make([]byte, gcm.NonceSize())
    io.ReadFull(rand.Reader, nonce)
    sealed := gcm.Seal(nonce, nonce, []byte(privateKeyPEM), nil)
    return hex.EncodeToString(sealed), nil
}

func DecryptPrivateKey(encrypted string) (string, error) {
    key, _ := hex.DecodeString(os.Getenv(masterKeyEnvVar))
    data, _ := hex.DecodeString(encrypted)
    block, _ := aes.NewCipher(key)
    gcm, _ := cipher.NewGCM(block)
    nonceSize := gcm.NonceSize()
    nonce, ciphertext := data[:nonceSize], data[nonceSize:]
    opened, err := gcm.Open(nil, nonce, ciphertext, nil)
    return string(opened), err
}
```

#### 2.5 Debezium Connector Config

```json
{
  "name": "auth-outbox-connector",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "database.hostname": "localhost",
    "database.port": "5432",
    "database.user": "auth_user",
    "database.password": "auth_password",
    "database.dbname": "auth_db",
    "topic.prefix": "auth",
    "table.include.list": "public.outbox_events",
    "publication.autocreate.mode": "filtered",
    "slot.name": "auth_outbox_slot",
    "slot.drop.on.stop": false,
    "plugin.name": "pgoutput",
    "transforms": "outbox",
    "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
    "transforms.outbox.route.topic.replacement": "t.auth.events",
    "transforms.outbox.route.by.field": "event_type",
    "transforms.outbox.table.field.event.id": "event_id",
    "transforms.outbox.table.field.event.key": "aggregate_id",
    "transforms.outbox.table.field.event.timestamp": "created_at",
    "key.converter": "org.apache.kafka.connect.json.JsonConverter",
    "value.converter": "org.apache.kafka.connect.json.JsonConverter",
    "key.converter.schemas.enable": "false",
    "value.converter.schemas.enable": "false",
    "snapshot.mode": "initial"
  }
}
```

#### 2.6 Phụ thuộc giữa các module

```
Module 2 (Database & Entities)
    ├── Outputs: Entities, Repository interfaces, SQL schemas
    ├── Dependency on: Module 1 (constants, config)
    └── Consumed by: Module 3 (domain services), Module 5 (redis cache), Module 7 (outbox)
```

---

### Module 3: Core Domain Services & Token Logic

**Trưởng nhóm:** Backend Team 2
**Ước lượng:** 8–12 ngày
**⚠️ Module quan trọng nhất — Cần review cẩn thận**

#### 3.1 Mục tiêu

Triển khai toàn bộ business logic cốt lõi, đặc biệt là cơ chế **Token Rotation với Grace Period 10s** — phần quan trọng nhất và phức tạp nhất của hệ thống.

#### 3.2 Các file cần tạo / cập nhật

| File | Hành động | Mô tả |
|------|-----------|--------|
| `internal/usecase/authentication/refresh.go` | **SỬA LẠI HOÀN TOÀN** | Grace Period 10s với Redis TTL |
| `internal/usecase/authentication/login.go` | Mở rộng | Audit log write, family_id |
| `internal/usecase/authentication/logout.go` | Mở rộng | Force logout với revoke_before |
| `internal/usecase/grace/grace_service.go` | Tạo mới | Grace period Redis cache operations |
| `internal/usecase/security/token_generator.go` | **SỬA LẠI HOÀN TOÀN** | RS256 JWT signing với RSA keys |
| `internal/usecase/password/change.go` | Mở rộng | revoke_before + audit log |
| `internal/usecase/password/reset.go` | Mở rộng | Outbox event + revoke_before + audit |
| `internal/repository/redis_adapter/grace_cache.go` | Tạo mới | Redis grace period cache |
| `internal/repository/redis_adapter/revoke_before_cache.go` | Tạo mới | Redis revoke_before cache |
| `internal/repository/pg_adapter/session_repository.go` | Mở rộng | FOR UPDATE query, family_id |
| `internal/domain/repository/cache_repository.go` | Mở rộng | Interface mới |

#### 3.3 Chi tiết cơ chế Grace Period 10s (CRITICAL)

Đây là core logic, cần implement chính xác theo thiết kế:

```go
// internal/usecase/authentication/refresh.go

type RefreshUseCase struct {
    SessionRepo    repository.SessionRepository
    TokenRepo      repository.CacheRepository       // Redis
    GraceCache     repository.GraceCacheRepository  // Redis grace cache
    TokenGenerator security.TokenGenerator
    AuditService   *audit.Service
    EventPublisher event.Publisher
    Logger         *zap.Logger
}

type RefreshResult struct {
    AccessToken  string
    RefreshToken string
    ExpiresIn    int64
    TokenId      string // jti
}

// RefreshTokens thực hiện token rotation với Grace Period 10s
// Quy trình:
//  1. Hash incoming refresh token
//  2. SELECT ... FOR UPDATE trên DB để lock row
//  3. Kiểm tra trạng thái token:
//     a. VALID → tạo token mới, revoke cũ, lưu grace cache Redis TTL 10s
//     b. REVOKED + HIT grace cache → trả tokens mới từ cache (lag tolerant)
//     c. REVOKED + MISS grace cache → TOKEN THEFT → revoke all sessions
func (uc *RefreshUseCase) RefreshTokens(ctx context.Context, req *RefreshRequest) (*RefreshResult, error) {
    tokenHash := hash.Token(req.RefreshToken) // SHA-256

    // Bước 1: Query DB với FOR UPDATE để tránh race condition
    session, err := uc.SessionRepo.FindByTokenHashForUpdate(ctx, tokenHash)
    if err != nil {
        if errors.Is(err, repository.ErrSessionNotFound) {
            return nil, serr.ErrInvalidRefreshToken
        }
        return nil, err
    }

    // Bước 2: Kiểm tra user status
    if session.IsRevoked() {
        // Token đã bị revoke → kiểm tra Grace Period cache
        return uc.handleRevokedTokenWithGracePeriod(ctx, session, req.RefreshToken)
    }

    // Bước 3: Token hợp lệ → thực hiện rotation
    return uc.rotateToken(ctx, session, req.RefreshToken)
}

func (uc *RefreshUseCase) rotateToken(ctx context.Context, session *entity.Session, rawToken string) (*RefreshResult, error) {
    // Sinh access token mới
    accessToken, jti, err := uc.TokenGenerator.GenerateAccessToken(session.UserID, session.Roles)
    if err != nil {
        return nil, err
    }

    // Sinh refresh token mới (opaque)
    newRefreshToken := random.Token(32) // 32 bytes, base64url encoded
    newRefreshTokenHash := hash.Token(newRefreshToken)

    // Bước 4: Tạo record mới trong DB (INSERT trước, sau đó UPDATE record cũ)
    newSession := &entity.Session{
        ID:           uuid.New(),
        UserID:       session.UserID,
        TokenHash:    newRefreshTokenHash,
        FamilyID:     session.FamilyID, // Giữ nguyên family
        DeviceID:     session.DeviceID,
        DeviceInfo:   session.DeviceInfo,
        ExpiresAt:    time.Now().Add(domain.RefreshTokenTTL),
        Status:       entity.SessionStatusActive,
        CreatedAt:    time.Now(),
    }

    err = uc.SessionRepo.Create(ctx, newSession)
    if err != nil {
        return nil, err
    }

    // Bước 5: Revoke token cũ trong DB
    oldTokenHash := hash.Token(rawToken)
    graceExpiresAt := time.Now().Add(domain.GracePeriodTTL)
    err = uc.SessionRepo.RevokeWithGracePeriod(ctx, oldTokenHash, newSession.ID, graceExpiresAt)
    if err != nil {
        // Rollback: xóa record mới
        _ = uc.SessionRepo.Delete(ctx, newSession.ID)
        return nil, err
    }

    // Bước 6: Lưu Grace Period cache vào Redis với TTL 10s
    // Key: auth:refresh_grace:{old_rt_hash}
    // Value: JSON {new_access_token, new_refresh_token, jti}
    graceData := &domain.GracePeriodData{
        AccessToken:  accessToken,
        RefreshToken: newRefreshToken,
        JTI:          jti,
        NewSessionID: newSession.ID.String(),
    }
    err = uc.GraceCache.Set(ctx, oldTokenHash, graceData, domain.GracePeriodTTL)
    if err != nil {
        uc.Logger.Warn("Failed to set grace cache, continuing without it",
            zap.Error(err),
            zap.String("session_id", session.ID.String()))
        // Không fail transaction — grace period là optimization, không phải hard requirement
    }

    // Bước 7: Ghi audit log
    _ = uc.AuditService.Log(ctx, &audit.Event{
        ActorID:        session.UserID.String(),
        ActorType:      domain.ActorTypeUser,
        EventType:      domain.EventTypeTokenRefreshed,
        IPAddress:      GetClientIP(ctx),
        DeviceID:       session.DeviceID,
        SecurityLevel:  domain.SecurityLevelInfo,
        LogCategory:    domain.LogCategoryUser,
        Payload: map[string]any{
            "session_id":     session.ID.String(),
            "new_session_id": newSession.ID.String(),
            "family_id":      session.FamilyID.String(),
        },
    })

    return &RefreshResult{
        AccessToken:  accessToken,
        RefreshToken: newRefreshToken,
        ExpiresIn:    int64(domain.AccessTokenTTL.Seconds()),
        TokenId:      jti,
    }, nil
}

func (uc *RefreshUseCase) handleRevokedTokenWithGracePeriod(ctx context.Context, session *entity.Session, rawToken string) (*RefreshResult, error) {
    oldTokenHash := hash.Token(rawToken)

    // Bước 1: Check Grace Period cache
    graceData, err := uc.GraceCache.Get(ctx, oldTokenHash)
    if err == nil && graceData != nil {
        // HIT → Client gửi lại request trong 10s (lag tolerant)
        uc.Logger.Info("Grace period HIT - returning cached tokens",
            zap.String("session_id", session.ID.String()),
            zap.String("user_id", session.UserID.String()))

        return &RefreshResult{
            AccessToken:  graceData.AccessToken,
            RefreshToken: graceData.RefreshToken,
            ExpiresIn:    int64(domain.AccessTokenTTL.Seconds()),
            TokenId:      graceData.JTI,
        }, nil
    }

    // MISS → Hết Grace Period → TOKEN THEFT
    uc.Logger.Warn("TOKEN THEFT DETECTED",
        zap.String("user_id", session.UserID.String()),
        zap.String("session_id", session.ID.String()),
        zap.String("token_hash", oldTokenHash))

    // Bước 2: Revoke TẤT CẢ sessions của user
    err = uc.SessionRepo.RevokeAllByUserID(ctx, session.UserID)
    if err != nil {
        uc.Logger.Error("Failed to revoke all sessions", zap.Error(err))
    }

    // Bước 3: Set revoke_before timestamp (vô hiệu hóa mọi access token cũ ở Gateway)
    err = uc.TokenRepo.SetRevokedBefore(ctx, session.UserID.String(), time.Now())
    if err != nil {
        uc.Logger.Error("Failed to set revoked_before", zap.Error(err))
    }

    // Bước 4: Publish TOKEN_THEFT_DETECTED event
    _ = uc.EventPublisher.Publish(ctx, &event.Event{
        Type:       domain.EventTypeTokenTheftDetected,
        Aggregate:  "User",
        AggregateID: session.UserID,
        PartitionKey: session.UserID,
        Payload: map[string]any{
            "user_id":                  session.UserID.String(),
            "theft_refresh_token_id":   session.ID.String(),
            "action_taken":             "ALL_TOKENS_REVOKED",
        },
    })

    // Bước 5: Ghi audit log CRITICAL
    _ = uc.AuditService.Log(ctx, &audit.Event{
        ActorID:        session.UserID.String(),
        ActorType:      domain.ActorTypeSystem,
        EventType:      domain.EventTypeTokenTheftDetected,
        IPAddress:      GetClientIP(ctx),
        SecurityLevel:  domain.SecurityLevelCritical,
        LogCategory:    domain.LogCategorySystem,
        Payload: map[string]any{
            "theft_session_id": session.ID.String(),
            "action":           "ALL_TOKENS_REVOKED",
        },
    })

    return nil, serr.ErrTokenTheft
}
```

**Redis Grace Cache Implementation:**

```go
// internal/repository/redis_adapter/grace_cache.go

type GraceCacheRepository struct {
    client *redis.Client
    prefix string
    ttl    time.Duration
}

type GracePeriodData struct {
    AccessToken  string `json:"access_token"`
    RefreshToken string `json:"refresh_token"`
    JTI          string `json:"jti"`
    NewSessionID string `json:"new_session_id"`
    CreatedAt    int64  `json:"created_at"`
}

func (r *GraceCacheRepository) key(tokenHash string) string {
    return r.prefix + tokenHash
}

func (r *GraceCacheRepository) Set(ctx context.Context, tokenHash string, data *GracePeriodData) error {
    data.CreatedAt = time.Now().Unix()
    jsonData, err := json.Marshal(data)
    if err != nil {
        return err
    }
    return r.client.Set(ctx, r.key(tokenHash), jsonData, domain.GracePeriodTTL).Err()
}

func (r *GraceCacheRepository) Get(ctx context.Context, tokenHash string) (*GracePeriodData, error) {
    val, err := r.client.Get(ctx, r.key(tokenHash)).Result()
    if err != nil {
        return nil, err // redis.Nil if not found
    }
    var data GracePeriodData
    if err := json.Unmarshal([]byte(val), &data); err != nil {
        return nil, err
    }
    return &data, nil
}

func (r *GraceCacheRepository) Delete(ctx context.Context, tokenHash string) error {
    return r.client.Del(ctx, r.key(tokenHash)).Err()
}
```

#### 3.4 Revoke Before Cache (Force Logout All)

```go
// internal/repository/redis_adapter/revoke_before_cache.go

// SetRevokedBefore ghi timestamp khi user bị force logout
// TTL = Access Token TTL + 1 phút buffer
func (r *RevokeBeforeCacheRepository) SetRevokedBefore(ctx context.Context, userID string, timestamp time.Time) error {
    key := r.prefix + userID
    epoch := timestamp.Unix()
    return r.client.Set(ctx, key, epoch, domain.RevokedBeforeTTL).Err()
}

// GetRevokedBefore lấy timestamp revoke (nếu có)
func (r *RevokeBeforeCacheRepository) GetRevokedBefore(ctx context.Context, userID string) (*time.Time, error) {
    key := r.prefix + userID
    val, err := r.client.Get(ctx, key).Int64()
    if err == redis.Nil {
        return nil, nil // Chưa có revoke
    }
    if err != nil {
        return nil, err
    }
    t := time.Unix(val, 0)
    return &t, nil
}
```

#### 3.5 Token Generator với RS256

```go
// internal/usecase/security/token_generator.go

type TokenGenerator struct {
    jwksService *jwks.Service // Lấy active private key
    issuer      string
    accessTTL   time.Duration
}

type AccessTokenClaims struct {
    jwt.RegisteredClaims
    UserID string   `json:"user_id"`
    Roles  []string `json:"roles"`
    JTI    string   `json:"jti"` // Bắt buộc cho denylist
    Sid    string   `json:"sid"` // Session ID
}

// GenerateAccessToken sinh JWT RS256
func (g *TokenGenerator) GenerateAccessToken(userID uuid.UUID, roles []string) (string, string, error) {
    jti := uuid.New().String()
    now := time.Now()

    // Lấy active private key từ JWKS service
    privateKey, kid, err := g.jwksService.GetActivePrivateKey()
    if err != nil {
        return "", "", fmt.Errorf("failed to get signing key: %w", err)
    }

    claims := AccessTokenClaims{
        RegisteredClaims: jwt.RegisteredClaims{
            Issuer:    g.issuer,
            Subject:   userID.String(),
            IssuedAt:  jwt.NewNumericDate(now),
            ExpiresAt: jwt.NewNumericDate(now.Add(g.accessTTL)),
            ID:        jti,
        },
        UserID: userID.String(),
        Roles:  roles,
        JTI:    jti,
        Sid:    uuid.New().String(), // Session ID
    }

    token := jwt.NewWithClaims(jwt.SigningMethodRS256, claims)
    token.Header["kid"] = kid // Quan trọng: header chứa kid để Gateway biết dùng key nào

    signed, err := token.SignedString(privateKey)
    if err != nil {
        return "", "", err
    }
    return signed, jti, nil
}

// GenerateOpaqueRefreshToken sinh random string an toàn
func (g *TokenGenerator) GenerateOpaqueRefreshToken() string {
    b := make([]byte, 32)
    _, err := rand.Read(b)
    if err != nil {
        panic(err)
    }
    return base64.URLEncoding.EncodeToString(b)
}
```

#### 3.6 Denylist Cache (Access Token Revocation)

```go
// internal/repository/redis_adapter/blacklist_repository.go

// AddToDenylist thêm jti vào danh sách từ chối
// TTL = thời gian sống còn lại của JWT
func (r *BlacklistRepository) AddToDenylist(ctx context.Context, jti string, remainingSeconds int64) error {
    if remainingSeconds <= 0 {
        remainingSeconds = int64(domain.AccessTokenTTL.Seconds())
    }
    key := r.prefix + jti
    return r.client.Set(ctx, key, "1", time.Duration(remainingSeconds)*time.Second).Err()
}

// IsDenied kiểm tra jti có trong denylist không
func (r *BlacklistRepository) IsDenied(ctx context.Context, jti string) (bool, error) {
    key := r.prefix + jti
    exists, err := r.client.Exists(ctx, key).Result()
    return exists > 0, err
}
```

#### 3.7 Force Logout All (revoke_before)

Khi user đổi mật khẩu hoặc admin ban user:

```go
// Gọi trong password/change.go và kafka consumer

func revokeAllSessions(ctx context.Context, userID uuid.UUID) error {
    // 1. Revoke all sessions in DB
    if err := sessionRepo.RevokeAllByUserID(ctx, userID); err != nil {
        return err
    }

    // 2. Set revoked_before timestamp in Redis
    if err := revokeBeforeCache.SetRevokedBefore(ctx, userID.String(), time.Now()); err != nil {
        return err
    }

    // 3. Add all current jti to denylist (nếu có track)
    //    Hoặc để Gateway tự check iat vs revoked_before

    // 4. Audit log
    _ = auditService.Log(ctx, &audit.Event{
        ActorID:       userID.String(),
        ActorType:     domain.ActorTypeUser,
        EventType:     domain.EventTypeUserPasswordChanged,
        SecurityLevel: domain.SecurityLevelWarning,
        LogCategory:   domain.LogCategoryUser,
        Payload: map[string]any{
            "reason": "USER_PASSWORD_CHANGE",
        },
    })

    return nil
}
```

#### 3.8 Phụ thuộc giữa các module

```
Module 3 (Core Domain Services)
    ├── Dependencies: Module 1 (constants, config), Module 2 (entities, repos)
    ├── Consumed by: Module 4 (API layer)
    └── Internal dependencies:
        ├── TokenGenerator (RS256) → Module 6 (JWKS Service)
        ├── GraceCache → Module 5 (Redis)
        ├── AuditService → Module 11 (Testing)
```

---

### Module 4: API Layer – Controllers (HTTP Handlers)

**Trưởng nhóm:** Backend Team 3
**Ước lượng:** 4–6 ngày
**Có thể làm song song với Module 3**

#### 4.1 Mục tiêu

Triển khai các HTTP endpoint, đảm bảo tất cả controller chỉ gọi service interface, không chứa business logic. Bổ sung các endpoint mới: Audit, JWKS.

#### 4.2 Các file cần tạo / cập nhật

| File | Mô tả |
|------|--------|
| `internal/delivery/http/v1/handler/handler_audit.go` | Audit endpoints (NEW) |
| `internal/delivery/http/v1/handler/handler_jwks.go` | JWKS endpoint (NEW) |
| `internal/delivery/http/v1/router/audit_routes.go` | Audit routes (NEW) |
| `internal/delivery/http/v1/router/jwks_routes.go` | JWKS routes (NEW) |
| `internal/delivery/http/v1/middleware/rate_limit.go` | Mở rộng thêm patterns mới |
| `internal/delivery/http/v1/response.go` | Mở rộng thêm response types |
| `internal/usecase/dto/audit_dto.go` | Audit DTOs (NEW) |

#### 4.3 Endpoint specifications

**4.3.1 JWKS Endpoint**

```
GET /.well-known/jwks.json
```

Response (định dạng RFC 7517):

```json
{
  "keys": [
    {
      "kty": "RSA",
      "use": "sig",
      "alg": "RS256",
      "kid": "key-2026-05-01-a1b2c3",
      "n": "...",
      "e": "AQAB"
    },
    {
      "kty": "RSA",
      "use": "sig",
      "alg": "RS256",
      "kid": "key-2026-03-01-d4e5f6",
      "n": "...",
      "e": "AQAB"
    }
  ]
}
```

Handler:

```go
// internal/delivery/http/v1/handler/handler_jwks.go

type JwksHandler struct {
    jwksService *jwks.Service
}

func (h *JwksHandler) GetJwks(c *gin.Context) {
    jwks, err := h.jwksService.GetPublicJwks(c.Request.Context())
    if err != nil {
        c.JSON(http.StatusInternalServerError, response.Error("JWKS_UNAVAILABLE", "Failed to retrieve JWKS"))
        return
    }
    c.JSON(http.StatusOK, jwks)
}
```

**4.3.2 Audit Endpoints**

```
GET  /api/v1/auth/audit/my-login-history
GET  /api/v1/auth/audit/admin/history-logs     (requires ADMIN role)
```

```go
// GET /api/v1/auth/audit/my-login-history
// Header: X-User-Id (từ Gateway sau khi verify JWT)
// Returns: List login history với pagination

type AuditHandler struct {
    auditService *audit.Service
}

func (h *AuditHandler) GetMyLoginHistory(c *gin.Context) {
    userID := c.GetHeader("X-User-Id")
    if userID == "" {
        c.JSON(http.StatusUnauthorized, response.Unauthorized())
        return
    }

    // Parse pagination
    limit, offset := parsePagination(c)

    events, total, err := h.auditService.GetLoginHistory(c.Request.Context(), userID, limit, offset)
    if err != nil {
        c.JSON(http.StatusInternalServerError, response.Error(...))
        return
    }

    c.JSON(http.StatusOK, response.Paginated(events, total, limit, offset))
}

// GET /api/v1/auth/audit/admin/history-logs
// Header: X-User-Id, X-User-Roles
// Requires: ADMIN role
// Query params: from_date, to_date, event_type, user_id, security_level, page, limit

func (h *AuditHandler) GetAdminHistoryLogs(c *gin.Context) {
    // Role check
    roles := parseRoles(c.GetHeader("X-User-Roles"))
    if !slices.Contains(roles, "ADMIN") {
        c.JSON(http.StatusForbidden, response.Forbidden())
        return
    }

    // Parse filter params
    filter := &audit.AdminFilter{
        FromDate:      parseTime(c.Query("from_date")),
        ToDate:        parseTime(c.Query("to_date")),
        EventType:     c.Query("event_type"),
        ActorID:       c.Query("user_id"),
        SecurityLevel: c.Query("security_level"),
        Limit:         parseIntDefault(c.Query("limit"), 50),
        Offset:        parseIntDefault(c.Query("page"), 0) * parseIntDefault(c.Query("limit"), 50),
    }

    events, total, err := h.auditService.GetAdminLogs(c.Request.Context(), filter)
    if err != nil {
        c.JSON(http.StatusInternalServerError, response.Error(...))
        return
    }

    c.JSON(http.StatusOK, response.Paginated(events, total, filter.Limit, filter.Offset))
}
```

**4.3.3 Rate Limiting Middleware mở rộng**

Bổ sung thêm các rate limit pattern cho `/forgot-password`, `/resend-otp`:

```go
// internal/delivery/http/v1/middleware/rate_limit.go

func RateLimitForLogin() gin.HandlerFunc {
    return newRateLimiter(domain.RedisKeyPrefixRateLimitLogin, 10, 15*time.Minute)
}

func RateLimitForRegister() gin.HandlerFunc {
    return newRateLimiter(domain.RedisKeyPrefixRateLimitReg, 5, 1*time.Hour)
}

func RateLimitForForgotPassword() gin.HandlerFunc {
    return newRateLimiter(domain.RedisKeyPrefixRateLimitForgot, 3, 1*time.Hour)
}

func RateLimitForResendOTP() gin.HandlerFunc {
    // Key là email chứ không phải IP
    return func(c *gin.Context) {
        email := c.Query("email") // hoặc từ body
        key := domain.RedisKeyPrefixRateLimitResend + email
        // ... limit logic
    }
}
```

#### 4.4 Route Registration

```go
// internal/delivery/http/v1/router/router.go (cần tạo hoặc mở rộng)

func SetupRouter(/* dependencies */) *gin.Engine {
    r := gin.New()
    
    // Public routes
    public := r.Group("/")
    {
        public.GET("/.well-known/jwks.json", jwksHandler.GetJwks)
        // Auth routes...
    }
    
    // Authenticated routes (JWT required)
    auth := r.Group("/api/v1/auth")
    auth.Use(middleware.Auth())
    {
        audit := auth.Group("/audit")
        {
            audit.GET("/my-login-history", auditHandler.GetMyLoginHistory)
            
            // Admin only
            admin := audit.Group("")
            admin.Use(middleware.RequireRole("ADMIN"))
            {
                admin.GET("/admin/history-logs", auditHandler.GetAdminHistoryLogs)
            }
        }
    }
    
    return r
}
```

#### 4.5 Phụ thuộc giữa các module

```
Module 4 (API Layer)
    ├── Dependencies: Module 3 (services)
    ├── Consumed by: Module 4 (self), Module 11 (testing)
    └── Testable independently với mock services
```

---

### Module 5: Redis Caching & Rate Limiting

**Trưởng nhóm:** Backend Team 1
**Ước lượng:** 3–5 ngày
**Có thể làm song song với Module 3**

#### 5.1 Mục tiêu

Triển khai toàn bộ các Redis key patterns theo thiết kế caching schema. Đảm bảo mỗi operation có unit test riêng.

#### 5.2 Các file cần tạo / cập nhật

| File | Mô tả |
|------|--------|
| `internal/repository/redis_adapter/grace_cache.go` | `auth:refresh_grace:{hash}` — TTL 10s |
| `internal/repository/redis_adapter/revoke_before_cache.go` | `auth:revoked_before:{user_id}` — TTL 16m |
| `internal/repository/redis_adapter/blacklist_repository.go` | Mở rộng: denylist với TTL động |
| `internal/repository/redis_adapter/ratelimit_repository.go` | Mở rộng: thêm patterns |
| `internal/repository/redis_adapter/otp_repository.go` | Giữ nguyên (đã có) |
| `internal/repository/redis_adapter/reset_pw_cache.go` | `auth:reset_pw:{token_hash}` |
| `internal/domain/repository/cache_repository.go` | Mở rộng interface |

#### 5.3 Chi tiết từng Redis Pattern

**5.3.1 AuthTokenDenylist (`auth:denylist:{jti}`)**

```go
// Lưu jti đã revoke với TTL = thời gian sống còn lại
func (r *BlacklistRepository) DenyToken(ctx context.Context, jti string, remainingSeconds int64) error {
    key := domain.RedisKeyPrefixDenylist + jti
    ttl := time.Duration(remainingSeconds) * time.Second
    if ttl <= 0 {
        ttl = domain.AccessTokenTTL
    }
    return r.client.Set(ctx, key, "1", ttl).Err()
}

func (r *BlacklistRepository) IsTokenDenied(ctx context.Context, jti string) (bool, error) {
    key := domain.RedisKeyPrefixDenylist + jti
    n, err := r.client.Exists(ctx, key).Result()
    return n > 0, err
}
```

**5.3.2 UserRevocationTimestamp (`auth:revoked_before:{user_id}`)**

```go
func (r *RevokeBeforeCache) SetRevokedBefore(ctx context.Context, userID string, timestamp time.Time) error {
    key := domain.RedisKeyPrefixRevokedBefore + userID
    return r.client.Set(ctx, key, timestamp.Unix(), domain.RevokedBeforeTTL).Err()
}

func (r *RevokeBeforeCache) GetRevokedBefore(ctx context.Context, userID string) (*time.Time, error) {
    key := domain.RedisKeyPrefixRevokedBefore + userID
    val, err := r.client.Get(ctx, key).Int64()
    if errors.Is(err, redis.Nil) {
        return nil, nil
    }
    if err != nil {
        return nil, err
    }
    t := time.Unix(val, 0)
    return &t, nil
}
```

**5.3.3 GracePeriodCache (`auth:refresh_grace:{old_rt_hash}`)**

```go
type GraceCacheRepo struct {
    client *redis.Client
}

type GraceCacheEntry struct {
    AccessToken   string `json:"access_token"`
    RefreshToken  string `json:"refresh_token"`
    JTI           string `json:"jti"`
    NewSessionID  string `json:"new_session_id"`
    IssuedAt      int64  `json:"issued_at"` // Unix timestamp
}

func (r *GraceCacheRepo) Set(ctx context.Context, tokenHash string, entry *GraceCacheEntry) error {
    key := domain.RedisKeyPrefixRefreshGrace + tokenHash
    data, _ := json.Marshal(entry)
    return r.client.Set(ctx, key, data, domain.GracePeriodTTL).Err()
}

func (r *GraceCacheRepo) Get(ctx context.Context, tokenHash string) (*GraceCacheEntry, error) {
    key := domain.RedisKeyPrefixRefreshGrace + tokenHash
    data, err := r.client.Get(ctx, key).Bytes()
    if errors.Is(err, redis.Nil) {
        return nil, nil // MISS
    }
    if err != nil {
        return nil, err
    }
    var entry GraceCacheEntry
    json.Unmarshal(data, &entry)
    return &entry, nil
}

func (r *GraceCacheRepo) Delete(ctx context.Context, tokenHash string) error {
    key := domain.RedisKeyPrefixRefreshGrace + tokenHash
    return r.client.Del(ctx, key).Err()
}
```

**5.3.4 Rate Limiting Patterns**

```go
// Generic rate limiter dùng sliding window
type RateLimitResult struct {
    Allowed   bool
    Remaining int
    RetryIn   time.Duration
}

func (r *RateLimitRepo) Check(ctx context.Context, key string, limit int, window time.Duration) (*RateLimitResult, error) {
    now := time.Now().Unix()
    windowStart := now - int64(window.Seconds())

    pipe := r.client.Pipeline()

    // Xóa entries cũ
    pipe.ZRemRangeByScore(ctx, key, "0", strconv.FormatInt(windowStart, 10))

    // Đếm current
    countCmd := pipe.ZCard(ctx, key)

    _, err := pipe.Exec(ctx)
    if err != nil && err != redis.Nil {
        return nil, err
    }

    count := countCmd.Val()

    if count >= int64(limit) {
        // Tính thời gian chờ
        oldestCmd, _ := r.client.ZRangeWithScores(ctx, key, 0, 0).Result()
        if len(oldestCmd) > 0 {
            retryIn := time.Duration(window-(now-int64(oldestCmd[0].Score)))*time.Second + time.Second
            return &RateLimitResult{Allowed: false, Remaining: 0, RetryIn: retryIn}, nil
        }
        return &RateLimitResult{Allowed: false, Remaining: 0, RetryIn: window}, nil
    }

    // Thêm request
    r.client.ZAdd(ctx, key, redis.Z{Score: float64(now), Member: uuid.New().String()})
    r.client.Expire(ctx, key, window)

    return &RateLimitResult{Allowed: true, Remaining: limit - int(count) - 1, RetryIn: 0}, nil
}
```

#### 5.4 Phụ thuộc giữa các module

```
Module 5 (Redis Caching)
    ├── Dependencies: Module 1 (constants)
    ├── Consumed by: Module 3 (Core Domain Services)
    └── Có thể test độc lập với redis-testcontainers
```

---

### Module 6: JWKS Management & Key Rotation

**Trưởng nhóm:** Backend Team 2
**Ước lượng:** 5–7 ngày
**Có thể làm song song với Module 3**

#### 6.1 Mục tiêu

Triển khai hoàn chỉnh JWKS system: sinh RSA key pairs, lưu trữ (mã hóa) trong DB, expose endpoint, cache Caffeine, và background key rotation worker.

#### 6.2 Các file cần tạo

| File | Mô tả |
|------|--------|
| `internal/usecase/jwks/service.go` | Quản lý keystore, sinh key, get JWKS |
| `internal/usecase/jwks/cache.go` | Caffeine L1 in-memory cache |
| `internal/usecase/jwks/rotator.go` | Key rotation logic |
| `internal/repository/pg_adapter/jwt_key_repository.go` | DB operations cho jwt_keys |
| `internal/infrastructure/redis_pubsub/publisher.go` | Redis Pub/Sub publisher |
| `internal/worker/key_rotation.go` | Background worker (cron) |
| `scripts/init_jwt_keys.sql` | Script tạo cặp key RSA đầu tiên |
| `deployments/debezium/connector.json` | Đã có trong Module 2 |

#### 6.3 Chi tiết implementation

**6.3.1 JWKS Service (Core)**

```go
// internal/usecase/jwks/service.go

type JwksService struct {
    keyRepo     repository.JwtKeyRepository
    cache       *JwksCache
    pubsub      *redispubsub.Publisher
    keyGen      *KeyGenerator
    logger      *zap.Logger
}

type JWK struct {
    Kty string `json:"kty"` // "RSA"
    Use string `json:"use"` // "sig"
    Alg string `json:"alg"` // "RS256"
    Kid string `json:"kid"`
    N   string `json:"n"`   // Modulus (base64url)
    E   string `json:"e"`  // Exponent (base64url)
}

type JWKS struct {
    Keys []JWK `json:"keys"`
}

// GetActivePrivateKey trả về private key để ký JWT
func (s *JwksService) GetActivePrivateKey() (*rsa.PrivateKey, string, error) {
    key, err := s.keyRepo.GetActive(context.Background())
    if err != nil {
        return nil, "", err
    }

    privateKeyPEM, err := crypto.DecryptPrivateKey(key.PrivateKeyEncrypted)
    if err != nil {
        return nil, "", fmt.Errorf("failed to decrypt private key: %w", err)
    }

    parsedKey, err := jwt.ParseRSAPrivateKeyFromPEM([]byte(privateKeyPEM))
    if err != nil {
        return nil, "", fmt.Errorf("failed to parse private key: %w", err)
    }

    return parsedKey, key.Kid, nil
}

// GetPublicJwks trả về JWKS cho endpoint public
func (s *JwksService) GetPublicJwks(ctx context.Context) (*JWKS, error) {
    // Bước 1: Check L1 cache (Caffeine)
    cached, err := s.cache.Get()
    if err == nil && cached != nil {
        return cached, nil
    }

    // Bước 2: Query DB — lấy ACTIVE và PHASE_OUT keys
    keys, err := s.keyRepo.GetActiveAndPhaseOut(ctx)
    if err != nil {
        return nil, err
    }

    // Bước 3: Build JWKS response
    jwks := &JWKS{Keys: make([]JWK, 0, len(keys))}
    for _, key := range keys {
        pubKey, err := jwt.ParseRSAPublicKeyFromPEM([]byte(key.PublicKey))
        if err != nil {
            s.logger.Warn("Failed to parse public key", zap.String("kid", key.Kid))
            continue
        }

        jwk := JWK{
            Kty: "RSA",
            Use: "sig",
            Alg: "RS256",
            Kid: key.Kid,
            N:   base64.URLEncoding.EncodeToString(pubKey.N.Bytes()),
            E:   base64.URLEncoding.EncodeToString(big.NewInt(int64(pubKey.E)).Bytes()),
        }
        jwks.Keys = append(jwks.Keys, jwk)
    }

    // Bước 4: Update cache
    s.cache.Set(jwks)

    return jwks, nil
}

// GenerateAndRotateKey tạo cặp key mới, chuyển key cũ sang PHASE_OUT
func (s *JwksService) GenerateAndRotateKey(ctx context.Context) error {
    // Bước 1: Sinh cặp RSA key mới
    kid := fmt.Sprintf("key-%s", time.Now().Format("2006-01-02"))
    privateKey, publicKeyPEM, err := s.keyGen.GenerateRSAKeyPair(kid)
    if err != nil {
        return fmt.Errorf("failed to generate RSA key: %w", err)
    }

    // Bước 2: Mã hóa private key
    encryptedPrivateKey, err := crypto.EncryptPrivateKey(privateKey)
    if err != nil {
        return fmt.Errorf("failed to encrypt private key: %w", err)
    }

    // Bước 3: Chuyển key hiện tại sang PHASE_OUT
    if err := s.keyRepo.SetCurrentToPhaseOut(ctx); err != nil {
        return fmt.Errorf("failed to set current key to phase_out: %w", err)
    }

    // Bước 4: Insert key mới với status ACTIVE
    newKey := &entity.JwtKey{
        Kid:                  kid,
        PublicKey:            publicKeyPEM,
        PrivateKeyEncrypted:  encryptedPrivateKey,
        Status:               entity.KeyStatusActive,
        Algorithm:            "RS256",
        CreatedAt:            time.Now(),
    }
    if err := s.keyRepo.Create(ctx, newKey); err != nil {
        return fmt.Errorf("failed to insert new key: %w", err)
    }

    // Bước 5: Invalidate local cache
    s.cache.Invalidate()

    // Bước 6: Publish Redis Pub/Sub để Gateway invalidate cache
    if err := s.pubsub.PublishJwksUpdate(ctx); err != nil {
        s.logger.Error("Failed to publish JWKS update via Redis Pub/Sub",
            zap.Error(err),
            zap.String("kid", kid))
        // Không fail — Gateway sẽ refresh sau TTL 10 phút
    }

    s.logger.Info("JWKS key rotated",
        zap.String("new_kid", kid),
        zap.Time("rotated_at", time.Now()))

    return nil
}
```

**6.3.2 Caffeine L1 Cache (In-Memory)**

```go
// internal/usecase/jwks/cache.go

type JwksCache struct {
    cache  *ristretto.Cache[string, *JWKS] // Ristretto — high-performance Go cache
    ttl    time.Duration
    mu     sync.RWMutex
    loaded time.Time
}

func NewJwksCache(ttl time.Duration) *JwksCache {
    cache, _ := ristretto.NewCache[string, *JWKS](&ristretto.Config{
        MaxCost:     1 << 20, // 1 MB
        BufferItems: 64,
        TTL:         ttl,
    })

    return &JwksCache{
        cache: cache,
        ttl:   ttl,
    }
}

func (c *JwksCache) Get() (*JWKS, error) {
    c.mu.RLock()
    defer c.mu.RUnlock()

    val, found := c.cache.Get("jwks")
    if !found {
        return nil, nil // MISS
    }
    return val, nil
}

func (c *JwksCache) Set(jwks *JWKS) {
    c.mu.Lock()
    defer c.mu.Unlock()
    c.cache.Set("jwks", jwks, 1)
    c.loaded = time.Now()
}

func (c *JwksCache) Invalidate() {
    c.mu.Lock()
    defer c.mu.Unlock()
    c.cache.Del("jwks")
    c.loaded = time.Time{}
}
```

**6.3.3 Key Generator**

```go
// internal/usecase/jwks/generator.go

type KeyGenerator struct{}

func (g *KeyGenerator) GenerateRSAKeyPair(kid string) (privateKeyPEM, publicKeyPEM string, err error) {
    // Sinh RSA 2048-bit key (hoặc 4096-bit cho production)
    privateKey, err := rsa.GenerateKey(rand.Reader, 4096)
    if err != nil {
        return "", "", err
    }

    // Private key PEM
    privateKeyPEM = string(pem.EncodeToMemory(&pem.Block{
        Type:  "RSA PRIVATE KEY",
        Bytes: x509.MarshalPKCS1PrivateKey(privateKey),
    }))

    // Public key PEM
    publicKeyBytes, err := x509.MarshalPKIXPublicKey(&privateKey.PublicKey)
    if err != nil {
        return "", "", err
    }
    publicKeyPEM = string(pem.EncodeToMemory(&pem.Block{
        Type:  "PUBLIC KEY",
        Bytes: publicKeyBytes,
    }))

    return privateKeyPEM, publicKeyPEM, nil
}
```

#### 6.4 Redis Pub/Sub cho JWKS Update

```go
// internal/infrastructure/redis_pubsub/publisher.go

type Publisher struct {
    client  *redis.Client
    channel string
}

type JwksUpdateMessage struct {
    Event     string `json:"event"`     // "JWKS_UPDATED"
    Timestamp int64  `json:"timestamp"` // Unix timestamp
    Kid       string `json:"kid"`       // New key ID (optional)
}

func (p *Publisher) PublishJwksUpdate(ctx context.Context) error {
    msg := JwksUpdateMessage{
        Event:     "JWKS_UPDATED",
        Timestamp: time.Now().Unix(),
    }
    data, _ := json.Marshal(msg)
    return p.client.Publish(ctx, domain.RedisChannelJwksUpdate, data).Err()
}
```

#### 6.5 Phụ thuộc giữa các module

```
Module 6 (JWKS)
    ├── Dependencies: Module 1 (constants), Module 2 (jwt_keys table)
    ├── Consumed by: Module 3 (TokenGenerator dùng để sign JWT)
    └── Module 9 (Background Worker gọi RotateKey)
```

---

### Module 7: Outbox & Event Publishing

**Trưởng nhóm:** Backend Team 3
**Ước lượng:** 3–5 ngày

#### 7.1 Mục tiêu

Chuyển đổi từ direct Kafka publish sang **Transactional Outbox Pattern** sử dụng Debezium CDC. Đảm bảo event được publish cùng transaction với business data.

#### 7.2 Các file cần tạo

| File | Mô tả |
|------|--------|
| `internal/infrastructure/event/outbox_publisher.go` | Insert event vào outbox table |
| `internal/infrastructure/event/event_registry.go` | Event type → payload builder |
| `internal/repository/pg_adapter/outbox_repository.go` | Outbox CRUD |
| `internal/domain/entity/outbox_event.go` | Entity |
| `sql/queries/outbox_events.sql` | SQL queries |
| `deployments/debezium/connector.json` | Đã có Module 2 |

#### 7.3 Chi tiết implementation

**7.3.1 Outbox Publisher (Transaction-aware)**

```go
// internal/infrastructure/event/outbox_publisher.go

// OutboxPublisher insert event vào outbox table cùng transaction với business logic
// Điều này đảm bảo: nếu business transaction fail → event không được insert
type OutboxPublisher struct {
    db *pgxpool.Pool
}

// PublishEvent insert event vào outbox (trong cùng transaction)
func (p *OutboxPublisher) PublishEvent(ctx context.Context, tx pgx.Tx, event *OutboxEvent) error {
    if tx == nil {
        return errors.New("outbox publisher requires an active transaction")
    }

    payloadJSON, err := json.Marshal(event.Payload)
    if err != nil {
        return fmt.Errorf("failed to marshal event payload: %w", err)
    }

    query := `
        INSERT INTO outbox_events (event_id, event_type, aggregate_type, aggregate_id, partition_key, payload, created_at)
        VALUES ($1, $2, $3, $4, $5, $6, NOW())
    `
    _, err = tx.Exec(ctx, query,
        event.EventID,
        event.EventType,
        event.AggregateType,
        event.AggregateID,
        event.PartitionKey,
        payloadJSON,
    )
    return err
}

// OutboxEvent struct
type OutboxEvent struct {
    EventID       uuid.UUID
    EventType     string
    AggregateType string
    AggregateID   uuid.UUID
    PartitionKey  uuid.UUID
    Payload       map[string]any
}
```

**7.3.2 Event Registry (Type → Payload Builder)**

```go
// internal/infrastructure/event/event_registry.go

type EventRegistry struct{}

func (er *EventRegistry) BuildOtpRequestedEvent(userID uuid.UUID, email string, otpType string) *OutboxEvent {
    return &OutboxEvent{
        EventID:       uuid.New(),
        EventType:     domain.EventTypeUserOtpRequested,
        AggregateType: "User",
        AggregateID:   userID,
        PartitionKey:  userID,
        Payload: map[string]any{
            "user_id":   userID.String(),
            "email":     email,
            "otp_type":  otpType,
        },
    }
}

func (er *EventRegistry) BuildUserVerifiedEvent(userID uuid.UUID, email string, roles []string) *OutboxEvent {
    return &OutboxEvent{
        EventID:       uuid.New(),
        EventType:     domain.EventTypeUserVerifiedSuccess,
        AggregateType: "User",
        AggregateID:   userID,
        PartitionKey:  userID,
        Payload: map[string]any{
            "user_id": userID.String(),
            "email":   email,
            "roles":   roles,
        },
    }
}

func (er *EventRegistry) BuildUserDeletedEvent(userID uuid.UUID, reason string) *OutboxEvent {
    return &OutboxEvent{
        EventID:       uuid.New(),
        EventType:     domain.EventTypeUserDeleted,
        AggregateType: "User",
        AggregateID:   userID,
        PartitionKey:  userID,
        Payload: map[string]any{
            "user_id": userID.String(),
            "reason":  reason,
        },
    }
}

func (er *EventRegistry) BuildPasswordResetRequestedEvent(userID uuid.UUID, email string, resetURL string) *OutboxEvent {
    return &OutboxEvent{
        EventID:       uuid.New(),
        EventType:     domain.EventTypePasswordResetRequested,
        AggregateType: "User",
        AggregateID:   userID,
        PartitionKey:  userID,
        Payload: map[string]any{
            "user_id":   userID.String(),
            "email":     email,
            "reset_url": resetURL,
        },
    }
}

func (er *EventRegistry) BuildTokenTheftDetectedEvent(userID uuid.UUID, theftSessionID string) *OutboxEvent {
    return &OutboxEvent{
        EventID:       uuid.New(),
        EventType:     domain.EventTypeTokenTheftDetected,
        AggregateType: "User",
        AggregateID:   userID,
        PartitionKey:  userID,
        Payload: map[string]any{
            "user_id":                  userID.String(),
            "theft_refresh_token_id":   theftSessionID,
            "action_taken":             "ALL_TOKENS_REVOKED",
        },
    }
}
```

**7.3.3 Cập nhật Business Logic để dùng Outbox**

Trong `registration/managed.go`, thay vì gọi `kafkaPublisher.Publish()` trực tiếp:

```go
// TRƯỚC (direct Kafka publish — không đảm bảo consistency)
func (uc *ManagedRegistrationUseCase) VerifyEmail(ctx context.Context, email, otp string) error {
    // ... verify logic ...
    tx, _ := uc.db.Begin(ctx)
    
    // UPDATE user status
    _, _ = uc.userRepo.UpdateStatus(ctx, tx, userID, "ACTIVE")
    
    // Direct Kafka publish — NẾU PUBLISH FAIL thì transaction đã commit rồi!
    _ = uc.kafkaPublisher.Publish(ctx, userVerifiedEvent) // ❌ Không an toàn
    
    _ = tx.Commit(ctx)
    return nil
}

// SAU (Outbox Pattern — đảm bảo ALL-OR-NOTHING)
func (uc *ManagedRegistrationUseCase) VerifyEmail(ctx context.Context, email, otp string) error {
    tx, _ := uc.db.Begin(ctx)
    
    // UPDATE user status
    _, _ = uc.userRepo.UpdateStatus(ctx, tx, userID, "ACTIVE")
    
    // Insert vào outbox — cùng transaction với UPDATE
    event := uc.eventRegistry.BuildUserVerifiedEvent(userID, email, []string{"USER"})
    _ = uc.outboxPublisher.PublishEvent(ctx, tx, event) // ✅ An toàn
    
    _ = tx.Commit(ctx)
    return nil
    // Debezium CDC sẽ tự động đọc outbox_events và publish lên Kafka
}
```

#### 7.4 Phụ thuộc giữa các module

```
Module 7 (Outbox Pattern)
    ├── Dependencies: Module 2 (outbox_events table)
    ├── Consumed by: Module 3 (business logic dùng outboxPublisher)
    └── Cần phối hợp với DevOps để setup Debezium connector
```

---

### Module 8: Kafka Event Consumers

**Trưởng nhóm:** Backend Team 1
**Ước lượng:** 3–5 ngày

#### 8.1 Mục tiêu

Triển khai Kafka consumer để xử lý các event từ `t.admin.events`: `ADMIN_FORCE_LOGOUT` và `USER_BANNED`. Khi nhận event, thực hiện revoke toàn bộ session, set `revoked_before` timestamp.

#### 8.2 Các file cần tạo

| File | Mô tả |
|------|--------|
| `internal/infrastructure/event/kafka_consumer.go` | Kafka consumer chính |
| `internal/infrastructure/event/consumer_handlers.go` | Handler cho từng event type |
| `cmd/server/main.go` | Khởi tạo consumer khi start |

#### 8.3 Chi tiết implementation

**8.3.1 Kafka Consumer**

```go
// internal/infrastructure/event/kafka_consumer.go

type KafkaConsumer struct {
    client       *kafka.Client
    groupID      string
    topic        string
    handlers     map[string]EventHandler
    sessionRepo  repository.SessionRepository
    tokenCache   repository.CacheRepository
    auditService *audit.Service
    logger       *zap.Logger
    ready        chan struct{}
}

type EventHandler func(ctx context.Context, event *KafkaEvent) error

type KafkaEvent struct {
    EventID       string          `json:"event_id"`
    AggregateType string          `json:"aggregate_type"`
    AggregateID   string          `json:"aggregate_id"`
    EventType     string          `json:"event_type"`
    OccurredAt    time.Time       `json:"occurred_at"`
    Payload       json.RawMessage `json:"payload"`
}

func (c *KafkaConsumer) Start(ctx context.Context) error {
    c.ready = make(chan struct{})

    go func() {
        session := c.client.Config()
        session.Set("group.id", c.groupID)
        session.Set("auto.offset.reset", "earliest")
        session.Set("enable.auto.commit", "false")

        partitionCount, err := c.client.GetKernelPollLen(c.topic, 10*time.Second)
        if err != nil {
            c.logger.Error("Failed to get partition count", zap.Error(err))
            return
        }

        for p := 0; p < partitionCount; p++ {
            go c.consumePartition(ctx, p)
        }

        close(c.ready)
        <-ctx.Done()
    }()

    <-c.ready
    return nil
}

func (c *KafkaConsumer) consumePartition(ctx context.Context, partition int) {
    ch := c.client.Channels()

    for {
        select {
        case msg, ok := <-ch:
            if !ok {
                return
            }

            var event KafkaEvent
            if err := json.Unmarshal(msg.Value, &event); err != nil {
                c.logger.Error("Failed to unmarshal Kafka event",
                    zap.Error(err),
                    zap.ByteString("raw", msg.Value))
                continue
            }

            handler, ok := c.handlers[event.EventType]
            if !ok {
                c.logger.Debug("No handler for event type", zap.String("type", event.EventType))
                continue
            }

            if err := handler(ctx, &event); err != nil {
                c.logger.Error("Handler failed for event",
                    zap.Error(err),
                    zap.String("event_type", event.EventType),
                    zap.String("aggregate_id", event.AggregateID))
                // Implement retry với exponential backoff
                c.retryWithBackoff(ctx, &event, handler)
            }

            // Commit offset sau khi xử lý thành công
            _, _ = c.client.CommitMessages([]kafka.Message{msg})
        case <-ctx.Done():
            return
        }
    }
}
```

**8.3.2 Consumer Handlers**

```go
// internal/infrastructure/event/consumer_handlers.go

func (c *KafkaConsumer) RegisterHandlers() {
    c.handlers = map[string]EventHandler{
        "ADMIN_FORCE_LOGOUT": c.handleAdminForceLogout,
        "USER_BANNED":        c.handleUserBanned,
    }
}

type AdminForceLogoutPayload struct {
    UserID uuid.UUID `json:"user_id"`
    Reason string    `json:"reason"`
    ActorID string   `json:"actor_id"` // Admin user ID
}

func (c *KafkaConsumer) handleAdminForceLogout(ctx context.Context, event *KafkaEvent) error {
    var payload AdminForceLogoutPayload
    if err := json.Unmarshal(event.Payload, &payload); err != nil {
        return fmt.Errorf("failed to unmarshal payload: %w", err)
    }

    c.logger.Info("Processing ADMIN_FORCE_LOGOUT",
        zap.String("user_id", payload.UserID.String()),
        zap.String("actor_id", payload.ActorID))

    // Bước 1: Revoke all sessions in DB
    if err := c.sessionRepo.RevokeAllByUserID(ctx, payload.UserID); err != nil {
        return fmt.Errorf("failed to revoke sessions: %w", err)
    }

    // Bước 2: Set revoked_before timestamp
    if err := c.tokenCache.SetRevokedBefore(ctx, payload.UserID.String(), time.Now()); err != nil {
        c.logger.Error("Failed to set revoked_before", zap.Error(err))
    }

    // Bước 3: Audit log
    _ = c.auditService.Log(ctx, &audit.Event{
        ActorID:       payload.ActorID,
        ActorType:     domain.ActorTypeAdmin,
        EventType:     domain.EventTypeUserForceLoggedOut,
        SecurityLevel: domain.SecurityLevelCritical,
        LogCategory:   domain.LogCategorySystem,
        Payload: map[string]any{
            "target_user_id": payload.UserID.String(),
            "reason":         payload.Reason,
        },
    })

    c.logger.Info("Admin force logout completed",
        zap.String("user_id", payload.UserID.String()))

    return nil
}

func (c *KafkaConsumer) handleUserBanned(ctx context.Context, event *KafkaEvent) error {
    var payload AdminForceLogoutPayload // Reuse struct
    if err := json.Unmarshal(event.Payload, &payload); err != nil {
        return err
    }

    // USER_BANNED = ADMIN_FORCE_LOGOUT + additional status update
    if err := c.handleAdminForceLogout(ctx, event); err != nil {
        return err
    }

    // Cập nhật user status thành BANNED
    if err := c.userRepo.UpdateStatus(ctx, nil, payload.UserID, "BANNED"); err != nil {
        c.logger.Error("Failed to update user status to BANNED", zap.Error(err))
    }

    return nil
}
```

#### 8.4 Phụ thuộc giữa các module

```
Module 8 (Kafka Consumer)
    ├── Dependencies: Module 3 (session repo, token cache), Module 11 (audit)
    ├── Consumed by: cmd/server/main.go (khởi tạo khi start)
    └── Không xung đột với các module khác
```

---

### Module 9: Background Jobs (Cron Workers)

**Trưởng nhóm:** Backend Team 2
**Ước lượng:** 4–6 ngày
**Có thể làm song song với Module 6**

#### 9.1 Mục tiêu

Triển khai 3 background workers chạy theo cron schedule với distributed lock để đảm bảo chỉ 1 instance thực thi.

#### 9.2 Các file cần tạo

| File | Mô tả |
|------|--------|
| `internal/worker/scheduler.go` | Cron scheduler với ShedLock |
| `internal/worker/token_cleanup.go` | Cleanup expired/revoked tokens |
| `internal/worker/audit_cleanup.go` | Cleanup old audit logs |
| `internal/worker/key_rotation.go` | JWKS key rotation (gọi JWKS service) |
| `internal/worker/outbox_cleanup.go` | Cleanup old outbox events |

#### 9.3 Chi tiết implementation

**9.3.1 Distributed Lock (ShedLock-style)**

```go
// internal/worker/distributed_lock.go

type DistributedLock struct {
    redis *redis.Client
    ttl   time.Duration
}

func NewDistributedLock(redisClient *redis.Client) *DistributedLock {
    return &DistributedLock{
        redis: redisClient,
        ttl:   10 * time.Minute, // Lock expires after 10 minutes (failsafe)
    }
}

// Acquire tries to acquire the lock. Returns true if successful.
func (l *DistributedLock) Acquire(ctx context.Context, lockName string, ownerID string) (bool, error) {
    key := domain.RedisKeyPrefixLock + lockName
    // SET NX EX — atomic set-if-not-exists with expiry
    ok, err := l.redis.SetNX(ctx, key, ownerID, l.ttl).Result()
    return ok, err
}

// Release releases the lock (only if we own it)
func (l *DistributedLock) Release(ctx context.Context, lockName string, ownerID string) error {
    key := domain.RedisKeyPrefixLock + lockName

    // Lua script: chỉ xóa nếu value = ownerID (tránh xóa lock của instance khác)
    script := redis.NewScript(`
        if redis.call("get", KEYS[1]) == ARGV[1] then
            return redis.call("del", KEYS[1])
        else
            return 0
        end
    `)
    _, err := script.Run(ctx, l.redis, []string{key}, ownerID).Result()
    return err
}

// Extend extends the lock TTL (heartbeat)
func (l *DistributedLock) Extend(ctx context.Context, lockName string, ownerID string, ttl time.Duration) error {
    key := domain.RedisKeyPrefixLock + lockName

    script := redis.NewScript(`
        if redis.call("get", KEYS[1]) == ARGV[1] then
            return redis.call("pexpire", KEYS[1], ARGV[2])
        else
            return 0
        end
    `)
    _, err := script.Run(ctx, l.redis, []string{key}, ownerID, ttl.Milliseconds()).Result()
    return err
}
```

**9.3.2 Scheduler với Cron**

```go
// internal/worker/scheduler.go

type Scheduler struct {
    cron      *cron.Cron
    lock      *DistributedLock
    instanceID string // UUID của instance hiện tại
    logger    *zap.Logger
}

func NewScheduler(redisClient *redis.Client, logger *zap.Logger) *Scheduler {
    return &Scheduler{
        cron:       cron.New(cron.WithSeconds()), // Support second-level cron
        lock:       NewDistributedLock(redisClient),
        instanceID: uuid.New().String(),
        logger:    logger,
    }
}

func (s *Scheduler) Register(name string, schedule string, job func()) error {
    return s.cron.AddFunc(schedule, func() {
        s.runWithLock(name, job)
    })
}

func (s *Scheduler) runWithLock(name string, job func()) {
    // Thử acquire lock
    acquired, err := s.lock.Acquire(context.Background(), name, s.instanceID)
    if err != nil {
        s.logger.Error("Failed to acquire lock", zap.String("job", name), zap.Error(err))
        return
    }

    if !acquired {
        s.logger.Debug("Lock not acquired, skipping", zap.String("job", name))
        return
    }

    s.logger.Info("Acquired lock, starting job", zap.String("job", name))

    // Execute với timeout
    done := make(chan struct{})
    go func() {
        defer close(done)
        job()
    }()

    select {
    case <-done:
        s.logger.Info("Job completed", zap.String("job", name))
    case <-time.After(9 * time.Minute): // Slightly less than lock TTL
        s.logger.Warn("Job timeout, forcing release", zap.String("job", name))
    }

    // Release lock
    if err := s.lock.Release(context.Background(), name, s.instanceID); err != nil {
        s.logger.Error("Failed to release lock", zap.String("job", name), zap.Error(err))
    }
}

func (s *Scheduler) Start() {
    s.cron.Start()
}

func (s *Scheduler) Stop() {
    ctx := s.cron.Stop()
    <-ctx.Done()
}
```

**9.3.3 Token Cleanup Worker**

```go
// internal/worker/token_cleanup.go

type TokenCleanupWorker struct {
    db           *pgxpool.Pool
    scheduler    *Scheduler
    retentionDays int
    logger       *zap.Logger
}

func (w *TokenCleanupWorker) Register() error {
    return w.scheduler.Register(
        "refresh-token-cleanup",
        "0 0 2 * * *", // 2:00 AM daily
        w.run,
    )
}

func (w *TokenCleanupWorker) run() {
    ctx := context.Background()
    w.logger.Info("Starting refresh token cleanup")

    cutoff := time.Now().Add(-time.Duration(w.retentionDays) * 24 * time.Hour)

    query := `
        DELETE FROM auth_sessions
        WHERE expires_at < $1
           OR (revoked_at IS NOT NULL AND revoked_at < $1)
    `
    result, err := w.db.Exec(ctx, query, cutoff)
    if err != nil {
        w.logger.Error("Token cleanup failed", zap.Error(err))
        return
    }

    w.logger.Info("Token cleanup completed",
        zap.Int64("deleted", result.RowsAffected()),
        zap.Time("cutoff", cutoff))
}
```

**9.3.4 Audit Log Cleanup Worker**

```go
// internal/worker/audit_cleanup.go

type AuditCleanupWorker struct {
    db           *pgxpool.Pool
    scheduler    *Scheduler
    retentionDays int
    logger       *zap.Logger
}

func (w *AuditCleanupWorker) Register() error {
    return w.scheduler.Register(
        "audit-log-cleanup",
        "0 0 3 * * *", // 3:00 AM daily
        w.run,
    )
}

func (w *AuditCleanupWorker) run() {
    ctx := context.Background()
    w.logger.Info("Starting audit log cleanup")

    cutoff := time.Now().AddDate(0, 0, -w.retentionDays) // 6 months ago

    query := `
        DELETE FROM audit_logs
        WHERE log_category = 'USER'
          AND occurred_at < $1
    `
    result, err := w.db.Exec(ctx, query, cutoff)
    if err != nil {
        w.logger.Error("Audit cleanup failed", zap.Error(err))
        return
    }

    w.logger.Info("Audit log cleanup completed",
        zap.Int64("deleted", result.RowsAffected()),
        zap.Time("cutoff", cutoff))
}
```

**9.3.5 Key Rotation Worker**

```go
// internal/worker/key_rotation.go

type KeyRotationWorker struct {
    jwksService *jwks.Service
    scheduler   *Scheduler
    logger      *zap.Logger
}

func (w *KeyRotationWorker) Register() error {
    return w.scheduler.Register(
        "key-rotation",
        "0 0 0 1 */3 *", // 00:00 on 1st of every 3rd month
        w.run,
    )
}

func (w *KeyRotationWorker) run() {
    ctx := context.Background()
    w.logger.Info("Starting JWKS key rotation")

    // Bước 1: Kiểm tra key hiện tại có đủ old không (prevent unnecessary rotation)
    currentKey, err := w.jwksService.GetCurrentKey(ctx)
    if err != nil {
        w.logger.Error("Failed to get current key", zap.Error(err))
        return
    }

    if currentKey != nil && time.Since(currentKey.CreatedAt) < 90*24*time.Hour {
        w.logger.Info("Key rotation skipped — current key is not due for rotation",
            zap.Time("created_at", currentKey.CreatedAt))
        return
    }

    // Bước 2: Chuyển key cũ sang PHASE_OUT (nếu chưa có key mới)
    if err := w.jwksService.GenerateAndRotateKey(ctx); err != nil {
        w.logger.Error("Key rotation failed", zap.Error(err))
        return
    }

    // Bước 3: Cleanup RETIRED keys (> 7 days after phase_out)
    if err := w.jwksService.CleanupRetiredKeys(ctx); err != nil {
        w.logger.Warn("Failed to cleanup retired keys", zap.Error(err))
    }

    w.logger.Info("JWKS key rotation completed successfully")
}
```

#### 9.4 Phụ thuộc giữa các module

```
Module 9 (Background Jobs)
    ├── Dependencies: Module 3 (service logic), Module 6 (JWKS)
    ├── Consumed by: cmd/server/main.go
    └── Chạy độc lập, không xung đột với request handlers
```

---

### Module 10: Security & Middleware Enhancements

**Trưởng nhóm:** Backend Team 3
**Ước lượng:** 2–3 ngày

#### 10.1 Mục tiêu

Mở rộng các middleware bảo mật: JWT authentication filter (internal), RBAC cho admin API, input validation.

#### 10.2 Các file cần tạo / cập nhật

| File | Mô tả |
|------|--------|
| `internal/delivery/http/v1/middleware/auth_filter.go` | JWT internal filter |
| `internal/delivery/http/v1/middleware/rbac.go` | Role-based access control |
| `internal/delivery/http/v1/middleware/validate.go` | Input validation middleware |
| `internal/delivery/http/v1/middleware/security_headers.go` | Security headers (CSP, HSTS, etc.) |

#### 10.3 Chi tiết implementation

**JWT Internal Filter** (dùng khi Auth Service cần verify token nội bộ):

```go
// internal/delivery/http/v1/middleware/auth_filter.go

// InternalAuthFilter xác thực JWT dùng JWKS public key
// Dùng cho các endpoint bảo vệ (ví dụ: session management, audit)
type InternalAuthFilter struct {
    jwksService *jwks.Service
    redisCache  repository.CacheRepository
}

func (f *InternalAuthFilter) Authenticate() gin.HandlerFunc {
    return func(c *gin.Context) {
        authHeader := c.GetHeader("Authorization")
        if authHeader == "" || !strings.HasPrefix(authHeader, "Bearer ") {
            c.AbortWithStatusJSON(http.StatusUnauthorized, response.Unauthorized())
            return
        }

        tokenString := strings.TrimPrefix(authHeader, "Bearer ")

        // Parse và verify JWT (dùng JWKS)
        claims, err := f.verifyToken(c.Request.Context(), tokenString)
        if err != nil {
            c.AbortWithStatusJSON(http.StatusUnauthorized, response.Error("TOKEN_INVALID", err.Error()))
            return
        }

        // Set claims vào context cho handlers sử dụng
        c.Set("user_id", claims.UserID)
        c.Set("roles", claims.Roles)
        c.Set("jti", claims.JTI)
        c.Set("session_id", claims.Sid)

        c.Next()
    }
}

func (f *InternalAuthFilter) verifyToken(ctx context.Context, tokenString string) (*security.AccessTokenClaims, error) {
    token, err := jwt.ParseWithClaims(tokenString, &security.AccessTokenClaims{}, func(token *jwt.Token) (interface{}, error) {
        // Lấy kid từ header
        kid, ok := token.Header["kid"].(string)
        if !ok {
            return nil, errors.New("missing kid in token header")
        }

        // Lấy public key từ JWKS
        publicKey, err := f.jwksService.GetPublicKey(kid)
        if err != nil {
            return nil, fmt.Errorf("key not found: %w", err)
        }
        return publicKey, nil
    })

    if err != nil {
        return nil, err
    }

    claims, ok := token.Claims.(*security.AccessTokenClaims)
    if !ok || !token.Valid {
        return nil, errors.New("invalid token claims")
    }

    // Check denylist
    denied, _ := f.redisCache.IsTokenDenied(ctx, claims.JTI)
    if denied {
        return nil, errors.New("token has been revoked")
    }

    // Check revoked_before
    revokedBefore, _ := f.redisCache.GetRevokedBefore(ctx, claims.UserID)
    if revokedBefore != nil {
        issuedAt := claims.IssuedAt.Time
        if !issuedAt.After(*revokedBefore) {
            return nil, errors.New("token issued before revocation")
        }
    }

    return claims, nil
}
```

**RBAC Middleware:**

```go
// internal/delivery/http/v1/middleware/rbac.go

func RequireRole(roles ...string) gin.HandlerFunc {
    return func(c *gin.Context) {
        userRoles, exists := c.Get("roles")
        if !exists {
            c.AbortWithStatusJSON(http.StatusForbidden, response.Forbidden())
            return
        }

        userRoleList, ok := userRoles.([]string)
        if !ok {
            c.AbortWithStatusJSON(http.StatusInternalServerError, response.Error(...))
            return
        }

        roleSet := make(map[string]bool)
        for _, r := range userRoleList {
            roleSet[r] = true
        }

        for _, required := range roles {
            if roleSet[required] {
                c.Next()
                return
            }
        }

        c.AbortWithStatusJSON(http.StatusForbidden, response.Forbidden("Insufficient permissions"))
    }
}
```

---

### Module 11: Testing & Integration

**Trưởng nhóm:** QA Team / All Teams
**Ước lượng:** 6–10 ngày (chạy song song với các module khác)

#### 11.1 Mục tiêu

Triển khai test coverage đầy đủ, đặc biệt là integration test cho Grace Period, race condition, và toàn bộ workflow.

#### 11.2 Các file cần tạo

| File | Mô tả |
|------|--------|
| `tests/integration/grace_period_test.go` | Test Grace Period 10s |
| `tests/integration/token_theft_test.go` | Test token theft detection |
| `tests/integration/token_rotation_test.go` | Test concurrent refresh |
| `tests/integration/jwks_test.go` | Test JWKS generation + rotation |
| `tests/integration/outbox_test.go` | Test outbox pattern |
| `tests/integration/kafka_consumer_test.go` | Test Kafka consumer |
| `tests/integration/postgres_test.go` | Test DB operations |
| `tests/integration/redis_test.go` | Test Redis operations |
| `tests/unit/grace_service_test.go` | Unit test grace service |
| `tests/unit/token_generator_test.go` | Unit test RS256 JWT |
| `tests/unit/jwks_service_test.go` | Unit test JWKS |
| `tests/unit/audit_service_test.go` | Unit test audit |
| `tests/e2e/auth_flow_test.go` | E2E test scenarios |

#### 11.3 Integration Test với Testcontainers

**Testcontainers Setup:**

```go
// tests/integration/postgres_test.go

type IntegrationSuite struct {
    testing.T
    postgres   *postgres.Container
    redis      *redis.Container
    kafka      *kafka.Container
    db         *pgxpool.Pool
    redisClient *redis.Client
    kafkaBroker *kafka.Broker
}

func TestIntegrationSuite(t *testing.T) {
    suite.Run(t, new(IntegrationSuite))
}

func (s *IntegrationSuite) SetupSuite() {
    ctx := context.Background()

    // PostgreSQL
    pg, _ := postgres.Run(ctx, "postgres:15-alpine",
        postgres.WithDatabase("auth_db"),
        postgres.WithUsername("auth_user"),
        postgres.WithPassword("auth_pass"),
    )
    s.postgres = pg

    // Redis
    rd, _ := redis.Run(ctx, "redis:7-alpine")
    s.redis = rd

    // Kafka + Zookeeper
    kafkaContainer, _ := kafka.Run(ctx, "confluentinc/cp-kafka:7.5.0",
        kafka.WithClusterID("test-cluster"),
    )
    s.kafka = kafkaContainer

    // Kết nối DB
    dsn := fmt.Sprintf("postgres://auth_user:auth_pass@%s/auth_db?sslmode=disable",
        pg.Host(ctx))
    s.db, _ = pgxpool.New(ctx, dsn)

    // Kết nối Redis
    s.redisClient = redis.NewClient(&redis.Options{
        Addr: rd.Host(ctx),
    })

    // Chạy migrations
    s.runMigrations(ctx)
}

func (s *IntegrationSuite) TearDownSuite() {
    s.db.Close()
    s.redisClient.Close()
    s.postgres.Terminate(context.Background())
    s.redis.Terminate(context.Background())
    s.kafka.Terminate(context.Background())
}
```

#### 11.4 Grace Period Test (CRITICAL)

```go
// tests/integration/grace_period_test.go

func (s *IntegrationSuite) TestGracePeriod_HitWithin10Seconds() {
    ctx := context.Background()

    // Bước 1: Login để lấy tokens
    loginResp, _ := s.authClient.Login(ctx, "user@test.com", "password123")
    originalRT := loginResp.RefreshToken

    // Bước 2: Refresh lần 1 (tạo grace period)
    newTokens, _ := s.authClient.Refresh(ctx, originalRT)

    // Bước 3: Refresh lần 2 ngay sau đó với cùng token cũ
    cachedTokens, err := s.authClient.Refresh(ctx, originalRT)

    // ASSERT: Phải trả về tokens mới (grace period HIT)
    s.Require().NoError(err)
    s.Require().Equal(newTokens.AccessToken, cachedTokens.AccessToken)
    s.Require().Equal(newTokens.RefreshToken, cachedTokens.RefreshToken)
}

func (s *IntegrationSuite) TestGracePeriod_MissAfter10Seconds() {
    ctx := context.Background()

    // Login
    loginResp, _ := s.authClient.Login(ctx, "user@test.com", "password123")
    originalRT := loginResp.RefreshToken

    // Refresh lần 1 (tạo grace period)
    _, _ = s.authClient.Refresh(ctx, originalRT)

    // Đợi 11 giây (quá Grace Period)
    time.Sleep(11 * time.Second)

    // Refresh lần 2 với token cũ → phải bị reject (token theft)
    _, err := s.authClient.Refresh(ctx, originalRT)

    s.Require().Error(err)
    s.Require().Contains(err.Error(), "TOKEN_THEFT")
}

func (s *IntegrationSuite) TestGracePeriod_ConcurrentRefresh() {
    ctx := context.Background()

    // Simulate race condition: Nhiều request refresh cùng lúc
    loginResp, _ := s.authClient.Login(ctx, "user@test.com", "password123")
    originalRT := loginResp.RefreshToken

    // Spawn 10 concurrent refresh requests
    results := make(chan *RefreshResult, 10)
    errors := make(chan error, 10)

    for i := 0; i < 10; i++ {
        go func() {
            resp, err := s.authClient.Refresh(ctx, originalRT)
            if err != nil {
                errors <- err
                return
            }
            results <- resp
        }()
    }

    // Collect results
    successCount := 0
    theftCount := 0

    for i := 0; i < 10; i++ {
        select {
        case <-results:
            successCount++
        case err := <-errors:
            if strings.Contains(err.Error(), "TOKEN_THEFT") {
                theftCount++
            }
        }
    }

    // ASSERT: Chỉ 1 request thành công (request đầu tiên)
    // 9 request còn lại: hoặc success (grace period hit) hoặc theft (grace period miss)
    // Trong 10 request gần nhau (< 10s), tất cả phải là success
    s.Require().Equal(10, successCount, "All concurrent requests should succeed within grace period")
    s.Require().Equal(0, theftCount)
}

func (s *IntegrationSuite) TestTokenTheft_AllSessionsRevoked() {
    ctx := context.Background()

    // Setup: Tạo 3 sessions (3 devices)
    token1 := s.loginDevice("device1")
    token2 := s.loginDevice("device2")
    token3 := s.loginDevice("device3")

    // Simulate theft: refresh token1, sau đó đợi 11s rồi dùng token1 lần nữa
    _, _ = s.authClient.Refresh(ctx, token1.RefreshToken)
    time.Sleep(11 * time.Second)

    // Thử refresh với token cũ → theft detected
    _, err := s.authClient.Refresh(ctx, token1.RefreshToken)
    s.Require().Error(err)

    // Kiểm tra: TẤT CẢ 3 sessions đều bị revoke
    sessions, _ := s.authClient.GetSessions(ctx, token1.AccessToken)
    s.Require().Empty(sessions, "All sessions should be revoked after theft detection")

    // Kiểm tra: token2 và token3 cũng không hoạt động
    _, err = s.authClient.Refresh(ctx, token2.RefreshToken)
    s.Require().Error(err) // Token cũng bị revoke

    _, err = s.authClient.Refresh(ctx, token3.RefreshToken)
    s.Require().Error(err)
}

func (s *IntegrationSuite) TestRevokedBefore_ForceLogoutAll() {
    ctx := context.Background()

    // Login
    loginResp, _ := s.authClient.Login(ctx, "user@test.com", "password123")

    // Change password → trigger force logout all
    _, _ = s.authClient.ChangePassword(ctx, loginResp.AccessToken, "oldpass", "newpass")

    // Token cũ không còn valid
    _, err := s.authClient.Refresh(ctx, loginResp.RefreshToken)
    s.Require().Error(err)
}
```

#### 11.5 Unit Test cho Token Generator (RS256)

```go
// tests/unit/token_generator_test.go

func TestTokenGenerator_RS256(t *testing.T) {
    // Setup mock JWKS service trả về test key
    mockJwks := &mockJwksService{
        key: testRSAPrivateKey,
        kid: "test-key-001",
    }
    gen := security.NewTokenGenerator(mockJwks, "auth-service", 15*time.Minute)

    // Generate token
    userID := uuid.New()
    token, jti, err := gen.GenerateAccessToken(userID, []string{"USER", "ADMIN"})

    s.Require().NoError(err)
    s.Require().NotEmpty(token)
    s.Require().NotEmpty(jti)

    // Parse lại để verify
    parsed, err := jwt.ParseWithClaims(token, &security.AccessTokenClaims{}, func(t *jwt.Token) (interface{}, error) {
        return &testRSAPublicKey.PublicKey, nil
    })

    s.Require().NoError(err)
    s.Require().True(parsed.Valid)

    claims := parsed.Claims.(*security.AccessTokenClaims)
    s.Require().Equal(userID.String(), claims.UserID)
    s.Require().Equal([]string{"USER", "ADMIN"}, claims.Roles)
    s.Require().Equal(jti, claims.JTI)
    s.Require().Equal("test-key-001", parsed.Header["kid"]) // Quan trọng: kid phải có trong header
}
```

#### 11.6 E2E Test Scenarios

```go
// tests/e2e/auth_flow_test.go

func TestE2E_RegistrationAndLogin() {
    // 1. Register → nhận OTP
    // 2. Verify email → tài khoản ACTIVE
    // 3. Login → nhận access + refresh token
    // 4. Refresh token → nhận tokens mới
    // 5. Logout → tokens bị revoke
}

func TestE2E_PasswordResetFlow() {
    // 1. Forgot password → nhận email reset
    // 2. Click link reset → set password mới
    // 3. Kiểm tra: tất cả sessions cũ bị revoke
    // 4. Login với password mới → thành công
}

func TestE2E_MultiDeviceSession() {
    // 1. Login từ 3 devices
    // 2. Xem danh sách sessions → 3 items
    // 3. Logout device 2
    // 4. Kiểm tra: device 1 và 3 vẫn hoạt động, device 2 không
    // 5. Change password → tất cả devices bị revoke
}

func TestE2E_JwksKeyRotation() {
    // 1. Login → nhận token ký bằng key A
    // 2. Trigger key rotation
    // 3. Login → nhận token ký bằng key B (mới)
    // 4. Verify: token cũ (key A) vẫn hợp lệ với Gateway
    // 5. Sau 7 ngày: key A bị xóa, token cũ không hợp lệ
}
```

#### 11.7 Phụ thuộc giữa các module

```
Module 11 (Testing)
    ├── Dependencies: Tất cả các module khác (cần code đã hoàn thành)
    ├── Chạy song song với tất cả các module
    └── Cần CI/CD để chạy automated tests
```

---

## 5. Các bước triển khai cụ thể (Milestones)

### Milestone 1: Khung Project, Foundation & Database (Ngày 1–14)

**Mục tiêu:** Thiết lập nền tảng để các team phát triển song song.

| Task | Module | Owner | Effort |
|------|--------|-------|--------|
| Cập nhật go.mod, thêm dependencies | Module 1 | DevOps | 1 ngày |
| Tạo constants, config structs | Module 1 | Platform | 1 ngày |
| Tạo domain types (event, token, key) | Module 1 | Platform | 1 ngày |
| Thiết kế & review SQL schema (5 bảng mới) | Module 2 | Backend 1 | 2 ngày |
| Tạo migration files | Module 2 | Backend 1 | 1 ngày |
| sqlc config + generate code | Module 2 | Backend 1 | 1 ngày |
| Tạo entities (audit_log, jwt_key, outbox) | Module 2 | Backend 1 | 1 ngày |
| Repository interfaces + implementations | Module 2 | Backend 1 | 2 ngày |
| Setup Debezium connector config | Module 2 | DevOps | 1 ngày |
| Review & merge | All | Architecture | 1 ngày |

**Definition of Done:**
- [ ] Tất cả table tồn tại trong PostgreSQL
- [ ] sqlc generate ra code không lỗi
- [ ] Debezium connector có thể đọc outbox table
- [ ] Unit test cho repository interfaces

### Milestone 2: Core Domain Services + API + Caching (Ngày 15–35)

**Mục tiêu:** Hoàn thành core business logic, API endpoints, và Redis caching.

| Task | Module | Owner | Effort |
|------|--------|-------|--------|
| Sửa lại TokenGenerator (RS256) | Module 3 | Backend 2 | 3 ngày |
| Implement Grace Period 10s (Redis) | Module 3 | Backend 2 | 4 ngày |
| Sửa Refresh UseCase với Grace Period | Module 3 | Backend 2 | 3 ngày |
| Mở rộng Login/Logout/ChangePassword | Module 3 | Backend 2 | 2 ngày |
| Implement Audit Service | Module 3 | Backend 2 | 2 ngày |
| Redis Grace Cache implementation | Module 5 | Backend 1 | 2 ngày |
| Redis RevokeBefore Cache implementation | Module 5 | Backend 1 | 1 ngày |
| Mở rộng Denylist + Rate Limiting | Module 5 | Backend 1 | 2 ngày |
| JWKS Service (key gen, JWKS endpoint) | Module 6 | Backend 2 | 3 ngày |
| Caffeine L1 Cache + Redis Pub/Sub | Module 6 | Backend 2 | 2 ngày |
| Audit endpoints + routes | Module 4 | Backend 3 | 2 ngày |
| JWKS endpoint + routes | Module 4 | Backend 3 | 1 ngày |
| Mở rộng middleware (rate limit, RBAC) | Module 10 | Backend 3 | 2 ngày |
| Review & merge | All | Architecture | 2 ngày |

**Definition of Done:**
- [ ] Grace Period 10s hoạt động đúng (integration test pass)
- [ ] JWKS endpoint trả về đúng format RFC 7517
- [ ] RS256 JWT signature hoạt động
- [ ] Tất cả Redis key patterns hoạt động
- [ ] Audit log ghi đúng các event
- [ [ ] API endpoints respond đúng

### Milestone 3: Background Jobs + Outbox + Kafka Consumer (Ngày 30–50)

**Mục tiêu:** Hoàn thiện event-driven architecture và automation.

| Task | Module | Owner | Effort |
|------|--------|-------|--------|
| Outbox Publisher + Event Registry | Module 7 | Backend 3 | 3 ngày |
| Cập nhật business logic dùng Outbox | Module 7 | Backend 3 | 3 ngày |
| Verify Debezium CDC hoạt động | Module 7 | DevOps | 2 ngày |
| Kafka Consumer (admin events) | Module 8 | Backend 1 | 3 ngày |
| Consumer handlers (force logout, ban) | Module 8 | Backend 1 | 2 ngày |
| Distributed Lock implementation | Module 9 | Backend 2 | 2 ngày |
| Cron Scheduler + ShedLock | Module 9 | Backend 2 | 2 ngày |
| Token Cleanup Worker | Module 9 | Backend 2 | 1 ngày |
| Audit Cleanup Worker | Module 9 | Backend 2 | 1 ngày |
| Key Rotation Worker | Module 9 | Backend 2 | 2 ngày |
| Khởi tạo consumer + workers trong main | Module 8, 9 | Backend 2 | 1 ngày |
| Review & merge | All | Architecture | 2 ngày |

**Definition of Done:**
- [ ] Outbox pattern hoạt động (event insert cùng transaction)
- [ ] Debezium đọc outbox và publish lên Kafka
- [ ] Kafka consumer nhận ADMIN_FORCE_LOGOUT và revoke sessions
- [ ] Background workers chạy đúng schedule
- [ ] Distributed lock hoạt động (multi-instance safe)

### Milestone 4: Testing & Hardening (Ngày 45–60)

**Mục tiêu:** Đảm bảo chất lượng, độ tin cậy, và tài liệu vận hành.

| Task | Module | Owner | Effort |
|------|--------|-------|--------|
| Setup Testcontainers | Module 11 | QA | 2 ngày |
| Unit tests cho mỗi service | Module 11 | All | 5 ngày |
| Integration tests (Grace Period, theft) | Module 11 | QA | 4 ngày |
| Integration tests (JWKS rotation) | Module 11 | QA | 2 ngày |
| Integration tests (Kafka consumer) | Module 11 | QA | 2 ngày |
| E2E test scenarios | Module 11 | QA | 3 ngày |
| Performance testing (load test K6) | Module 11 | QA | 2 ngày |
| Security review | All | Security | 3 ngày |
| Tối ưu hóa (nếu cần) | All | All | 3 ngày |
| Hướng dẫn vận hành chi tiết | All | DevOps | 2 ngày |
| API documentation (OpenAPI) | Module 4 | Backend | 2 ngày |

**Definition of Done:**
- [ ] Tất cả tests pass
- [ ] Không có security vulnerabilities
- [ ] Load test đạt SLA (> 1000 req/s với latency < 100ms p99)
- [ ] Tài liệu vận hành hoàn chỉnh

---

## 6. Kế hoạch kiểm thử

### 6.1 Test Pyramid

```
        /\
       /  \       E2E Tests (few, slow, high confidence)
      /    \      - Full auth flows
     /------\     - Multi-device scenarios
    /        \    - Key rotation E2E
   /----------\   
  /            \  Integration Tests (moderate, Testcontainers)
 /              \ - Grace Period race condition
/----------------\ - Token theft detection
/                  \ - Kafka consumer
/   Unit Tests      \- JWKS cache invalidation
/ (many, fast,     \ - Outbox + Debezium CDC
/  isolated)        \
/--------------------\ 
```

### 6.2 Test Case Matrix

| ID | Test Case | Loại | Module | Priority |
|----|-----------|------|--------|---------|
| TC-01 | Refresh token bình thường | Unit | Module 3 | Cao |
| TC-02 | Grace Period HIT (trong 10s) | Integration | Module 3, 5 | **Cao** |
| TC-03 | Grace Period MISS (sau 10s) | Integration | Module 3, 5 | **Cao** |
| TC-04 | Concurrent refresh (10 requests) | Integration | Module 3, 5 | **Cao** |
| TC-05 | Token theft → revoke all sessions | Integration | Module 3 | **Cao** |
| TC-06 | Revoked_before → force logout all | Integration | Module 3, 5 | Cao |
| TC-07 | JWKS endpoint trả về đúng format | Unit | Module 6 | Cao |
| TC-08 | JWT RS256 sign và verify | Unit | Module 3 | Cao |
| TC-09 | JWT header chứa đúng `kid` | Unit | Module 3 | Cao |
| TC-10 | Key rotation cập nhật DB và cache | Integration | Module 6 | Cao |
| TC-11 | Redis Pub/Sub gửi JWKS_UPDATED | Integration | Module 6 | Trung |
| TC-12 | Outbox event insert cùng transaction | Integration | Module 7 | Cao |
| TC-13 | Debezium CDC publish lên Kafka | Integration | Module 7 | Cao |
| TC-14 | Kafka consumer xử lý USER_BANNED | Integration | Module 8 | Cao |
| TC-15 | Token cleanup worker xóa đúng records | Integration | Module 9 | Trung |
| TC-16 | Distributed lock chỉ 1 instance chạy | Integration | Module 9 | Trung |
| TC-17 | Audit log ghi đúng event types | Unit | Module 3 | Cao |
| TC-18 | Rate limiting block đúng requests | Integration | Module 5 | Trung |
| TC-19 | Login với sai password → block | Integration | Module 3 | Trung |
| TC-20 | Password change → revoke all sessions | Integration | Module 3 | Cao |

### 6.3 Cách chạy Integration Tests

```bash
# Yêu cầu: Docker daemon đang chạy

# Chạy tất cả tests
cd go/services/auth-service
go test -v ./tests/...

# Chạy chỉ integration tests
go test -v -tags=integration ./tests/integration/...

# Chạy Grace Period tests
go test -v -tags=integration ./tests/integration/... -run "Grace"

# Chạy với coverage
go test -v -tags=integration -cover ./tests/... -coverprofile=coverage.out

# Xem coverage report
go tool cover -html=coverage.out

# Load test với k6
k6 run tests/load/auth_load_test.js \
  -e BASE_URL=http://localhost:8080 \
  -e USERS=100 \
  -e DURATION=30s
```

### 6.4 Load Test Scenario (K6)

```javascript
// tests/load/auth_load_test.js

import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '30s', target: 100 },  // Ramp up
    { duration: '1m', target: 100 },   // Steady state
    { duration: '30s', target: 200 },  // Spike
    { duration: '1m', target: 200 },
    { duration: '30s', target: 0 },    // Ramp down
  ],
  thresholds: {
    http_req_duration: ['p(95)<200', 'p(99)<500'],
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  // Login
  const loginRes = http.post(`${__ENV.BASE_URL}/api/v1/auth/login`, JSON.stringify({
    email: `user${Math.floor(Math.random()*1000)}@test.com`,
    password: 'password123',
  }), { headers: { 'Content-Type': 'application/json' } });

  check(loginRes, {
    'login successful': (r) => r.status === 200,
    'has access token': (r) => r.json('access_token') !== undefined,
  });

  // Refresh token
  const refreshRes = http.post(`${__ENV.BASE_URL}/api/v1/auth/refresh`, JSON.stringify({
    refresh_token: loginRes.json('refresh_token'),
  }), { headers: { 'Content-Type': 'application/json' } });

  check(refreshRes, {
    'refresh successful': (r) => r.status === 200,
  });

  sleep(1);
}
```

---

## 7. Hướng dẫn vận hành & Cấu hình

### 7.1 Biến môi trường bắt buộc

```bash
# Database
AUTH_DB_HOST=localhost
AUTH_DB_PORT=5432
AUTH_DB_NAME=auth_db
AUTH_DB_USER=auth_user
AUTH_DB_PASSWORD=<secret>
AUTH_DB_POOL_MIN=5
AUTH_DB_POOL_MAX=20

# Redis
AUTH_REDIS_HOST=localhost
AUTH_REDIS_PORT=6379
AUTH_REDIS_PASSWORD=<secret>
AUTH_REDIS_DB=0

# Kafka
AUTH_KAFKA_BROKERS=localhost:9092,localhost:9093,localhost:9094
AUTH_KAFKA_CONSUMER_GROUP=auth-service-consumer

# Security
AUTH_MASTER_KEY=<64-hex-characters-256-bit>  # Encryption key for private keys
AUTH_JWT_ISSUER=auth-service

# Application
AUTH_APP_HOST=0.0.0.0
AUTH_APP_PORT=8080
AUTH_APP_ENV=production  # development/staging/production

# Debezium (ops)
DEBEZIUM_CONNECTOR_URL=http://localhost:8083

# Feature Flags
AUTH_FEATURE_KEY_ROTATION=true
AUTH_FEATURE_GRACE_PERIOD=true
AUTH_FEATURE_OUTBOX=true
```

### 7.2 Cấu hình Redis chi tiết

```yaml
redis:
  pool:
    min-idle: 10
    max-idle: 50
    max-conns: 100
  timeout:
    dial: 5s
    read: 3s
    write: 3s
  # Redis key eviction policy
  maxmemory-policy: allkeys-lru
  # Persistence (RDB + AOF recommended for production)
  save:
    - "900 1"   # Save if at least 1 key changed in 15 minutes
    - "300 10"  # Save if at least 10 keys changed in 5 minutes
    - "60 10000" # Save if at least 10000 keys changed in 1 minute
```

### 7.3 Cấu hình Kafka

```yaml
kafka:
  # Producer (cho outbox events — nhưng thực ra Debezium tự publish)
  producer:
    acks: all
    retries: 3
    compression: snappy
    batch-size: 16384
    linger-ms: 5
  
  # Consumer (cho admin events)
  consumer:
    group-id: auth-service-consumer
    auto-offset-reset: earliest
    enable-auto-commit: false
    max-poll-records: 100
    session-timeout-ms: 30000
    heartbeat-interval-ms: 10000
  
  # Topics
  topics:
    auth-events:
      partitions: 12
      replication-factor: 3
    admin-events:
      partitions: 12
      replication-factor: 3
```

### 7.4 Cấu hình Debezium Connector

Xem file `deployments/debezium/connector.json` trong Module 2.

**Các bước deploy Debezium:**

```bash
# 1. Register connector
curl -i -X POST http://localhost:8083/connectors \
  -H "Content-Type: application/json" \
  -d @deployments/debezium/connector.json

# 2. Verify connector status
curl -s http://localhost:8083/connectors/auth-outbox-connector/status | jq

# 3. Check topics created
kafka-topics --bootstrap-server localhost:9092 --list | grep auth
```

### 7.5 Distributed Lock (ShedLock)

```yaml
workers:
  enabled: true
  lock-at-most-for: 10m  # Lock tự động expire sau 10 phút
  lock-at-least-for: 1m  # Lock tồn tại ít nhất 1 phút (prevent frequent re-acquisition)
```

### 7.6 Health Check Endpoints

```
GET /health          → Health status (DB, Redis, Kafka connectivity)
GET /ready           → Readiness (tất cả dependencies sẵn sàng)
GET /metrics         → Prometheus metrics (nếu enable)
```

### 7.7 Logging Configuration

```yaml
# Log levels
logging:
  level:
    root: INFO
    github.com/yourusername/auth-service: DEBUG
    auth.usecase.authentication: DEBUG  # Chi tiết cho grace period debugging
    auth.worker: INFO
    auth.infrastructure.event: INFO

  # Structured JSON logs cho production
  format: json
  output: stdout

  # Sampling cho high-traffic environments
  sampling:
    initial: 100
    thereafter: 100
```

---

## 8. Rủi ro và Phương án giảm thiểu

### 8.1 Rủi ro kỹ thuật

| # | Rủi ro | Mức độ | Phương án giảm thiểu |
|---|--------|--------|----------------------|
| R1 | **Grace Period TTL = 10s có thể gây false positive token theft** trong môi trường mạng chậm (Mobile 3G) | Cao | Cho phép cấu hình TTL qua env var (`AUTH_GRACE_PERIOD_TTL`). Mặc định 10s, có thể tăng lên 15-30s cho mobile. Thêm metric `grace_period_hit_rate` để monitor. |
| R2 | **Debezium CDC lag** có thể delay event propagation | Trung bình | Monitor lag metric. Có fallback: nếu event không được publish sau X phút, trigger manual sync job. Đặt SLA cho Debezium: event latency < 5 giây. |
| R3 | **Redis master failover** gây mất grace period cache | Trung bình | Grace period cache có TTL 10s — mất dữ liệu grace period chỉ ảnh hưởng đến concurrent refresh requests trong khoảng 10s. Không ảnh hưởng đến security (token theft vẫn được phát hiện qua DB). Khuyến nghị: Redis Cluster hoặc Sentinel. |
| R4 | **Private key mất** (nếu mã hóa lỗi hoặc master key leak) | Cao | 1. Mã hóa private key bằng AES-256-GCM. 2. Lưu master key trong Vault/KMS, không hardcode. 3. Có ít nhất 1 cặp key backup được encrypt bằng key khác. 4. Key rotation tự động 3 tháng/lần. |
| R5 | **Key rotation khi có token cũ chưa hết hạn** | Trung bình | Thuật toán: Key mới → ACTIVE. Key cũ → PHASE_OUT (vẫn dùng để verify). Sau 7 ngày → RETIRED (xóa). JWKS endpoint trả về cả ACTIVE và PHASE_OUT keys. |
| R6 | **Kafka consumer lag** khi xử lý ADMIN_FORCE_LOGOUT | Trung bình | Consumer group với multiple partitions. Nếu partition count < 2, xử lý tuần tự. Monitor consumer lag. Có retry mechanism với exponential backoff. |
| R7 | **Database deadlock** khi nhiều request refresh cùng user_id | Trung bình | Dùng `SELECT ... FOR UPDATE` với `ORDER BY id`. Deadlock giữa 2 sessions có thể xảy ra nếu thứ tự lock không nhất quán. Giải pháp: lock theo `user_id` trước khi lock theo `token_hash` (hoặc ngược lại, nhưng phải CONSISTENT). |
| R8 | **Clock skew** giữa các instances | Thấp | Dùng NTP sync. Kiểm tra `iat` vs `revoked_before` với buffer 1 giây để tránh false negative do clock skew nhỏ. |

### 8.2 Rủi ro vận hành

| # | Rủi ro | Mức độ | Phương án giảm thiểu |
|---|--------|--------|----------------------|
| R9 | **Auth service down** → không ai đăng nhập được | Cao | Multi-instance deployment (tối thiểu 3 pods). Circuit breaker pattern. Graceful degradation: cho phép đăng nhập với token cũ nếu Auth service không phản hồi (Gateway tự verify bằng JWKS). |
| R10 | **Redis down** → grace period không hoạt động, rate limiting fail | Cao | Redis Sentinel/Cluster. Fallback: graceful degradation — vẫn cho phép login nhưng không có rate limiting. Audit log tất cả failures. |
| R11 | **Audit log table phình to** | Trung bình | Cron cleanup chạy hàng ngày (Module 9). Partition table theo tháng để `DELETE` nhanh hơn. Monitoring: alert nếu table size > 10GB. |

### 8.3 Rủi ro bảo mật

| # | Rủi ro | Mức độ | Phương án giảm thiểu |
|---|--------|--------|----------------------|
| R12 | **Refresh token leak** (network sniffing) | Cao | Refresh token chỉ gửi qua HTTPS. HttpOnly, Secure, SameSite=Strict cookie. SHA-256 hash trong DB. |
| R13 | **Brute force refresh token** | Trung bình | Token là 256-bit random → không khả thi về mặt brute force. Tuy nhiên vẫn có rate limiting trên `/refresh` endpoint. |
| R14 | **SQL Injection** | Thấp | Dùng parameterized queries (pgx). sqlc generate type-safe queries. |
| R15 | **Privilege escalation** (user tự thêm role ADMIN) | Cao | Roles được lưu trong DB, verify qua query. JWT chỉ chứa roles từ DB, không có cách nào client tự set. Gateway phải verify roles từ token, không tin header. |

---

## 9. Phụ lục

### Phụ lục A: Database Schema đầy đủ

```sql
-- Full schema bao gồm tất cả bảng (consolidated)

-- 1. Users
CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    roles JSONB NOT NULL DEFAULT '["USER"]',
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING_VERIFICATION',
    email_verified_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_status ON users(status);

-- 2. Sessions (Refresh Tokens)
CREATE TABLE IF NOT EXISTS auth_sessions (
    id VARCHAR(255) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    user_id UUID NOT NULL REFERENCES users(id),
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    family_id UUID NOT NULL DEFAULT gen_random_uuid(),
    replaced_by VARCHAR(255),
    device_id VARCHAR(255) NOT NULL,
    device_info VARCHAR(255),
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at TIMESTAMP WITH TIME ZONE,
    grace_expires_at TIMESTAMP WITH TIME ZONE,
    last_activity TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_sessions_user_id ON auth_sessions(user_id);
CREATE INDEX idx_sessions_token_hash ON auth_sessions(token_hash);
CREATE INDEX idx_sessions_family_id ON auth_sessions(family_id);
CREATE INDEX idx_sessions_revoked_at ON auth_sessions(revoked_at);
CREATE INDEX idx_sessions_expires_at ON auth_sessions(expires_at);
CREATE INDEX idx_sessions_device_id ON auth_sessions(device_id);

-- 3. Login Attempts
CREATE TABLE IF NOT EXISTS auth_login_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID REFERENCES users(id),
    ip_address VARCHAR(45) NOT NULL,
    user_agent VARCHAR(255),
    success BOOLEAN NOT NULL DEFAULT FALSE,
    attempt_type VARCHAR(50) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_login_attempts_ip ON auth_login_attempts(ip_address);
CREATE INDEX idx_login_attempts_user_id ON auth_login_attempts(user_id);
CREATE INDEX idx_login_attempts_created_at ON auth_login_attempts(created_at);

-- 4. Password Reset Tokens
CREATE TABLE IF NOT EXISTS auth_password_reset_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_password_reset_token_hash ON auth_password_reset_tokens(token_hash);
CREATE INDEX idx_password_reset_user_id ON auth_password_reset_tokens(user_id);

-- 5. JWT Keys
CREATE TABLE IF NOT EXISTS jwt_keys (
    kid VARCHAR(100) PRIMARY KEY,
    public_key TEXT NOT NULL,
    private_key_encrypted TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    algorithm VARCHAR(20) NOT NULL DEFAULT 'RS256',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    rotated_at TIMESTAMP WITH TIME ZONE,
    expires_at TIMESTAMP WITH TIME ZONE,
    version INT NOT NULL DEFAULT 1
);
CREATE INDEX idx_jwt_keys_status ON jwt_keys(status);

-- 6. Outbox Events
CREATE TABLE IF NOT EXISTS outbox_events (
    event_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_type VARCHAR(100) NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    partition_key UUID NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    metadata JSONB
);
CREATE INDEX idx_outbox_events_created_at ON outbox_events(created_at);
CREATE INDEX idx_outbox_events_aggregate ON outbox_events(aggregate_type, aggregate_id);
CREATE INDEX idx_outbox_events_partition ON outbox_events(partition_key);

-- 7. Audit Logs
CREATE TABLE IF NOT EXISTS audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id VARCHAR(255) NOT NULL,
    actor_type VARCHAR(50) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    ip_address VARCHAR(45),
    device_id VARCHAR(255),
    device_info VARCHAR(255),
    security_level VARCHAR(50) NOT NULL DEFAULT 'INFO',
    payload JSONB,
    log_category VARCHAR(50) NOT NULL DEFAULT 'USER',
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_audit_logs_actor_id ON audit_logs(actor_id);
CREATE INDEX idx_audit_logs_event_type ON audit_logs(event_type);
CREATE INDEX idx_audit_logs_occurred_at ON audit_logs(occurred_at);
CREATE INDEX idx_audit_logs_security_level ON audit_logs(security_level);
CREATE INDEX idx_audit_logs_log_category ON audit_logs(log_category);
CREATE INDEX idx_audit_logs_category_occurred ON audit_logs(log_category, occurred_at DESC);
```

### Phụ lục B: API Endpoints tổng hợp

| Method | Endpoint | Auth | Mô tả |
|--------|----------|------|--------|
| POST | `/api/v1/auth/register` | None | Đăng ký tài khoản mới |
| POST | `/api/v1/auth/verify-email` | None | Xác thực email bằng OTP |
| POST | `/api/v1/auth/login` | None | Đăng nhập |
| POST | `/api/v1/auth/refresh` | None | Refresh token (với Grace Period) |
| POST | `/api/v1/auth/logout` | JWT | Đăng xuất thiết bị hiện tại |
| POST | `/api/v1/auth/logout-all` | JWT | Đăng xuất tất cả thiết bị |
| GET | `/api/v1/auth/sessions` | JWT | Lấy danh sách sessions |
| DELETE | `/api/v1/auth/sessions/{id}` | JWT | Xóa một session cụ thể |
| POST | `/api/v1/auth/forgot-password` | None | Yêu cầu reset mật khẩu |
| POST | `/api/v1/auth/reset-password` | None | Đặt lại mật khẩu |
| POST | `/api/v1/auth/change-password` | JWT | Đổi mật khẩu |
| POST | `/api/v1/auth/resend-otp` | None | Gửi lại OTP |
| GET | `/api/v1/auth/audit/my-login-history` | JWT | Lịch sử đăng nhập (user) |
| GET | `/api/v1/auth/audit/admin/history-logs` | JWT+ADMIN | Lịch sử audit log (admin) |
| GET | `/.well-known/jwks.json` | None | JWKS public keys |
| GET | `/health` | None | Health check |
| GET | `/ready` | None | Readiness check |

### Phụ lục C: Kafka Event Schemas (JSON Schema)

```json
// USER_OTP_REQUESTED
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "properties": {
    "event_id": { "type": "string", "format": "uuid" },
    "event_type": { "const": "USER_OTP_REQUESTED" },
    "aggregate_id": { "type": "string", "format": "uuid" },
    "occurred_at": { "type": "string", "format": "date-time" },
    "payload": {
      "type": "object",
      "properties": {
        "user_id": { "type": "string" },
        "email": { "type": "string", "format": "email" },
        "otp_type": { "enum": ["REGISTER_VERIFICATION", "PASSWORD_RESET", "LOGIN_2FA"] }
      },
      "required": ["user_id", "email", "otp_type"]
    }
  }
}

// USER_VERIFIED_SUCCESS
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "properties": {
    "event_id": { "type": "string", "format": "uuid" },
    "event_type": { "const": "USER_VERIFIED_SUCCESS" },
    "aggregate_id": { "type": "string", "format": "uuid" },
    "occurred_at": { "type": "string", "format": "date-time" },
    "payload": {
      "type": "object",
      "properties": {
        "user_id": { "type": "string" },
        "email": { "type": "string" },
        "roles": { "type": "array", "items": { "type": "string" } }
      },
      "required": ["user_id", "email", "roles"]
    }
  }
}

// TOKEN_THEFT_DETECTED
{
  "type": "object",
  "properties": {
    "event_type": { "const": "TOKEN_THEFT_DETECTED" },
    "payload": {
      "properties": {
        "user_id": { "type": "string" },
        "theft_refresh_token_id": { "type": "string" },
        "action_taken": { "const": "ALL_TOKENS_REVOKED" }
      }
    }
  }
}
```

### Phụ lục D: Monitoring & Alerting

**Metrics Prometheus cần expose:**

```go
// Auth Service metrics
auth_tokens_issued_total{type="access|refresh"}
auth_token_refresh_total{result="success|grace_hit|grace_miss|theft"}
auth_token_theft_detected_total
auth_jwks_cache_hits_total
auth_jwks_cache_misses_total
auth_grace_period_hit_rate        // Ratio để detect false positive
auth_redis_operation_duration_seconds{operation="get|set|del"}
auth_kafka_consumer_lag
auth_background_jobs_duration_seconds{job="token_cleanup|audit_cleanup|key_rotation"}
auth_rate_limit_blocked_total{type="login|register|forgot_pw"}
auth_active_sessions_total
auth_db_query_duration_seconds{query="session_select|token_insert"}
```

**Alerting rules:**

```yaml
groups:
  - name: auth-service
    rules:
      - alert: HighTokenTheftRate
        expr: rate(auth_token_theft_detected_total[5m]) > 0.1
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "High token theft detection rate"
          description: "Token theft rate > 0.1/s trong 5 phút"

      - alert: GracePeriodHitRateLow
        expr: auth_grace_period_hit_rate < 0.5
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "Grace period hit rate low"
          description: "Có thể có vấn đề về mạng hoặc client gửi quá chậm"

      - alert: AuthServiceDown
        expr: up{job="auth-service"} == 0
        for: 1m
        labels:
          severity: critical

      - alert: RedisConnectionErrors
        expr: rate(auth_redis_operation_errors_total[5m]) > 1
        for: 5m
        labels:
          severity: warning

      - alert: KafkaConsumerLag
        expr: auth_kafka_consumer_lag > 10000
        for: 10m
        labels:
          severity: warning
```

### Phụ lục E: Glossary

| Thuật ngữ | Định nghĩa |
|-----------|------------|
| **jti** | JWT ID — UUID duy nhất cho mỗi Access Token, dùng để revoke riêng lẻ |
| **kid** | Key ID — Identifier cho cặp khóa JWKS, được embed trong JWT header |
| **Grace Period** | Khoảng thời gian 10 giây cho phép refresh token cũ sau khi đã rotate, để chịu được concurrent requests |
| **Token Theft** | Tình huống khi refresh token cũ được sử dụng sau khi grace period hết hạn |
| **Force Logout** | Thu hồi toàn bộ session của user bằng `revoked_before` timestamp |
| **Outbox Pattern** | Pattern đảm bảo event được publish cùng transaction với business data |
| **Debezium CDC** | Change Data Capture — đọc WAL của PostgreSQL để tự động publish changes lên Kafka |
| **ShedLock** | Cơ chế distributed lock cho cron jobs, đảm bảo chỉ 1 instance chạy |
| **PHASE_OUT** | Trạng thái key hết hạn nhưng vẫn giữ lại để verify token cũ |
| **Dual Token** | Pattern dùng 2 loại token: Access Token (JWT, short-lived) + Refresh Token (opaque, long-lived) |

---

*Document được viết bởi Software Architecture Team. Vui lòng review và approve trước khi bắt đầu implementation.*
