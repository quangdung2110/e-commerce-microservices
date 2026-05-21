# User Service

Microservice quản lý thông tin người dùng cho E-Commerce Platform, bao gồm profile, địa chỉ, và preferences.

## Features

- **User Profile Management**: Quản lý thông tin cá nhân của người dùng
- **Address Management**: Quản lý danh sách địa chỉ giao hàng với caching
- **User Preferences**: Quản lý preferences (email notifications, newsletter)
- **Event-Driven Architecture**: Tiếp nhận events từ Auth Service qua Kafka
- **GDPR Compliance**: Two-phase account deletion với 90-day retention
- **Outbox Pattern**: Reliable event publishing qua outbox table

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         User Service                              │
├─────────────────────────────────────────────────────────────────┤
│  HTTP API (Gin)                                                 │
│  ├── GET/PUT /api/v1/users/me/profile                           │
│  ├── GET/POST/PUT/DELETE /api/v1/users/me/addresses            │
│  └── GET/PUT /api/v1/users/me/preferences                       │
├─────────────────────────────────────────────────────────────────┤
│  Kafka Consumers                                                 │
│  ├── USER_VERIFIED → Creates user profile                       │
│  └── USER_DELETED → Initiates account deletion                 │
├─────────────────────────────────────────────────────────────────┤
│  Domain Services                                                 │
│  ├── ProfileService     - Profile business logic                │
│  ├── AddressService    - Address + caching logic               │
│  └── PreferencesService - User preferences logic               │
├─────────────────────────────────────────────────────────────────┤
│  Infrastructure                                                  │
│  ├── PostgreSQL - Primary data store                           │
│  ├── Redis      - Address caching                               │
│  └── Kafka      - Event consumption/publishing                  │
└─────────────────────────────────────────────────────────────────┘
```

## Tech Stack

- **Language**: Go 1.21+
- **Web Framework**: Gin
- **Database**: PostgreSQL 15 (via pgx)
- **Cache**: Redis 7
- **Message Broker**: Apache Kafka
- **Dependency Injection**: Uber FX
- **Logging**: Zap

## Project Structure

```
user-service/
├── cmd/
│   └── server/
│       ├── main.go              # Main HTTP server
│       └── compliance/
│           └── main.go          # GDPR compliance worker
├── internal/
│   ├── api/
│   │   ├── handler/             # HTTP handlers
│   │   ├── middleware/          # Auth, logging, rate limit
│   │   ├── router/              # Route definitions
│   │   ├── dto/                 # Request/Response DTOs
│   │   └── validator/           # Request validators
│   ├── domain/
│   │   ├── entity/              # Domain entities
│   │   ├── service/             # Business logic
│   │   ├── port/                # Interface definitions
│   │   ├── vo/                  # Value objects
│   │   └── event/               # Domain events
│   ├── infrastructure/
│   │   ├── postgres/            # PostgreSQL repositories
│   │   ├── redis/               # Redis cache
│   │   └── kafka/               # Kafka consumers/handlers
│   ├── workers/
│   │   ├── kafka_consumer/      # Kafka consumer worker
│   │   └── compliance/          # GDPR hard delete worker
│   └── common/                  # Shared utilities (if needed)
├── configs/
│   ├── application.yaml         # Main configuration
│   └── debezium/                # Debezium connector config
├── migrations/                   # SQL migrations
├── scripts/                      # Operational scripts
├── docker-compose.yaml           # Local development
├── Dockerfile                    # Container image
└── go.mod                        # Go module
```

## API Endpoints

### Health Checks

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| GET | `/health` | Liveness probe | No |
| GET | `/ready` | Readiness probe | No |

### User Profile

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| GET | `/api/v1/users/me/profile` | Get current user profile | Yes |
| PUT | `/api/v1/users/me/profile` | Update profile | Yes |

### Addresses

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| GET | `/api/v1/users/me/addresses` | List all addresses | Yes |
| POST | `/api/v1/users/me/addresses` | Create address | Yes |
| PUT | `/api/v1/users/me/addresses/:id` | Update address | Yes |
| DELETE | `/api/v1/users/me/addresses/:id` | Delete address | Yes |
| POST | `/api/v1/users/me/addresses/:id/default` | Set as default | Yes |

### User Preferences

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| GET | `/api/v1/users/me/preferences` | Get preferences | Yes |
| PUT | `/api/v1/users/me/preferences` | Update preferences | Yes |

## Configuration

Configuration được load từ `configs/application.yaml` và có thể override qua environment variables.

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `APP_PORT` | `8080` | HTTP server port |
| `DB_HOST` | `localhost` | PostgreSQL host |
| `DB_PORT` | `5432` | PostgreSQL port |
| `DB_USER` | `postgres` | PostgreSQL user |
| `DB_PASSWORD` | `postgres` | PostgreSQL password |
| `DB_NAME` | `user_service` | Database name |
| `DB_SSLMODE` | `disable` | SSL mode |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `KAFKA_BROKERS` | `localhost:9092` | Kafka brokers |
| `KAFKA_AUTH_TOPIC` | `t.auth.events` | Auth events topic |
| `KAFKA_USER_TOPIC` | `t.user.events` | User events topic |
| `KAFKA_CONSUMER_GROUP` | `user-service` | Consumer group |
| `JWT_SECRET` | - | JWT signing secret |
| `RATE_LIMIT_ENABLED` | `true` | Enable rate limiting |

## Local Development

### Prerequisites

- Go 1.21+
- Docker & Docker Compose
- PostgreSQL 15
- Redis 7
- Kafka

### Quick Start

```bash
# 1. Start infrastructure
docker-compose up -d postgres redis kafka

# 2. Run migrations
make migrate

# 3. Start the service
make run

# Or use docker-compose for full stack
docker-compose up -d
```

### Running Tests

```bash
# Run all tests
make test

# Run with coverage
make test-coverage

# Run specific package tests
go test ./internal/domain/...
```

### Building

```bash
# Build main server
make build

# Build compliance worker
make build-compliance

# Build Docker images
make docker-build
```

## Kafka Events

### Consumed Events

| Topic | Event | Action |
|-------|-------|--------|
| `t.auth.events` | `USER_VERIFIED` | Create user profile |
| `t.auth.events` | `USER_DELETED` | Anonymize user data |

### Published Events (via Outbox)

| Event | Trigger |
|-------|---------|
| `ADDRESS_CREATED` | New address created |
| `ADDRESS_UPDATED` | Address modified |
| `ADDRESS_DELETED` | Address removed |
| `ADDRESS_DEFAULT_CHANGED` | Default address changed |
| `PREFERENCES_UPDATED` | Preferences modified |

## GDPR Compliance

Service hỗ trợ GDPR compliance qua two-phase deletion:

1. **Soft Delete** (từ Auth Service): Thông tin cá nhân được anonymize
2. **Hard Delete** (sau 90 ngày): Compliance worker xóa vĩnh viễn dữ liệu

Xem `cmd/server/compliance/main.go` để biết chi tiết implementation.

## Database Schema

- `user_profiles` - User profile information
- `user_addresses` - Delivery addresses
- `user_preferences` - User preferences
- `outbox_events` - Outbox table for reliable events
- `user_deletions` - Deletion requests tracking

## Monitoring

Health check endpoints:
- `/health` - Returns 200 nếu service đang chạy
- `/ready` - Returns 200 nếu database và Redis đang kết nối

## License

Internal use only - E-Commerce Platform
