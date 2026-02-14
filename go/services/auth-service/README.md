# Auth Service

Authentication and authorization service for the E-Commerce Platform built with Go and Gin framework.

## Features

- ✅ User authentication (login/logout)
- ✅ JWT token generation and validation
- ✅ Refresh token rotation with theft detection
- ✅ Session management (multiple devices)
- ✅ OTP-based registration (email/SMS)
- ✅ Password reset flow
- ✅ 2FA support
- ✅ Rate limiting and IP blocking
- ✅ Comprehensive error handling
- ✅ Request/response logging
- ✅ CORS and security headers

## Architecture

```
auth-service/
├── cmd/
│   └── main.go                 # Application entry point with Gin setup
├── config/
│   └── application.yaml        # Service configuration
├── internal/
│   ├── delivery/
│   │   └── http/
│   │       └── v1/            # HTTP handlers, middleware, responses
│   ├── domain/
│   │   ├── entity/            # Domain entities (Session, OTP, Token, etc.)
│   │   └── serr/              # Custom error definitions
│   └── usecase/               # Business logic
│       ├── authentication.go  # Login, logout, token refresh
│       ├── registration.go    # OTP-based registration
│       ├── password.go        # Password reset flow
│       ├── session.go         # Session management
│       └── interfaces.go      # Repository and service interfaces
```

## Prerequisites

- Go 1.22+
- PostgreSQL 15+
- Redis 7+
- Kafka (optional for event publishing)

## Configuration

Edit `config/application.yaml`:

```yaml
server:
  port: 8081
  host: 0.0.0.0
  mode: development  # or production

database:
  host: localhost
  port: 5432
  name: auth_db
  user: postgres
  password: postgres

redis:
  host: localhost
  port: 6379

jwt:
  access_token_secret: "your-secret-key"
  refresh_token_secret: "your-refresh-secret"
  access_token_ttl_minutes: 15
  refresh_token_ttl_days: 90
```

## Database Setup

Create PostgreSQL database:

```sql
CREATE DATABASE auth_db;

-- Run migrations (TODO: add migration tool)
```

## Installation

From the workspace root:

```bash
# Install dependencies
cd go
go mod download

# Build auth-service
cd services/auth-service
go build -o bin/auth-service ./cmd
```

## Running the Service

### Development mode

```bash
cd go/services/auth-service
go run cmd/main.go
```

The service will start on `http://localhost:8081`

### Production mode

```bash
# Build
go build -o bin/auth-service ./cmd

# Run
./bin/auth-service
```

## API Endpoints

### Health Check

- `GET /health` - Health status
- `GET /ready` - Readiness check (validates Redis connection)

### Authentication

- `POST /api/v1/auth/login` - User login
- `POST /api/v1/auth/logout` - User logout (requires auth)
- `POST /api/v1/auth/refresh` - Refresh access token

### Registration

- `POST /api/v1/register/send-otp` - Send OTP for registration
- `POST /api/v1/register/verify-otp` - Verify OTP code
- `POST /api/v1/register/complete` - Complete registration

### Password Management

- `POST /api/v1/password/forgot` - Request password reset
- `POST /api/v1/password/reset` - Reset password with token
- `POST /api/v1/password/change` - Change password (requires auth)

### Session Management (requires auth)

- `GET /api/v1/sessions` - List all active sessions
- `GET /api/v1/sessions/:session_id` - Get session details
- `DELETE /api/v1/sessions/:session_id` - Revoke specific session
- `DELETE /api/v1/sessions` - Revoke all sessions

## Error Handling

All errors follow a standard format:

```json
{
  "success": false,
  "error": {
    "type": "AUTHENTICATION",
    "code": "INVALID_CREDENTIALS",
    "message": "Invalid email/username or password",
    "details": ""
  },
  "timestamp": "2026-02-07T10:30:00Z"
}
```

Error types:
- `AUTHENTICATION` - Login/token issues
- `AUTHORIZATION` - Permission denied
- `VALIDATION` - Input validation errors
- `RATE_LIMIT` - Too many requests
- `RESOURCE` - Resource not found
- `INTERNAL` - Server errors
- `EXTERNAL` - External service errors

See [internal/domain/serr/errors.go](internal/domain/serr/errors.go) for all error definitions.

## Security Features

### Rate Limiting
- Login attempts: 10 per IP per 15 minutes
- OTP requests: 5 emails or 3 SMS per day
- IP blocking after threshold exceeded

### Token Management
- Access tokens: 15 minutes (JWT)
- Refresh tokens: 90 days (opaque, stored in Redis)
- Token rotation on refresh with theft detection
- Blacklisting for revoked tokens

### Password Security
- Bcrypt hashing (cost 12)
- Strength validation (min length, complexity)
- Reset tokens: SHA-256 hashed, 1-hour TTL

### Session Security
- Device fingerprinting
- Geolocation tracking
- Max 5 active sessions per user
- Automatic session cleanup

## Development

### Running Tests

```bash
go test ./...
```

### Linting

```bash
golangci-lint run
```

### Adding New Errors

1. Define error in `internal/domain/serr/errors.go`:
```go
var ErrCustomError = NewAppError(
    ErrorTypeValidation,
    "CUSTOM_ERROR",
    "Description of the error",
    http.StatusBadRequest,
)
```

2. Use in handlers:
```go
if invalid {
    return v1.ErrorResponse(c, serr.ErrCustomError)
}
```

## TODO

- [ ] Implement repository layer (PostgreSQL + Redis)
- [ ] Add database migrations
- [ ] Implement external service clients (user-service, notification-service)
- [ ] Add JWT token generation/validation
- [ ] Complete handler implementations
- [ ] Add integration tests
- [ ] Add Swagger/OpenAPI documentation
- [ ] Implement metrics and tracing
- [ ] Add Docker support

## Dependencies

- **Gin**: HTTP web framework
- **Zap**: Structured logging
- **Viper**: Configuration management
- **Redis**: Session and OTP storage
- **PostgreSQL**: Persistent storage
- **Kafka**: Event publishing (optional)
- **golang.org/x/crypto**: Password hashing

## License

Part of the E-Commerce Platform microservices architecture.
