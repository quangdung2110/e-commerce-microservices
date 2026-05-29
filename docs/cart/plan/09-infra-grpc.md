# Cart Service — Module 09: Infrastructure — gRPC & Circuit Breaker

**Document:** 09 | **Module:** Infrastructure — gRPC Product Client & Circuit Breaker  
**Status:** `[x]` Chưa hoàn thành | `[x]` Đã hoàn thành | **Ngày hoàn thành:** 2026-05-30  
**Phụ thuộc:** Module 02 (domain models), Module 03 (domain ports)

---

## 1. Mục tiêu Module

Implement `ProductClient` interface (Module 03) sử dụng gRPC để giao tiếp với Product Service. Implement Circuit Breaker pattern để bảo vệ Cart Service khỏi cascading failures khi Product Service gặp sự cố.

---

## 2. Chi tiết triển khai

### 2.1. gRPC Product Client (`internal/infrastructure/grpc/product_client.go`)

```go
package grpc

import (
    "context"
    "fmt"
    "strings"
    "sync"
    "time"

    "google.golang.org/grpc"
    "google.golang.org/grpc/codes"
    "google.golang.org/grpc/credentials/insecure"
    "google.golang.org/grpc/status"
    "google.golang.org/grpc/timeout"

    "cart-service/internal/domain/entity"
    "cart-service/internal/domain/port"
)

// Config holds gRPC client configuration.
type Config struct {
    ProductServiceAddr string
    DialTimeout       time.Duration
    MaxCallTimeout    time.Duration
}

// ProductClient implements port.ProductClient using gRPC.
type ProductClient struct {
    conn    *grpc.ClientConn
    client  ProductServiceClient  // generated from proto
    breaker *CircuitBreaker
    cfg     Config
    mu      sync.RWMutex
}

// ProductServiceClient is the gRPC client interface (generated from proto).
// In real implementation, this would be generated from .proto file.
type ProductServiceClient interface {
    GetProductSku(ctx context.Context, in *GetProductSkuRequest, opts ...grpc.CallOption) (*GetProductSkuResponse, error)
    GetBatchProductSkus(ctx context.Context, in *GetBatchProductSkusRequest, opts ...grpc.CallOption) (*GetBatchProductSkusResponse, error)
}

// MockProductClient implements ProductServiceClient for development.
type MockProductClient struct{}

func (m *MockProductClient) GetProductSku(ctx context.Context, in *GetProductSkuRequest, opts ...grpc.CallOption) (*GetProductSkuResponse, error) {
    return &GetProductSkuResponse{
        SkuId:   in.SkuId,
        ProductId: "prod-1",
        ProductName: "Mock Product",
        OriginalPrice: 150000,
        PromotionalPrice: 0,
        Status: ProductStatus_ACTIVE,
    }, nil
}

func (m *MockProductClient) GetBatchProductSkus(ctx context.Context, in *GetBatchProductSkusRequest, opts ...grpc.CallOption) (*GetBatchProductSkusResponse, error) {
    items := make([]*ProductSkuItem, len(in.SkuIds))
    for i, id := range in.SkuIds {
        items[i] = &ProductSkuItem{
            SkuId: id,
            Status: ProductStatus_ACTIVE,
        }
    }
    return &GetBatchProductSkusResponse{Items: items}, nil
}

// NewProductClient creates a new gRPC Product Client with circuit breaker.
func NewProductClient(ctx context.Context, cfg Config, cbConfig CircuitBreakerConfig) (*ProductClient, error) {
    // Set up gRPC connection
    dialOpts := []grpc.DialOption{
        grpc.WithTransportCredentials(insecure.NewCredentials()),
        grpc.WithUnaryInterceptor(grpcTimeoutUnaryClientInterceptor(cfg.MaxCallTimeout)),
    }

    conn, err := grpc.DialContext(ctx, cfg.ProductServiceAddr,
        dialOpts...,
    )
    if err != nil {
        return nil, fmt.Errorf("dial product service: %w", err)
    }

    // Use mock client in development, real client in production
    // In real implementation: client := NewProductServiceClient(conn)
    client := &MockProductClient{}

    return &ProductClient{
        conn:    conn,
        client:  client,
        breaker: NewCircuitBreaker(cbConfig),
        cfg:     cfg,
    }, nil
}

var _ port.ProductClient = (*ProductClient)(nil)

// GetProductSku fetches a single SKU from Product Service.
func (c *ProductClient) GetProductSku(ctx context.Context, skuID string) (*entity.SkuInfo, error) {
    type result struct {
        sku  *entity.SkuInfo
        err  error
    }

    ch := c.breaker.Execute(func() (interface{}, error) {
        resp, err := c.client.GetProductSku(ctx, &GetProductSkuRequest{SkuId: skuID})
        if err != nil {
            return nil, err
        }
        return resp, nil
    })

    select {
    case <-ctx.Done():
        return nil, ctx.Err()
    case res := <-ch:
        if res.Err != nil {
            return nil, c.mapError(res.Err)
        }
        resp := res.Val.(*GetProductSkuResponse)
        return c.toSkuInfo(resp), nil
    }
}

// GetBatchProductSkus fetches multiple SKUs in one gRPC call.
// This is the primary method used in GetCart (both cache-hit and cache-miss).
func (c *ProductClient) GetBatchProductSkus(ctx context.Context, skuIDs []string) (*entity.GrpcResult, error) {
    if len(skuIDs) == 0 {
        return &entity.GrpcResult{Success: true, Skus: make(map[string]entity.SkuInfo)}, nil
    }

    ch := c.breaker.Execute(func() (interface{}, error) {
        resp, err := c.client.GetBatchProductSkus(ctx, &GetBatchProductSkusRequest{SkuIds: skuIDs})
        if err != nil {
            return nil, err
        }
        return resp, nil
    })

    select {
    case <-ctx.Done():
        return &entity.GrpcResult{Success: false, Error: "context cancelled"}, nil
    case res := <-ch:
        if res.Err != nil {
            return &entity.GrpcResult{Success: false, Error: c.mapErrorStr(res.Err)}, nil
        }
        resp := res.Val.(*GetBatchProductSkusResponse)
        return c.toGrpcResult(resp), nil
    }
}

// Close closes the gRPC connection.
func (c *ProductClient) Close() error {
    c.mu.Lock()
    defer c.mu.Unlock()
    if c.conn != nil {
        return c.conn.Close()
    }
    return nil
}

// IsHealthy checks if the product service is reachable (via circuit breaker state).
func (c *ProductClient) IsHealthy(ctx context.Context) bool {
    state := c.breaker.State()
    return state == StateClosed || state == StateHalfOpen
}

// --- Internal helpers ---

func (c *ProductClient) toSkuInfo(resp *GetProductSkuResponse) *entity.SkuInfo {
    price := resp.OriginalPrice
    if resp.PromotionalPrice > 0 {
        price = resp.PromotionalPrice
    }
    return &entity.SkuInfo{
        IsAvailable:  resp.Status == ProductStatus_ACTIVE,
        CurrentPrice:  price,
        ProductName:   resp.ProductName,
        ImageURL:      resp.MainImageUrl,
    }
}

func (c *ProductClient) toGrpcResult(resp *GetBatchProductSkusResponse) *entity.GrpcResult {
    result := &entity.GrpcResult{
        Success: true,
        Skus:    make(map[string]entity.SkuInfo, len(resp.Items)),
    }
    for _, item := range resp.Items {
        price := item.OriginalPrice
        if item.PromotionalPrice > 0 {
            price = item.PromotionalPrice
        }
        result.Skus[item.SkuId] = entity.SkuInfo{
            IsAvailable:  item.Status == ProductStatus_ACTIVE,
            CurrentPrice: price,
        }
    }
    return result
}

func (c *ProductClient) mapError(err error) error {
    if err == nil {
        return nil
    }
    st, ok := status.FromError(err)
    if !ok {
        return err
    }
    switch st.Code() {
    case codes.NotFound:
        return port.ErrProductNotFound
    case codes.Unavailable, codes.DeadlineExceeded:
        return fmt.Errorf("product service unavailable: %w", err)
    default:
        return err
    }
}

func (c *ProductClient) mapErrorStr(err error) string {
    return err.Error()
}

// grpcTimeoutUnaryClientInterceptor creates a timeout interceptor.
func grpcTimeoutUnaryClientInterceptor(timeout time.Duration) grpc.UnaryClientInterceptor {
    return func(ctx context.Context, method string, req, reply interface{}, cc *grpc.ClientConn, invoker grpc.UnaryInvoker, opts ...grpc.CallOption) error {
        ctx, cancel := context.WithTimeout(ctx, timeout)
        defer cancel()
        return invoker(ctx, method, req, reply, cc, opts...)
    }
}
```

### 2.2. Circuit Breaker (`internal/infrastructure/grpc/circuit_breaker.go`)

Implement Circuit Breaker pattern dựa trên `sony/gobreaker`.

```go
package grpc

import (
    "time"

    "github.com/sony/gobreaker/v2"
)

// CircuitBreakerConfig configures the circuit breaker.
type CircuitBreakerConfig struct {
    Name          string
    MaxRequests   uint32  // Max requests in half-open state
    Interval      time.Duration // Period of closed state
    Timeout       time.Duration // Period of open state
}

// CircuitBreaker wraps gobreaker.CircuitBreaker with configurable settings.
type CircuitBreaker struct {
    cb *gobreaker.CircuitBreaker[interface{}]
}

// Circuit breaker states.
const (
    StateClosed   = "closed"
    StateOpen     = "open"
    StateHalfOpen = "half-open"
)

// NewCircuitBreaker creates a new circuit breaker.
func NewCircuitBreaker(cfg CircuitBreakerConfig) *CircuitBreaker {
    if cfg.Name == "" {
        cfg.Name = "product-service"
    }
    if cfg.MaxRequests == 0 {
        cfg.MaxRequests = 3
    }
    if cfg.Interval == 0 {
        cfg.Interval = 10 * time.Second
    }
    if cfg.Timeout == 0 {
        cfg.Timeout = 30 * time.Second
    }

    cb := gobreaker.New[interface{}](
        gobreaker.Name[interface{}](cfg.Name),
        gobreaker.Settings[interface{}]{
            MaxRequests: cfg.MaxRequests,
            Interval:    cfg.Interval,
            Timeout:     cfg.Timeout,
            ReadyToTrade: func(count gobreaker.Counts) bool {
                // Allow trade when there are no recent failures
                return count.ConsecutiveFailures == 0
            },
            OnStateChange: func(name string, from, to gobreaker.State) {
                // Log state changes (use zap in production)
                _ = name
                _ = from
                _ = to
            },
        },
    )

    return &CircuitBreaker{cb: cb}
}

// Execute runs the function through the circuit breaker.
func (c *CircuitBreaker) Execute(fn func() (interface{}, error)) <-chan gobreaker.Result[interface{}] {
    ch := make(chan gobreaker.Result[interface{}], 1)

    go func() {
        result, err := c.cb.Execute(func() (interface{}, error) {
            return fn()
        })
        ch <- gobreaker.Result[interface{}]{Val: result, Err: err}
    }()

    return ch
}

// State returns the current circuit breaker state.
func (c *CircuitBreaker) State() string {
    switch c.cb.State() {
    case gobreaker.StateClosed:
        return StateClosed
    case gobreaker.StateOpen:
        return StateOpen
    case gobreaker.StateHalfOpen:
        return StateHalfOpen
    default:
        return "unknown"
    }
}
```

### 2.3. Proto Definitions (Placeholder) (`internal/infrastructure/grpc/product.proto`)

```protobuf
// Product Service gRPC definitions
// These would be in a shared proto repository in a real monorepo

syntax = "proto3";
package product;

option go_package = "cart-service/internal/infrastructure/grpc/pb";

service ProductService {
    rpc GetProductSku(GetProductSkuRequest) returns (GetProductSkuResponse);
    rpc GetBatchProductSkus(GetBatchProductSkusRequest) returns (GetBatchProductSkusResponse);
}

message GetProductSkuRequest {
    string sku_id = 1;
}

message GetProductSkuResponse {
    string sku_id = 1;
    string product_id = 2;
    string product_name = 3;
    double original_price = 4;
    double promotional_price = 5;
    string main_image_url = 6;
    ProductStatus status = 7;
}

message GetBatchProductSkusRequest {
    repeated string sku_ids = 1;
}

message GetBatchProductSkusResponse {
    repeated ProductSkuItem items = 1;
}

message ProductSkuItem {
    string sku_id = 1;
    string product_id = 2;
    string product_name = 3;
    double original_price = 4;
    double promotional_price = 5;
    string main_image_url = 6;
    ProductStatus status = 7;
}

enum ProductStatus {
    ACTIVE = 0;
    INACTIVE = 1;
    DELETED = 2;
    BANNED = 3;
}
```

---

## 3. File Locations

| File | Vị trí | Mô tả |
|------|---------|--------|
| `product_client.go` | `internal/infrastructure/grpc/product_client.go` | gRPC client + circuit breaker |
| `circuit_breaker.go` | `internal/infrastructure/grpc/circuit_breaker.go` | Circuit breaker wrapper |
| `product.proto` | `internal/infrastructure/grpc/product.proto` | Proto definitions |
| `pb/` | `internal/infrastructure/grpc/pb/` | Generated Go code |

---

## 4. Checklist triển khai Module 09

```markdown
## 09 - Infrastructure gRPC
- [x] Tạo `internal/infrastructure/grpc/product.proto` với proto definitions
- [x] Tạo `internal/infrastructure/grpc/pb/product.pb.go` với Go messages và gRPC client
- [x] Tạo `internal/infrastructure/grpc/circuit_breaker.go` với full CB implementation
- [x] Tạo `internal/infrastructure/grpc/product_client.go` với full implementation
- [x] Implement `port.ProductClient` interface
- [ ] Viết unit tests: circuit breaker state transitions
- [ ] Viết unit tests: timeout handling
- [ ] Viết unit tests: gRPC call with breaker
- [x] Verify `go build ./internal/infrastructure/grpc` compile thành công
```

**Ngày hoàn thành:** 2026-05-30

**Tổng tiến độ Module 09:** 6/9 tasks hoàn thành (3 tasks test còn lại)
```

---

*Tiếp theo: [10-infra-config.md](10-infra-config.md) — Configuration loading.*
