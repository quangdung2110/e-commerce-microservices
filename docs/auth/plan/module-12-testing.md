# Module 12: Testing

> **Module:** Testing  
> **File:** `module-12-testing.md`  
> **Phase:** 4 - Workers & Operations  
> **Estimated Time:** 2-3 days

---

## Table of Contents

1. [Overview](#1-overview)
2. [Unit Testing Strategy](#2-unit-testing-strategy)
3. [Integration Testing](#3-integration-testing)
4. [Load Testing](#4-load-testing)
5. [Test Coverage](#5-test-coverage)
6. [Checklist](#6-checklist)

---

## 1. Overview

Module này thiết lập chiến lược kiểm thử:
- Unit tests cho domain services
- Integration tests với testcontainers
- Load testing với k6
- CI/CD pipeline

### Testing Pyramid

```
                    ┌───────────────┐
                    │ Load Testing │
                    └───────┬───────┘
                  ┌─────────┴─────────┐
                  │  Integration     │
                  │     Tests        │
                  └───────┬───────────┘
              ┌─────────┴─────────┐
              │    Unit Tests      │
              └───────────────────┘
```

---

## 2. Unit Testing Strategy

### 2.1 Test Structure

```go
// test/unit/domain/service/auth_service_test.go
package service

import (
    "context"
    "testing"

    "github.com/stretchr/testify/assert"
    "github.com/stretchr/testify/mock"
    "github.com/stretchr/testify/require"
)

// Mock implementations for ports

type MockUserRepository struct {
    mock.Mock
}

func (m *MockUserRepository) Create(ctx context.Context, user *entity.User) error {
    args := m.Called(ctx, user)
    return args.Error(0)
}

func (m *MockUserRepository) GetByID(ctx context.Context, id uuid.UUID) (*entity.User, error) {
    args := m.Called(ctx, id)
    if args.Get(0) == nil {
        return nil, args.Error(1)
    }
    return args.Get(0).(*entity.User), args.Error(1)
}

func (m *MockUserRepository) GetByEmail(ctx context.Context, email string) (*entity.User, error) {
    args := m.Called(ctx, email)
    if args.Get(0) == nil {
        return nil, args.Error(1)
    }
    return args.Get(0).(*entity.User), args.Error(1)
}

func (m *MockUserRepository) Update(ctx context.Context, user *entity.User) error {
    args := m.Called(ctx, user)
    return args.Error(0)
}

func (m *MockUserRepository) UpdateStatus(ctx context.Context, id uuid.UUID, status entity.UserStatus) error {
    args := m.Called(ctx, id, status)
    return args.Error(0)
}

func (m *MockUserRepository) LockAccount(ctx context.Context, id uuid.UUID) error {
    args := m.Called(ctx, id)
    return args.Error(0)
}

func (m *MockUserRepository) UnlockAccount(ctx context.Context, id uuid.UUID) error {
    args := m.Called(ctx, id)
    return args.Error(0)
}

func (m *MockUserRepository) BanAccount(ctx context.Context, id uuid.UUID) error {
    args := m.Called(ctx, id)
    return args.Error(0)
}

func (m *MockUserRepository) GetLockedUsers(ctx context.Context, duration time.Duration, limit int) ([]*entity.User, error) {
    args := m.Called(ctx, duration, limit)
    return args.Get(0).([]*entity.User), args.Error(1)
}

func (m *MockUserRepository) Delete(ctx context.Context, id uuid.UUID) error {
    args := m.Called(ctx, id)
    return args.Error(0)
}

// Test examples
func TestAuthService_Login_Success(t *testing.T) {
    ctx := context.Background()

    // Setup
    mockUserRepo := new(MockUserRepository)
    service := NewAuthService(mockUserRepo, /* other mocks */)

    user := &entity.User{
        ID:           uuid.New(),
        Email:        "test@example.com",
        PasswordHash: "hashed_password",
        Status:       entity.StatusActive,
    }

    // Expectations
    mockUserRepo.On("GetByEmail", ctx, user.Email).Return(user, nil)

    // Execute
    resp, err := service.Login(ctx, &LoginRequest{
        Email:    user.Email,
        Password: "correct_password",
    })

    // Assert
    require.NoError(t, err)
    assert.NotNil(t, resp)
    assert.NotEmpty(t, resp.AccessToken)
}
```

### 2.2 Test Helpers

```go
// test/helper/test_helpers.go
package helper

import (
    "context"
    "crypto/rand"
    "encoding/hex"

    "github.com/google/uuid"

    "github.com/ecommerce/auth-service/internal/domain/entity"
)

// GenerateTestUser creates a test user entity
func GenerateTestUser() *entity.User {
    return &entity.User{
        ID:           uuid.New(),
        Email:        "test_" + randomString(8) + "@example.com",
        PasswordHash: "hashed_password",
        Roles:        []string{"USER"},
        Status:       entity.StatusActive,
    }
}

// GenerateTestRefreshToken creates a test refresh token entity
func GenerateTestRefreshToken(userID uuid.UUID) *entity.RefreshToken {
    return &entity.RefreshToken{
        ID:         uuid.New(),
        UserID:     userID,
        TokenHash:  randomString(64),
        FamilyID:   uuid.New(),
        DeviceID:   "test-device",
        DeviceInfo: "Test Browser",
        ExpiresAt:  time.Now().Add(30 * 24 * time.Hour),
    }
}

func randomString(length int) string {
    bytes := make([]byte, length)
    rand.Read(bytes)
    return hex.EncodeToString(bytes)
}
```

---

## 3. Integration Testing

### 3.1 Testcontainers Setup

```go
// test/integration/integration_test.go
package integration

import (
    "context"
    "testing"
    "time"

    "github.com/testcontainers/testcontainers-go"
    "github.com/testcontainers/testcontainers-go/modules/postgres"
    "github.com/testcontainers/testcontainers-go/modules/redis"
    "github.com/stretchr/testify/require"
)

type IntegrationTestSuite struct {
    ctx        context.Context
    cancel     context.CancelFunc
    pgContainer *postgres.Container
    redisContainer *redis.Container
    dbPool *pgxpool.Pool
    redisClient *redis.Client
}

func SetupIntegrationSuite(t *testing.T) *IntegrationTestSuite {
    ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)

    suite := &IntegrationTestSuite{
        ctx:    ctx,
        cancel: cancel,
    }

    // Start PostgreSQL
    pgContainer, err := postgres.Run(ctx,
        "postgres:16-alpine",
        postgres.WithDatabase("auth_test"),
        postgres.WithUsername("postgres"),
        postgres.WithPassword("postgres"),
    )
    require.NoError(t, err)
    suite.pgContainer = pgContainer

    // Start Redis
    redisContainer, err := redis.Run(ctx,
        "redis:7-alpine",
    )
    require.NoError(t, err)
    suite.redisContainer = redisContainer

    // Setup connection pools
    // ... (see Module 1 for connection setup)

    return suite
}

func (s *IntegrationTestSuite) TearDown(t *testing.T) {
    s.cancel()

    if s.pgContainer != nil {
        s.pgContainer.Terminate(s.ctx)
    }
    if s.redisContainer != nil {
        s.redisContainer.Terminate(s.ctx)
    }
    if s.dbPool != nil {
        s.dbPool.Close()
    }
    if s.redisClient != nil {
        s.redisClient.Close()
    }
}

func TestIntegration_AuthService(t *testing.T) {
    suite := SetupIntegrationSuite(t)
    defer suite.TearDown(t)

    // Run integration tests
    t.Run("Login Flow", func(t *testing.T) {
        testLoginFlow(t, suite)
    })

    t.Run("Token Refresh Flow", func(t *testing.T) {
        testTokenRefreshFlow(t, suite)
    })
}
```

### 3.2 Database Migration for Tests

```go
// test/integration/migrations.go
package integration

func RunMigrations(t *testing.T, pool *pgxpool.Pool) {
    migration := `
    CREATE TABLE IF NOT EXISTS users (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        email VARCHAR(255) NOT NULL UNIQUE,
        password_hash VARCHAR(255) NOT NULL,
        roles JSONB NOT NULL DEFAULT '["USER"]',
        status VARCHAR(50) NOT NULL DEFAULT 'PENDING_VERIFICATION',
        locked_at TIMESTAMP WITH TIME ZONE,
        failed_login_attempts INTEGER NOT NULL DEFAULT 0,
        email_verified_at TIMESTAMP WITH TIME ZONE,
        created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
        updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
    );

    CREATE TABLE IF NOT EXISTS refresh_tokens (
        id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
        user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
        token_hash VARCHAR(255) NOT NULL UNIQUE,
        family_id UUID NOT NULL DEFAULT gen_random_uuid(),
        replaced_by UUID,
        device_id VARCHAR(255) NOT NULL,
        device_info VARCHAR(255),
        expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
        revoked_at TIMESTAMP WITH TIME ZONE,
        created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
    );

    -- Add more tables as needed
    `

    _, err := pool.Exec(context.Background(), migration)
    require.NoError(t, err)
}
```

---

## 4. Load Testing

### 4.1 k6 Load Test Configuration

```javascript
// test/load/auth_service.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

// Custom metrics
const loginDuration = new Trend('login_duration');
const refreshDuration = new Trend('refresh_duration');
const errorRate = new Rate('errors');

export const options = {
    stages: [
        { duration: '30s', target: 100 },  // Ramp up
        { duration: '1m', target: 100 },   // Steady state
        { duration: '30s', target: 200 },  // Stress
        { duration: '1m', target: 200 },   // Steady state
        { duration: '30s', target: 0 },   // Ramp down
    ],
    thresholds: {
        'http_req_duration': ['p(95)<500'],  // 95% under 500ms
        'errors': ['rate<0.01'],             // Error rate under 1%
    },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export default function () {
    const email = `loadtest_${__VU}_${__ITER}@example.com`;
    const password = 'LoadTest123';

    // Test registration
    const registerRes = http.post(`${BASE_URL}/api/v1/auth/public/register`, 
        JSON.stringify({
            email: email,
            password: password,
        }),
        {
            headers: { 'Content-Type': 'application/json' },
        }
    );

    check(registerRes, {
        'registration status 201 or 409': (r) => r.status === 201 || r.status === 409,
    });

    // Test login
    const loginStart = Date.now();
    const loginRes = http.post(`${BASE_URL}/api/v1/auth/public/login`,
        JSON.stringify({
            email: email,
            password: password,
        }),
        {
            headers: { 'Content-Type': 'application/json' },
        }
    );
    loginDuration.add(Date.now() - loginStart);

    const loginSuccess = check(loginRes, {
        'login status 200': (r) => r.status === 200,
        'has access token': (r) => r.json('access_token') !== undefined,
        'has refresh token': (r) => r.json('refresh_token') !== undefined,
    });

    if (!loginSuccess) {
        errorRate.add(1);
        return;
    }

    const accessToken = loginRes.json('access_token');
    const refreshToken = loginRes.json('refresh_token');

    // Test authenticated endpoint
    const profileRes = http.get(`${BASE_URL}/api/v1/auth/sessions`,
        {
            headers: {
                'Authorization': `Bearer ${accessToken}`,
            },
        }
    );

    check(profileRes, {
        'profile status 200': (r) => r.status === 200,
    });

    // Test token refresh
    sleep(1);
    
    const refreshStart = Date.now();
    const refreshRes = http.post(`${BASE_URL}/api/v1/auth/refresh`,
        null,
        {
            headers: {
                'Content-Type': 'application/json',
                'X-Refresh-Token': refreshToken,
            },
            cookies: {
                'refresh_token': refreshToken,
            },
        }
    );
    refreshDuration.add(Date.now() - refreshStart);

    check(refreshRes, {
        'refresh status 200': (r) => r.status === 200,
        'has new access token': (r) => r.json('access_token') !== undefined,
    });

    errorRate.add(refreshRes.status !== 200 ? 1 : 0);
}
```

### 4.2 Load Test with Artillery

```yaml
# test/load/artillery.yaml
config:
  target: "http://localhost:8080"
  phases:
    - duration: 60
      arrivalRate: 10
      name: "Warm up"
    - duration: 120
      arrivalRate: 50
      name: "Steady state"
    - duration: 60
      arrivalRate: 100
      name: "Stress test"
  plugins:
    expect: {}
  processor: "./artillery_functions.js"

scenarios:
  - name: "Auth Flow"
    flow:
      - post:
          url: "/api/v1/auth/public/register"
          json:
            email: "user{{ $randomString(8) }}@example.com"
            password: "TestPass123"
          capture:
            - json: "$.user_id"
              as: "userId"

      - post:
          url: "/api/v1/auth/public/login"
          json:
            email: "user@example.com"
            password: "TestPass123"
          capture:
            - json: "$.access_token"
              as: "accessToken"

      - get:
          url: "/api/v1/auth/sessions"
          headers:
            Authorization: "Bearer {{ accessToken }}"

      - post:
          url: "/api/v1/auth/refresh"
          headers:
            X-Refresh-Token: "{{ refreshToken }}"
```

---

## 5. Test Coverage

### 5.1 Coverage Targets

| Component | Coverage Target |
|-----------|---------------|
| Domain Services | 90% |
| API Handlers | 80% |
| Repositories | 85% |
| Infrastructure | 70% |
| Overall | 80% |

### 5.2 Coverage Report

```bash
# Run tests with coverage
go test -coverprofile=coverage.out ./...

# Generate HTML report
go tool cover -html=coverage.out -o coverage.html

# View summary
go tool cover -func=coverage.out
```

### 5.3 CI/CD Test Pipeline

```yaml
# .github/workflows/test.yml
name: Tests

on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main]

jobs:
  unit-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      
      - name: Set up Go
        uses: actions/setup-go@v5
        with:
          go-version: '1.22'
          
      - name: Cache modules
        uses: actions/cache@v3
        with:
          path: ~/go/pkg/mod
          key: ${{ runner.os }}-go-${{ hashFiles('**/go.sum') }}
          
      - name: Run unit tests
        run: |
          go test -v -race -coverprofile=coverage.out -covermode=atomic ./...
          
      - name: Upload coverage
        uses: codecov/codecov-action@v3
        with:
          file: coverage.out
          fail_ci_if_error: true
          
      - name: Run linter
        uses: golangci/golangci-lint-action@v3
        with:
          version: latest

  integration-tests:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:16-alpine
        env:
          POSTGRES_USER: postgres
          POSTGRES_PASSWORD: postgres
          POSTGRES_DB: auth_test
        options: >-
          --health-cmd pg_isready
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5
          
      redis:
        image: redis:7-alpine
        options: >-
          --health-cmd "redis-cli ping"
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5
          
    steps:
      - uses: actions/checkout@v4
      
      - name: Set up Go
        uses: actions/setup-go@v5
        
      - name: Run integration tests
        run: |
          go test -v -tags=integration ./test/integration/...
          
  load-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      
      - name: Install k6
        run: |
          sudo gpg -k
          sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
          echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" | sudo tee /etc/apt/sources.list.d/k6.list
          sudo apt-get update
          sudo apt-get install k6
          
      - name: Run k6 tests
        run: |
          k6 run test/load/auth_service.js
        env:
          BASE_URL: ${{ secrets.LOAD_TEST_URL }}
```

---

## 6. Checklist

### Unit Testing

- [ ] Create test directory structure
- [ ] Set up test helpers and mocks
- [ ] Write tests for UserService
- [ ] Write tests for AuthService
- [ ] Write tests for RefreshService
- [ ] Write tests for PasswordService
- [ ] Write tests for SessionService
- [ ] Write tests for JWTService
- [ ] Write tests for AuditService
- [ ] Achieve 80% coverage target

### Integration Testing

- [ ] Set up testcontainers for PostgreSQL
- [ ] Set up testcontainers for Redis
- [ ] Set up testcontainers for Kafka (optional)
- [ ] Create database migration helpers
- [ ] Write integration tests for Login Flow
- [ ] Write integration tests for Registration Flow
- [ ] Write integration tests for Token Refresh
- [ ] Write integration tests for Password Reset

### Load Testing

- [ ] Create k6 load test script
- [ ] Configure test scenarios
- [ ] Create Artillery configuration
- [ ] Document load test results
- [ ] Set performance thresholds

### CI/CD

- [ ] Create GitHub Actions workflow
- [ ] Configure unit test job
- [ ] Configure integration test job
- [ ] Configure load test job
- [ ] Add code coverage reporting
- [ ] Add lint checks

### Documentation

- [ ] Document testing strategy
- [ ] Document how to run tests
- [ ] Document load testing results
- [ ] Document performance benchmarks

---

**Previous:** [module-11-background-workers.md](module-11-background-workers.md)  
**Next:** [module-13-deployment.md](module-13-deployment.md)
