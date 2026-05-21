# Auth Service - Implementation Plan Index

> **Document Version:** 1.0  
> **Date:** May 18, 2026  
> **Status:** Complete Blueprint

---

## Module List

| # | Module | File | Phase | Time | Priority |
|---|-------|------|-------|-------|----------|
| 0 | Project Setup | `module-00-project-setup.md` | 1 - Foundation | 2-3 days | HIGH |
| 1 | Database Layer | `module-01-database-layer.md` | 1 - Foundation | 3-4 days | HIGH |
| 2 | Redis Cache Layer | `module-02-redis-cache-layer.md` | 1 - Foundation | 3-4 days | HIGH |
| 3 | User Management | `module-03-user-management.md` | 2 - Core | 3-4 days | HIGH |
| 4 | Authentication | `module-04-authentication.md` | 2 - Core | 3-4 days | HIGH |
| 5 | Token Management | `module-05-token-management.md` | 2 - Core | 4-5 days | HIGH |
| 6 | Session Management | `module-06-session-management.md` | 2 - Core | 2-3 days | MEDIUM |
| 7 | Password Management | `module-07-password-management.md` | 3 - Advanced | 2-3 days | MEDIUM |
| 8 | Audit Logging | `module-08-audit-logging.md` | 3 - Advanced | 2-3 days | MEDIUM |
| 9 | JWKS Management | `module-09-jwks-management.md` | 3 - Advanced | 3-4 days | MEDIUM |
| 10 | Event Publishing | `module-10-event-publishing.md` | 3 - Advanced | 3-4 days | MEDIUM |
| 11 | Background Workers | `module-11-background-workers.md` | 4 - Workers | 3-4 days | MEDIUM |
| 12 | Testing | `module-12-testing.md` | 4 - Workers | 2-3 days | HIGH |
| 13 | Deployment | `module-13-deployment.md` | 4 - Workers | 2-3 days | HIGH |

---

## Quick Reference

### File Structure

```
docs/auth/plan/
├── 00-overview-plan.md          # This overview
├── module-00-project-setup.md  # Project initialization
├── module-01-database-layer.md  # PostgreSQL schema & repos
├── module-02-redis-cache-layer.md # Redis caching & rate limiting
├── module-03-user-management.md # Registration & verification
├── module-04-authentication.md # Login & brute-force protection
├── module-05-token-management.md # JWT & refresh tokens
├── module-06-session-management.md # Session listing & revocation
├── module-07-password-management.md # Password change/reset
├── module-08-audit-logging.md # Audit trail & admin API
├── module-09-jwks-management.md # JWKS endpoint & key rotation
├── module-10-event-publishing.md # Kafka & Redis Pub/Sub
├── module-11-background-workers.md # Scheduled workers
├── module-12-testing.md # Unit & integration tests
└── module-13-deployment.md # Docker & Kubernetes
```

### Validation Architecture (CRITICAL)

> **Unified Validator Pattern**: All validation logic is centralized in `internal/common/validator/validator.go` as the Single Source of Truth.

```
┌─────────────────────────────────────────────────────────────────┐
│           internal/common/validator/validator.go                  │
│                     (SINGLE SOURCE OF TRUTH)                     │
├─────────────────────────────────────────────────────────────────┤
│  • IsValidEmail(email string) bool                              │
│  • ValidatePassword(password string) (bool, string)              │
│  • IsValidPassword(password string) bool                        │
└─────────────────────────────────────────────────────────────────┘
                              │
          ┌───────────────────┼───────────────────┐
          ▼                   ▼                   ▼
    ┌───────────┐     ┌───────────┐      ┌──────────────┐
    │ vo/email  │     │  service  │      │   DTOs      │
    │    .go    │     │    .go    │      │ (Gin binding)│
    └───────────┘     └───────────┘      └──────────────┘
```

**Validation Rules:**
- **Email**: Regex `^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$`
- **Password**: 8-128 chars, must contain uppercase, lowercase, and digit

### Dependencies Between Modules

```
Module 0 (Setup)
    │
    ├──▶ Module 1 (Database) ◀────────────┐
    │                                        │
    └──▶ Module 2 (Redis)                   │
                                              │
              ┌──────────────────────────────┤
              │                              │
              ▼                              ▼
    ┌─────────────────┐    ┌─────────────────────┐
    │ Module 3        │    │ Module 4            │
    │ (User Mgmt)     │◀──▶│ (Authentication)  │
    └────────┬────────┘    └──────────┬──────────┘
             │                        │
             │                        ▼
             │           ┌─────────────────────┐
             │           │ Module 5             │
             │           │ (Token Management) │
             │           └──────────┬──────────┘
             │                      │
             ▼                      ▼
    ┌─────────────────┐    ┌─────────────────────┐
    │ Module 6        │    │ Module 7             │
    │ (Session Mgmt)  │    │ (Password Mgmt)    │
    └─────────────────┘    └─────────────────────┘

Module 8 (Audit) ──────┬────── Module 9 (JWKS)
                       │
                       ▼
              ┌─────────────────────┐
              │ Module 10            │
              │ (Event Publishing)  │
              └──────────┬──────────┘
                         │
                         ▼
              ┌─────────────────────┐
              │ Module 11          │
              │ (Background Workers)│
              └──────────┬──────────┘
                         │
                         ▼
              ┌─────────────────────┐
              │ Module 12          │
              │ (Testing)          │
              └──────────┬──────────┘
                         │
                         ▼
              ┌─────────────────────┐
              │ Module 13          │
              │ (Deployment)      │
              └─────────────────────┘
```

---

## Implementation Timeline

### Phase 1: Foundation (Week 1-2)
- Module 0: Project Setup
- Module 1: Database Layer
- Module 2: Redis Cache Layer

### Phase 2: Core Features (Week 3-5)
- Module 3: User Management
- Module 4: Authentication
- Module 5: Token Management
- Module 6: Session Management

### Phase 3: Advanced Features (Week 6-8)
- Module 7: Password Management
- Module 8: Audit Logging
- Module 9: JWKS Management
- Module 10: Event Publishing

### Phase 4: Workers & Operations (Week 9-10)
- Module 11: Background Workers
- Module 12: Testing
- Module 13: Deployment

---

## Key Features Summary

| Feature | Module | Description |
|---------|--------|-------------|
| Registration | Module 3 | User signup with email verification |
| Login | Module 4 | Authentication with brute-force protection |
| Dual Token | Module 5 | Access + Refresh with Grace Period |
| Session Mgmt | Module 6 | View & revoke sessions |
| Password | Module 7 | Change, forgot, reset passwords |
| Audit | Module 8 | Security logging & admin API |
| JWKS | Module 9 | Key rotation every 30 days |
| Events | Module 10 | Kafka + Redis Pub/Sub |
| Workers | Module 11 | Cleanup & auto-unlock |

---

## Go Project Structure

```
auth-service/
├── cmd/server/main.go
├── internal/
│   ├── api/
│   │   ├── handler/
│   │   ├── middleware/
│   │   ├── router/
│   │   ├── dto/
│   │   └── validator/
│   ├── domain/
│   │   ├── entity/
│   │   ├── service/
│   │   ├── port/
│   │   ├── vo/
│   │   ├── event/
│   │   └── errors/
│   ├── infrastructure/
│   │   ├── postgres/
│   │   ├── redis/
│   │   ├── kafka/
│   │   └── pubsub/
│   ├── workers/
│   │   ├── token_cleanup/
│   │   ├── auto_unlock/
│   │   ├── audit_cleanup/
│   │   ├── key_rotation/
│   │   └── key_retirement/
│   └── common/
│       ├── constants/
│       ├── errors/
│       ├── keybuilder/
│       ├── jwt/
│       └── crypto/
├── configs/
├── test/
├── Makefile
├── Dockerfile
└── docker-compose.yaml
```

---

## Technology Stack

| Component | Library |
|-----------|---------|
| HTTP | `gin-gonic/gin` |
| Database | `jackc/pgx/v5` |
| Redis | `redis/go-redis/v9` |
| Kafka | `IBM/sarama` |
| JWT | `golang-jwt/jwt/v5` |
| Password | `go-modified/argon2` |
| Config | `spf13/viper` |
| Logging | `uber-go/zap` |

---

## Checklist

### Phase 1 - Foundation
- [ ] Module 0: Project Setup
- [ ] Module 1: Database Layer
- [ ] Module 2: Redis Cache Layer

### Phase 2 - Core Features
- [ ] Module 3: User Management
- [ ] Module 4: Authentication
- [ ] Module 5: Token Management
- [ ] Module 6: Session Management

### Phase 3 - Advanced Features
- [x] Module 7: Password Management
- [x] Module 8: Audit Logging
- [x] Module 9: JWKS Management
- [ ] Module 10: Event Publishing

### Phase 4 - Workers & Operations
- [ ] Module 11: Background Workers
- [ ] Module 12: Testing
- [ ] Module 13: Deployment

---

**Total Estimated Time:** 10 weeks (Full Implementation)

**Start with:** [00-overview-plan.md](00-overview-plan.md)
