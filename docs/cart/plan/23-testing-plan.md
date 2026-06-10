# Cart Service — Module 23: Testing Plan

**Document:** 23 | **Module:** Testing Strategy, Test Utilities, CI/CD  
**Status:** `[ ]` Chưa hoàn thành | `[x]` Đã hoàn thành  
**Phụ thuộc:** Tất cả modules 01-22

---

## 1. Mục tiêu Module

Định nghĩa chiến lược kiểm thử toàn diện cho Cart Service: unit tests, integration tests (với testcontainers), và load tests.

---

## 2. Testing Pyramid

```
                    ┌─────────────────┐
                    │   Load Tests    │  k6 / wrk
                    │   (E2E)        │
                    └────────┬────────┘
                             │
          ┌──────────────────┼──────────────────┐
          │    Integration Tests (Docker)        │
          │  testcontainers (Redis + PG + Kafka) │
          └────────┬──────────────────────────┬──┘
                   │                            │
    ┌──────────────┴────────────┐  ┌─────────┴───────────┐
    │   Unit Tests (in-process)  │  │  Unit Tests (Lua)    │
    │  Mock Redis/DB/GrpcClients │  │   miniredis          │
    └────────────────────────────┘  └─────────────────────┘
```

---

## 3. Chi tiết từng tầng Test

### 3.1. Unit Tests — Domain Layer

```go
// internal/domain/entity/cart_test.go
package entity

import "testing"

func TestCartStatus_Transition(t *testing.T) {
    cart := NewCart("{cart}:user1", "user1")

    // ACTIVE -> ABANDONED: allowed
    if !cart.CanTransitionTo(CartStatusAbandoned) {
        t.Error("ACTIVE should transition to ABANDONED")
    }

    // ACTIVE -> CHECKED_OUT: not allowed directly
    if cart.CanTransitionTo(CartStatusCheckedOut) {
        t.Error("ACTIVE should not transition directly to CHECKED_OUT")
    }

    // After ABANDONED -> can go back to ACTIVE (re-activation)
    cart.TransitionTo(CartStatusAbandoned, "1000-0")
    if !cart.CanTransitionTo(CartStatusActive) {
        t.Error("ABANDONED should be re-activatable")
    }
}

func TestStreamIDComparison(t *testing.T) {
    // Stream IDs are lexicographically comparable
    older := "1708530000123-0"
    newer := "1708616400456-0"

    if !streamIDNewer(older, newer) {
        t.Error("newer should be greater than older")
    }
    if streamIDNewer(newer, older) {
        t.Error("older should not be greater than newer")
    }
}

func TestCartItem_QuantityValidation(t *testing.T) {
    // quantity must be > 0
    _, err := NewCartItem("cart1", "prod1", "sku1", 0, 100.0, ProductSnapshot{})
    if err != ErrInvalidQuantity {
        t.Errorf("expected ErrInvalidQuantity, got %v", err)
    }

    // quantity > max is capped by UpdateQuantity
    item, _ := NewCartItem("cart1", "prod1", "sku1", 50, 100.0, ProductSnapshot{})
    err = item.UpdateQuantity(150)
    if err != ErrQuantityExceedsLimit {
        t.Errorf("expected ErrQuantityExceedsLimit, got %v", err)
    }
}
```

### 3.2. Unit Tests — Lua Scripts (miniredis)

```go
// internal/infrastructure/redis/scripts/add_or_update_test.go
package scripts

import (
    "context"
    "encoding/json"
    "testing"

    "github.com/alicebob/miniredis/v2"
    "github.com/redis/go-redis/v9"
)

func TestAddOrUpdateScript_NewItem(t *testing.T) {
    mr, rc := miniredis.Run(t)
    defer mr.Close()

    repo := NewCartLuaRepository(rc)

    itemJSON := buildItemJSON("sku1", 2, "1708530000")
    result, err := repo.AddOrUpdateItem(
        context.Background(),
        "{cart}:user1", "{cart}:events:0",
        "sku1", itemJSON,
        100, 50, // maxQty, maxSkus
        2,       // step
        1708530000000, // eventTimeMs
        "user1",
    )

    if err != nil {
        t.Fatalf("unexpected error: %v", err)
    }

    var resultItem map[string]interface{}
    json.Unmarshal([]byte(result), &resultItem)

    if resultItem["quantity"].(float64) != 2 {
        t.Errorf("expected quantity 2, got %v", resultItem["quantity"])
    }
}

func TestAddOrUpdateScript_RaceCondition(t *testing.T) {
    mr, rc := miniredis.Run(t)
    defer mr.Close()

    repo := NewCartLuaRepository(rc)
    cartKey := "{cart}:user1"
    shardKey := "{cart}:events:0"

    // Simulate 10 concurrent "add 1" requests
    done := make(chan bool, 10)
    for i := 0; i < 10; i++ {
        go func() {
            itemJSON := buildItemJSON("sku1", 1, "1708530000")
            repo.AddOrUpdateItem(
                context.Background(), cartKey, shardKey,
                "sku1", itemJSON,
                100, 50, 1, 1708530000000, "user1",
            )
            done <- true
        }()
    }

    for i := 0; i < 10; i++ {
        <-done
    }

    // Final quantity should be exactly 10 (not less due to race)
    val, _ := rc.HGet(context.Background(), cartKey, "sku1").Result()
    var item map[string]interface{}
    json.Unmarshal([]byte(val), &item)
    if item["quantity"].(float64) != 10 {
        t.Errorf("expected quantity 10 after race, got %v", item["quantity"])
    }
}

func TestAddOrUpdateScript_CartFull(t *testing.T) {
    mr, rc := miniredis.Run(t)
    defer mr.Close()

    repo := NewCartLuaRepository(rc)
    cartKey := "{cart}:user1"
    shardKey := "{cart}:events:0"

    // Fill cart to max (maxSkus = 2 for test)
    for i := 0; i < 2; i++ {
        itemJSON := buildItemJSON("sku"+string(rune('1'+i)), 1, "1708530000")
        _, err := repo.AddOrUpdateItem(
            context.Background(), cartKey, shardKey,
            "sku"+string(rune('1'+i)), itemJSON,
            100, 2, 1, 1708530000000, "user1",
        )
        if err != nil {
            t.Fatalf("setup failed: %v", err)
        }
    }

    // Try to add 3rd item — should fail with ErrCartFull
    itemJSON := buildItemJSON("sku3", 1, "1708530000")
    _, err := repo.AddOrUpdateItem(
        context.Background(), cartKey, shardKey,
        "sku3", itemJSON,
        100, 2, 1, 1708530000000, "user1",
    )

    if err != port.ErrCartFull {
        t.Errorf("expected ErrCartFull, got %v", err)
    }
}

func TestRehydrateScript_TimestampGuard(t *testing.T) {
    mr, rc := miniredis.Run(t)
    defer mr.Close()

    repo := NewCartLuaRepository(rc)
    cartKey := "{cart}:user1"

    // Pre-populate Redis with newer data
    newerItem := buildItemJSON("sku1", 5, "1708616400")
    rc.HSet(context.Background(), cartKey, "sku1", newerItem)

    // Try to rehydrate with older data — should NOT overwrite
    olderItem := `{"sku_id":"sku1","quantity":3,"updated_at":"1708530000"}`
    olderArr := `[{"sku_id":"sku1","value":` + olderItem + `}]`

    repo.Rehydrate(context.Background(), cartKey, olderArr, 604800)

    val, _ := rc.HGet(context.Background(), cartKey, "sku1").Result()
    var item map[string]interface{}
    json.Unmarshal([]byte(val), &item)

    // Should keep the newer data (quantity 5), not overwrite with older (quantity 3)
    if item["quantity"].(float64) != 5 {
        t.Errorf("expected newer quantity 5, got %v (should not overwrite)", item["quantity"])
    }
}

func buildItemJSON(skuID string, qty int, timestamp string) string {
    item := map[string]interface{}{
        "sku_id": skuID,
        "product_id": "prod-1",
        "quantity": qty,
        "unit_price_at_add": 150000.0,
        "product_name": "Test Product",
        "is_available": true,
        "added_at": timestamp,
        "updated_at": timestamp,
    }
    b, _ := json.Marshal(item)
    return string(b)
}
```

### 3.3. Integration Tests (testcontainers)

```go
// test/integration/cart_test.go
package integration

import (
    "context"
    "testing"
    "time"

    "github.com/testcontainers/testcontainers-go"
    "github.com/testcontainers/modules/kafka"
    "github.com/testcontainers/modules/postgres"
    "github.com/testcontainers/modules/redis"
    "cart-service/internal/infrastructure/config"
)

type IntegrationSuite struct {
    ctx context.Context
    t   *testing.T

    Redis    *redis.Container
    Postgres *postgres.Container
    Kafka    *kafka.Container

    CartLuaRepo port.CartLuaRepository
    CartRepo   port.CartRepository
    Publisher  port.KafkaPublisher
}

func TestMain(m *testing.M) {
    os.Exit(m.Run())
}

func SetupIntegrationSuite(t *testing.T) *IntegrationSuite {
    ctx := context.Background()

    suite := &IntegrationSuite{ctx: ctx, t: t}

    // Start Redis
    redisC, err := redis.Run(ctx, "redis:7-alpine",
        redis.WithLogLevel("warning"),
    )
    if err != nil {
        t.Fatalf("start redis: %v", err)
    }
    suite.Redis = redisC

    // Start PostgreSQL
    pgC, err := postgres.Run(ctx, "postgres:16-alpine",
        postgres.WithDatabase("cart_test"),
        postgres.WithUsername("postgres"),
        postgres.WithPassword("postgres"),
    )
    if err != nil {
        t.Fatalf("start postgres: %v", err)
    }
    suite.Postgres = pgC

    // Run migrations
    runMigrations(t, suite.Postgres)

    // Start Kafka
    kafkaC, err := kafka.Run(ctx, "confluentinc/cp-kafka:7.6.0")
    if err != nil {
        t.Fatalf("start kafka: %v", err)
    }
    suite.Kafka = kafkaC

    // Initialize clients
    suite.initializeClients(t)

    return suite
}

func (s *IntegrationSuite) Teardown() {
    if s.Redis != nil {
        s.Redis.Terminate(s.ctx)
    }
    if s.Postgres != nil {
        s.Postgres.Terminate(s.ctx)
    }
    if s.Kafka != nil {
        s.Kafka.Terminate(s.ctx)
    }
}

func TestAddItem_Integration(t *testing.T) {
    suite := SetupIntegrationSuite(t)
    defer suite.Teardown()

    // Test: Add item, verify in Redis
    itemJSON := buildTestItemJSON("sku-001", 2)
    result, err := suite.CartLuaRepo.AddOrUpdateItem(
        suite.ctx,
        "{cart}:user1", "{cart}:events:0",
        "sku-001", itemJSON,
        100, 50, 2, time.Now().UnixMilli(), "user1",
    )

    if err != nil {
        t.Fatalf("AddOrUpdateItem failed: %v", err)
    }

    // Verify Redis hash
    val, _ := suite.RedisClient.HGet(suite.ctx, "{cart}:user1", "sku-001").Result()
    if val == "" {
        t.Error("item not found in Redis")
    }

    // Verify event was published to Redis Stream
    streamLen, _ := suite.RedisClient.XLen(suite.ctx, "{cart}:events:0").Result()
    if streamLen == 0 {
        t.Error("event not found in stream")
    }

    _ = result // use result
}

func TestGetCart_CacheMiss_Integration(t *testing.T) {
    suite := SetupIntegrationSuite(t)
    defer suite.Teardown()

    // Seed PostgreSQL with cart data
    suite.seedCartData(t, "user1", []CartItemSeed{
        {SkuID: "sku-001", Quantity: 2, Price: 150000},
    })

    // Get cart — should trigger rehydrate
    cartView, err := suite.CartService.GetCart(suite.ctx, "user1")
    if err != nil {
        t.Fatalf("GetCart failed: %v", err)
    }

    if len(cartView.Items) != 1 {
        t.Errorf("expected 1 item, got %d", len(cartView.Items))
    }
}

func TestBatchDelete_Integration(t *testing.T) {
    suite := SetupIntegrationSuite(t)
    defer suite.Teardown()

    // Pre-populate cart
    for _, sku := range []string{"sku-001", "sku-002", "sku-003"} {
        itemJSON := buildTestItemJSON(sku, 1)
        suite.CartLuaRepo.AddOrUpdateItem(suite.ctx,
            "{cart}:user1", "{cart}:events:0",
            sku, itemJSON, 100, 50, 1, time.Now().UnixMilli(), "user1")
    }

    // Batch delete 2 items
    skuIDsJSON := `["sku-001","sku-002"]`
    count, err := suite.CartLuaRepo.BatchDelete(
        suite.ctx,
        "{cart}:user1", "{cart}:events:0",
        time.Now().UnixMilli(), "CART_CLEARED",
        "user1", skuIDsJSON,
    )

    if err != nil {
        t.Fatalf("BatchDelete failed: %v", err)
    }
    if count != 2 {
        t.Errorf("expected 2 deleted, got %d", count)
    }

    // Verify remaining item
    val, _ := suite.RedisClient.HGet(suite.ctx, "{cart}:user1", "sku-003").Result()
    if val == "" {
        t.Error("sku-003 should still exist")
    }
}
```

### 3.4. Load Tests (k6)

```javascript
// test/load/cart_load_test.js
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  scenarios: {
    // Normal traffic
    normal: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 100 },
        { duration: '1m', target: 100 },
        { duration: '30s', target: 0 },
      ],
    },
    // Flash sale spike
    flash_sale: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: 500,
      maxVUs: 2000,
      stages: [
        { duration: '10s', target: 100 },   // Ramp up
        { duration: '30s', target: 100 }, // Sustain
        { duration: '10s', target: 0 },   // Ramp down
      ],
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<50', 'p(99)<100'],  // 95th < 50ms, 99th < 100ms
    http_req_failed: ['rate<0.01'],                 // < 1% failures
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export default function () {
  const userID = `user-${Math.floor(Math.random() * 10000)}`;
  const headers = {
    'Content-Type': 'application/json',
    'X-User-Id': userID,
  };

  // Test: Get Cart
  const cartRes = http.get(`${BASE_URL}/api/v1/carts/mine`, { headers });
  check(cartRes, {
    'cart status 200': (r) => r.status === 200,
    'cart has items': (r) => JSON.parse(r.body).items !== undefined,
  });

  // Test: Add Item
  const addPayload = JSON.stringify({
    sku_id: `sku-${Math.floor(Math.random() * 1000)}`,
    quantity: Math.floor(Math.random() * 5) + 1,
  });
  const addRes = http.post(`${BASE_URL}/api/v1/carts/items`, addPayload, { headers });
  check(addRes, {
    'add item status 200': (r) => r.status === 200,
    'add item success': (r) => JSON.parse(r.body).success === true,
  });

  sleep(Math.random() * 2 + 0.5);
}
```

---

## 4. Test Utilities (`test/mocks/`)

```go
// test/mocks/cart_lua_repo_mock.go
package mocks

import "context"

type MockCartLuaRepo struct {
    AddOrUpdateItemFunc func(ctx context.Context, ...) (string, error)
    BatchDeleteFunc    func(ctx context.Context, ...) (int, error)
    RehydrateFunc     func(ctx context.Context, ...) error
}

func (m *MockCartLuaRepo) AddOrUpdateItem(ctx context.Context, ...) (string, error) {
    return m.AddOrUpdateItemFunc(ctx, ...)
}
```

---

## 5. CI/CD Pipeline

```yaml
# .github/workflows/ci.yml
name: CI

on:
  push:
    branches: [main, develop]
  pull_request:

jobs:
  test:
    runs-on: ubuntu-latest
    services:
      redis:
        image: redis:7-alpine
        ports: ["6379:6379"]
      postgres:
        image: postgres:16-alpine
        env:
          POSTGRES_PASSWORD: postgres
        ports: ["5432:5432"]

    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-go@v5
        with: { go-version: '1.22' }

      - name: Cache modules
        uses: actions/cache@v3
        with:
          path: ~/go/pkg/mod
          key: ${{ runner.os }}-go-${{ hashFiles('**/go.sum') }}

      - name: Unit tests
        run: go test -v -race -short ./...

      - name: Integration tests
        run: go test -v -race -tags=integration ./test/integration/...
        env:
          REDIS_ADDR: localhost:6379
          DB_HOST: localhost

      - name: Lint
        run: golangci-lint run ./...

  load-test:
    runs-on: ubuntu-latest
    needs: test
    steps:
      - uses: actions/checkout@v4
      - name: Run k6
        run: |
          docker compose up -d
          sleep 10
          k6 run test/load/cart_load_test.js
          docker compose down
```

---

## 6. File Locations

| File | Vị trí | Mô tả |
|------|---------|--------|
| `cart_test.go` | `test/mocks/cart_lua_repo_mock.go` | Mock implementations |
| `add_or_update_test.go` | `internal/infrastructure/redis/scripts/` | Lua unit tests |
| `rehydrate_test.go` | `internal/infrastructure/redis/scripts/` | Rehydrate unit tests |
| `cart_integration_test.go` | `test/integration/` | Integration tests |
| `cart_load_test.js` | `test/load/` | k6 load test |
| `ci.yml` | `.github/workflows/` | GitHub Actions CI |

---

## 7. Checklist triển khai Module 23

```markdown
## 23 - Testing Plan
- [ ] Tạo `test/mocks/` với mock implementations cho all ports
- [ ] Tạo unit tests: domain entities (status transitions, stream ID comparison)
- [ ] Tạo unit tests: Lua scripts với miniredis (quantity, capacity, timestamp guard)
- [ ] Tạo unit tests: domain services (AddItem, GetCart, BatchDelete)
- [ ] Tạo integration tests với testcontainers (Redis + PG + Kafka)
- [ ] Tạo load tests với k6 (normal traffic + flash sale scenario)
- [ ] Tạo GitHub Actions CI workflow
- [ ] Verify: `go test -short ./...` passes
- [ ] Verify: integration tests pass với docker-compose
- [ ] Verify: load test meets SLA (p95 < 50ms, p99 < 100ms)
```

---

*Tiếp theo: [24-deployment-plan.md](24-deployment-plan.md) — Docker, Kubernetes, and operations.*
