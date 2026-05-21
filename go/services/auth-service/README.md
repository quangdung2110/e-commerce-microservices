# Auth Service

Central Identity Provider for E-Commerce Microservices Platform.

## Features

- **User Registration & Authentication**
  - Email/password registration with OTP verification
  - Login with brute-force protection
  - Session management (view/revoke devices)

- **Token Management**
  - Dual Token Pattern (Access Token + Refresh Token)
  - 10-second Grace Period to prevent race conditions
  - JWKS endpoint for key rotation

- **Security**
  - Argon2id password hashing
  - Rate limiting
  - Account lockout after failed attempts
  - Audit logging

## Project Structure

```
auth-service/
├── cmd/server/           # Application entry point
├── internal/
│   ├── api/              # HTTP handlers, middleware, router
│   ├── domain/           # Business logic, entities, services
│   ├── infrastructure/   # Database, Redis, Kafka adapters
│   ├── workers/          # Background workers
│   └── common/           # Shared utilities
├── configs/              # Configuration files
├── test/                 # Unit and integration tests
├── Makefile
├── Dockerfile
└── docker-compose.yaml
```

## Quick Start

### Prerequisites

- Go 1.22+
- Docker & Docker Compose
- PostgreSQL 16+ (or use Docker)
- Redis 7+ (or use Docker)

### Development

1. **Start infrastructure with Docker:**

```bash
docker-compose up -d postgres redis kafka
```

2. **Configure environment:**

```bash
cp .env.example .env
# Edit .env with your settings
```

3. **Run the service:**

```bash
go run ./cmd/server -config ./configs/application.yaml
```

### Docker

Build and run with Docker Compose:

```bash
docker-compose up -d
```

## API Endpoints

| Method | Endpoint | Description | Auth |
|--------|----------|-------------|------|
| GET | /health | Health check | No |
| GET | /ready | Readiness check | No |
| GET | /live | Liveness check | No |
| POST | /api/v1/auth/public/register | Register user | No |
| POST | /api/v1/auth/public/login | Login | No |
| POST | /api/v1/auth/public/verify-email | Verify email | No |
| POST | /api/v1/auth/public/forgot-password | Forgot password | No |
| POST | /api/v1/auth/public/reset-password | Reset password | No |
| POST | /api/v1/auth/logout | Logout | Yes |
| POST | /api/v1/auth/refresh | Refresh token | Yes |
| POST | /api/v1/auth/change-password | Change password | Yes |
| GET | /api/v1/auth/sessions | List sessions | Yes |
| DELETE | /api/v1/auth/sessions/:id | Revoke session | Yes |
| GET | /.well-known/jwks.json | JWKS endpoint | No |

## Configuration

Configuration is managed via `configs/application.yaml` and environment variables.

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| DB_PASSWORD | PostgreSQL password | postgres |
| REDIS_PASSWORD | Redis password | redis |
| APP_ENV | Environment | development |

## Makefile Commands

```bash
make build      # Build binary
make run        # Run service
make test       # Run tests
make lint       # Run linter
make docker-build  # Build Docker image
make docker-run   # Run with Docker Compose
```

## License

MIT
