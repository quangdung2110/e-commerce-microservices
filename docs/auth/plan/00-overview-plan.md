# Auth Service - Implementation Plan Overview

> **Document Version:** 1.0  
> **Date:** May 18, 2026  
> **Status:** Implementation Blueprint  
> **Service:** Auth Service (E-Commerce Platform)

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [Go Project Structure](#2-go-project-structure)
3. [Module Overview](#3-module-overview)
4. [Dependency Graph](#4-dependency-graph)
5. [Implementation Order](#5-implementation-order)
6. [Technology Stack](#6-technology-stack)
7. [Naming Conventions](#7-naming-conventions)

---

## 1. Executive Summary

Auth Service là Identity Provider trung tâm của hệ thống E-Commerce Microservices, đóng vai trò:
- **Đăng ký / Đăng nhập / Quản lý mật khẩu**
- **Vòng đời Token** (Dual Token Pattern với Grace Period 10 giây)
- **JWKS Management** và Key Rotation
- **Audit Logging** (Security & Audit Center)
- **Session Management** (Xem & Thu hồi thiết bị)

### Key Security Features

| Feature | Description |
|---------|-------------|
| Dual Token | Access Token (JWT, 15 phút) + Refresh Token (Opaque, 30 ngày) |
| Grace Period | 10 giây chống race condition khi refresh |
| Denylist | Redis-based single token revocation |
| User Revision | Force logout bằng timestamp |
| Brute-Force Protection | Tự động khóa sau 5 lần sai |
| JWKS Rotation | Key rotation 30 ngày, retirement 31 ngày |

---

## 2. Go Project Structure

Theo chuẩn **Golang Common Layout**, Auth Service sẽ có cấu trúc:

```
auth-service/
├── cmd/
│   └── server/
│       └── main.go                    # Application entry point
│
├── internal/
│   ├── api/                           # API Layer (HTTP Handlers)
│   │   ├── handler/
│   │   │   ├── auth_handler.go        # /login, /register, /logout, /refresh
│   │   │   ├── user_handler.go        # /profile, /change-password
│   │   │   ├── session_handler.go     # /sessions (xem/thu hồi thiết bị)
│   │   │   ├── otp_handler.go         # /verify-email, /resend-otp
│   │   │   ├── forgot_handler.go      # /forgot-password, /reset-password
│   │   │   ├── admin_handler.go       # /admin/audit-logs, /admin/ban
│   │   │   └── health_handler.go      # /health, /ready
│   │   ├── middleware/
│   │   │   ├── auth.go                # JWT verification (internal)
│   │   │   ├── ratelimit.go           # Rate limiting
│   │   │   ├── logging.go             # Request logging
│   │   │   ├── recovery.go            # Panic recovery
│   │   │   └── tracing.go            # Distributed tracing
│   │   ├── router/
│   │   │   └── router.go             # Route definitions
│   │   ├── dto/
│   │   │   ├── request/              # Incoming request DTOs
│   │   │   │   ├── login.go
│   │   │   │   ├── register.go
│   │   │   │   ├── refresh.go
│   │   │   │   ├── verify_email.go
│   │   │   │   ├── forgot_password.go
│   │   │   │   ├── reset_password.go
│   │   │   │   ├── change_password.go
│   │   │   │   ├── sessions.go
│   │   │   │   └── admin.go
│   │   │   └── response/             # Outgoing response DTOs
│   │   │       ├── token.go
│   │   │       ├── session.go
│   │   │       ├── audit.go
│   │   │       └── error.go
│   │   └── validator/
│   │       └── validator.go          # Request validation
│   │
│   ├── domain/                        # Domain Layer (Core Business Logic)
│   │   ├── entity/
│   │   │   ├── user.go              # User aggregate root
│   │   │   ├── refresh_token.go     # RefreshToken entity
│   │   │   ├── jwt_key.go           # JWT Key entity
│   │   │   ├── audit_log.go         # AuditLog entity
│   │   │   └── outbox_event.go      # OutboxEvent entity
│   │   ├── service/
│   │   │   ├── auth_service.go      # Login, Logout, Refresh logic
│   │   │   ├── user_service.go      # Registration, verification
│   │   │   ├── password_service.go   # Password management
│   │   │   ├── session_service.go   # Session management
│   │   │   ├── otp_service.go       # OTP generation/verification
│   │   │   ├── audit_service.go     # Audit logging
│   │   │   └── jwt_service.go       # JWT generation/verification
│   │   ├── port/                    # Interface definitions (Ports)
│   │   │   ├── user_repository.go
│   │   │   ├── refresh_token_repository.go
│   │   │   ├── jwt_key_repository.go
│   │   │   ├── audit_log_repository.go
│   │   │   ├── outbox_repository.go
│   │   │   ├── cache.go             # Redis cache interface
│   │   │   ├── publisher.go         # Event publisher interface
│   │   │   └── password_hasher.go
│   │   ├── vo/                      # Value Objects
│   │   │   ├── email.go
│   │   │   ├── password.go
│   │   │   ├── user_status.go       # PENDING_VERIFICATION, ACTIVE, LOCKED, BANNED
│   │   │   └── token_pair.go
│   │   ├── event/                   # Domain events
│   │   │   ├── auth_events.go       # Login, Logout events
│   │   │   ├── user_events.go       # Registration, verification events
│   │   │   └── security_events.go   # Lock, Ban events
│   │   └── errors/
│   │       └── errors.go            # Domain-specific errors
│   │
│   ├── infrastructure/               # Infrastructure Layer (Adapters)
│   │   ├── postgres/
│   │   │   ├── connection.go        # DB connection pool (pgx)
│   │   │   ├── user_repo.go        # Implements UserRepository
│   │   │   ├── refresh_token_repo.go
│   │   │   ├── jwt_key_repo.go
│   │   │   ├── audit_log_repo.go
│   │   │   ├── outbox_repo.go
│   │   │   └── migrations/          # SQL migrations
│   │   │       ├── 001_initial_schema.up.sql
│   │   │       └── 001_initial_schema.down.sql
│   │   ├── redis/
│   │   │   ├── connection.go        # Redis client setup
│   │   │   ├── cache_repo.go       # Implements Cache interface
│   │   │   ├── denylist.go         # Token denylist operations
│   │   │   ├── grace_period.go     # Grace period cache
│   │   │   ├── rate_limit.go       # Rate limiting
│   │   │   ├── otp_cache.go        # OTP caching
│   │   │   ├── reset_token.go      # Password reset token
│   │   │   └── failed_login.go    # Failed login counter
│   │   ├── kafka/
│   │   │   ├── config.go           # Kafka configuration
│   │   │   ├── producer.go         # Event producer
│   │   │   ├── consumer.go         # Admin event consumer
│   │   │   └── handler/            # Event handlers
│   │   │       └── admin_handler.go # ADMIN_FORCE_LOGOUT, USER_BANNED
│   │   └── pubsub/
│   │       ├── publisher.go         # Redis Pub/Sub publisher
│   │       └── subscriber.go        # JWKS update subscriber
│   │
│   ├── workers/                     # Background Workers
│   │   ├── token_cleanup/
│   │   │   └── worker.go           # Dọn refresh tokens hết hạn
│   │   ├── key_rotation/
│   │   │   └── worker.go           # JWKS key rotation (30 ngày)
│   │   ├── key_retirement/
│   │   │   └── worker.go           # Key retirement (31 ngày sau rotation)
│   │   ├── audit_cleanup/
│   │   │   └── worker.go           # Dọn audit logs (6 tháng)
│   │   ├── outbox_cleanup/
│   │   │   └── worker.go           # Dọn outbox events (14 ngày)
│   │   └── auto_unlock/
│   │       └── worker.go           # Auto-unlock tài khoản (30 phút)
│   │
│   └── common/                      # Shared Utilities
│       ├── constants/
│       │   └── constants.go        # Magic numbers, TTL values
│       ├── errors/
│       │   └── errors.go           # Shared error types
│       ├── keybuilder/
│       │   └── keybuilder.go       # Redis key pattern builder
│       ├── jwt/
│       │   └── jwt.go               # JWT utilities
│       ├── crypto/
│       │   └── crypto.go           # Cryptographic utilities
│       └── validator/
│           └── validator.go         # Cross-cutting validators
│
├── configs/
│   ├── application.yaml             # Main configuration
│   └── .env.example                # Environment template
│
├── scripts/
│   └── compliance-worker.go        # Standalone compliance worker
│
├── test/
│   ├── mocks/                      # Mock implementations
│   │   ├── user_repo_mock.go
│   │   ├── cache_mock.go
│   │   └── publisher_mock.go
│   └── integration/
│       └── integration_test.go
│
├── go.mod
├── go.sum
├── Dockerfile
├── Makefile
├── docker-compose.yaml
└── README.md
```

---

## 3. Module Overview

| Module | File | Description | Priority |
|--------|------|-------------|----------|
| **Foundation** | | | |
| Module 0 | `module-00-project-setup.md` | Project setup, dependencies, config | Phase 1 |
| Module 1 | `module-01-database-layer.md` | DB schema, migrations, entities | Phase 1 |
| Module 2 | `module-02-redis-cache-layer.md` | Redis caching, rate limiting | Phase 1 |
| **Core Features** | | | |
| Module 3 | `module-03-user-management.md` | Registration, email verification, OTP | Phase 2 |
| Module 4 | `module-04-authentication.md` | Login, logout, brute-force protection | Phase 2 |
| Module 5 | `module-05-token-management.md` | JWT, refresh tokens, grace period | Phase 2 |
| Module 6 | `module-06-session-management.md` | Session listing, revocation | Phase 2 |
| Module 7 | `module-07-password-management.md` | Change password, forgot/reset | Phase 3 |
| Module 8 | `module-08-audit-logging.md` | Audit events, admin API | Phase 3 |
| **Advanced Features** | | | |
| Module 9 | `module-09-jwks-management.md` | JWKS endpoint, key rotation | Phase 3 |
| Module 10 | `module-10-event-publishing.md` | Kafka events, Redis Pub/Sub | Phase 3 |
| Module 11 | `module-11-background-workers.md` | All scheduled workers | Phase 4 |
| **Testing & Deployment** | | | |
| Module 12 | `module-12-testing.md` | Unit, integration, load testing | Phase 4 |
| Module 13 | `module-13-deployment.md` | Docker, health checks, graceful shutdown | Phase 4 |

---

## 4. Dependency Graph

```
┌─────────────────────────────────────────────────────────────────┐
│                        PHASE 1: FOUNDATION                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   ┌──────────────┐    ┌──────────────┐    ┌──────────────┐     │
│   │ Module 0     │    │ Module 1     │    │ Module 2     │     │
│   │ Project      │───▶│ Database     │───▶│ Redis Cache  │     │
│   │ Setup        │    │ Layer        │    │ Layer        │     │
│   └──────────────┘    └──────────────┘    └──────────────┘     │
│          │                   │                   │              │
│          │                   ▼                   ▼              │
│          │          ┌──────────────────┐  ┌──────────────────┐   │
│          │          │ Domain Entities  │  │ Domain Ports     │   │
│          │          │ (User, Token,    │  │ (Interfaces)     │   │
│          │          │  AuditLog, etc)  │  │                  │   │
│          │          └──────────────────┘  └──────────────────┘   │
│          │                   │                   │              │
│          └───────────────────┴───────────────────┘              │
│                              │                                 │
└──────────────────────────────┼──────────────────────────────────┘
                               │
┌──────────────────────────────┼──────────────────────────────────┐
│                        PHASE 2: CORE                             │
├──────────────────────────────┼──────────────────────────────────┤
│                              ▼                                  │
│          ┌────────────────────────────────────────────┐         │
│          │          Domain Services Layer             │         │
│          │  AuthService | UserService | SessionService │         │
│          │  PasswordService | OTPService | JWTSevice  │         │
│          └────────────────────────────────────────────┘         │
│                              │                                 │
│                              ▼                                 │
│          ┌────────────────────────────────────────────┐         │
│          │              API Handlers Layer              │         │
│          │  AuthHandler | UserHandler | SessionHandler  │         │
│          │  OTPHandler | HealthHandler                 │         │
│          └────────────────────────────────────────────┘         │
│                              │                                 │
└──────────────────────────────┼──────────────────────────────────┘
                               │
┌──────────────────────────────┼──────────────────────────────────┐
│                       PHASE 3: ADVANCED                          │
├──────────────────────────────┼──────────────────────────────────┤
│                              ▼                                  │
│          ┌────────────────────────────────────────────┐         │
│          │           Event Publishing Layer            │         │
│          │  Kafka Producer | Redis Pub/Sub Publisher   │         │
│          │  Admin Event Consumer                        │         │
│          └────────────────────────────────────────────┘         │
│                              │                                 │
│                              ▼                                  │
│          ┌────────────────────────────────────────────┐         │
│          │            Audit & Admin Layer              │         │
│          │  AuditService | AdminHandler                │         │
│          │  JWKS Management | Admin Event Handler      │         │
│          └────────────────────────────────────────────┘         │
│                              │                                 │
└──────────────────────────────┼──────────────────────────────────┘
                               │
┌──────────────────────────────┼──────────────────────────────────┐
│                    PHASE 4: WORKERS & OPS                        │
├──────────────────────────────┼──────────────────────────────────┤
│                              ▼                                  │
│          ┌────────────────────────────────────────────┐         │
│          │            Background Workers                │         │
│          │  TokenCleanup | KeyRotation | KeyRetirement │         │
│          │  AuditCleanup | AutoUnlock | OutboxCleanup │         │
│          └────────────────────────────────────────────┘         │
│                              │                                 │
│                              ▼                                  │
│          ┌────────────────────────────────────────────┐         │
│          │         Testing & Deployment               │         │
│          │  Unit Tests | Integration Tests | Load     │         │
│          │  Docker | Health Checks | GracefulShutdown │         │
│          └────────────────────────────────────────────┘         │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

---

## 5. Implementation Order

### Phase 1: Foundation (Week 1-2)

| Week | Task | Deliverables |
|------|------|-------------|
| 1 | Module 0: Project Setup | Go module, dependencies, config structure |
| 1 | Module 1: Database Layer | Schema, migrations, repository implementations |
| 2 | Module 2: Redis Cache Layer | Redis client, caching strategies, rate limiting |

### Phase 2: Core Features (Week 3-5)

| Week | Task | Deliverables |
|------|------|-------------|
| 3 | Module 3: User Management | Registration, email verification, OTP |
| 3-4 | Module 4: Authentication | Login, logout, brute-force protection |
| 4 | Module 5: Token Management | JWT, refresh tokens, grace period 10s |
| 5 | Module 6: Session Management | View/revoke sessions |

### Phase 3: Advanced Features (Week 6-8)

| Week | Task | Deliverables |
|------|------|-------------|
| 6 | Module 7: Password Management | Change/forgot/reset password |
| 6-7 | Module 8: Audit Logging | Audit events, admin API |
| 7 | Module 9: JWKS Management | JWKS endpoint, key rotation |
| 8 | Module 10: Event Publishing | Kafka events, Redis Pub/Sub |

### Phase 4: Workers & Operations (Week 9-10)

| Week | Task | Deliverables |
|------|------|-------------|
| 9 | Module 11: Background Workers | All scheduled workers |
| 10 | Module 12: Testing | Unit, integration, load tests |
| 10 | Module 13: Deployment | Docker, health checks, documentation |

---

## 6. Technology Stack

### Core Dependencies

| Category | Library | Version | Purpose |
|----------|---------|---------|---------|
| **HTTP Framework** | `gin-gonic/gin` | v1.10+ | HTTP routing, middleware |
| **Database** | `jackc/pgx/v5` | v5+ | PostgreSQL driver (performance) |
| **ORM Alternative** | `go-gorm/gorm` | v2+ | Optional ORM for convenience |
| **Redis** | `redis/go-redis/v9` | v9+ | Redis client |
| **Kafka** | `IBM/sarama` | v1.43+ | Kafka producer/consumer |
| **JWT** | `golang-jwt/jwt/v5` | v5+ | JWT generation/verification |
| **Password Hashing** | `go-modified/argon2` | latest | Argon2id password hashing |
| **UUID** | `google/uuid` | v1+ | UUID generation |
| **Config** | `spf13/viper` | v1.18+ | Configuration management |
| **Logging** | `uber-go/zap` | v1.26+ | Structured logging |
| **Validation** | `go-playground/validator/v10` | v10+ | Request validation |
| **DI** | `google/wire` | v0.6+ | Dependency injection (optional) |
| **Graceful Shutdown** | `tylerb/graceful` | latest | HTTP graceful shutdown |

### Development Tools

| Tool | Purpose |
|------|---------|
| `golangci-lint` | Linting, code analysis |
| `mockery` | Mock generation for testing |
| `sqlc` | SQL to Go code generation |
| `migrate` | Database migration tool |
| `reflex` | Hot reload for development |
| `wrk` / `k6` | Load testing |

---

## 7. Naming Conventions

### Package Naming

```
internal/
├── api/handler/         # HTTP handlers (ONE file per entity)
├── api/middleware/       # HTTP middleware
├── api/router/          # Router definitions
├── api/dto/             # Data transfer objects
├── domain/entity/       # Domain entities
├── domain/service/      # Domain services
├── domain/port/         # Interface definitions
├── domain/vo/          # Value objects
├── domain/event/        # Domain events
├── domain/errors/       # Domain errors
├── infrastructure/postgres/  # PostgreSQL repositories
├── infrastructure/redis/     # Redis implementations
├── infrastructure/kafka/     # Kafka producers/consumers
├── infrastructure/pubsub/    # Redis Pub/Sub
├── workers/             # Background workers
└── common/              # Shared utilities
```

### File Naming

| Type | Convention | Example |
|------|------------|---------|
| Handler | `entity_handler.go` | `auth_handler.go`, `user_handler.go` |
| Service | `entity_service.go` | `auth_service.go`, `password_service.go` |
| Repository | `entity_repo.go` | `user_repo.go`, `refresh_token_repo.go` |
| Entity | `entity.go` | `user.go`, `refresh_token.go` |
| DTO Request | `entity_request.go` | `login_request.go` |
| DTO Response | `entity_response.go` | `token_response.go` |
| Test | `entity_test.go` | `auth_service_test.go` |
| Migration | `NNN_description.up.sql` | `001_initial_schema.up.sql` |

### Interface Naming

| Pattern | Suffix | Example |
|---------|--------|---------|
| Repository | `Repository` | `UserRepository`, `TokenRepository` |
| Cache | `Cache` | `TokenCache`, `OTPCache` |
| Publisher | `Publisher` | `EventPublisher`, `MessagePublisher` |
| Hasher | `Hasher` | `PasswordHasher` |

---

## Checklist

- [x] Module 0: Project Setup
- [ ] Module 1: Database Layer
- [ ] Module 2: Redis Cache Layer
- [ ] Module 3: User Management
- [ ] Module 4: Authentication
- [ ] Module 5: Token Management
- [ ] Module 6: Session Management
- [ ] Module 7: Password Management
- [ ] Module 8: Audit Logging
- [ ] Module 9: JWKS Management
- [ ] Module 10: Event Publishing
- [ ] Module 11: Background Workers
- [ ] Module 12: Testing
- [ ] Module 13: Deployment

---

**Next:** [Module 0: Project Setup](module-00-project-setup.md)
