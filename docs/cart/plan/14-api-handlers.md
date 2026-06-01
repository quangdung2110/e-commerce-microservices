# Cart Service — Module 14: API Layer — Handlers & Router

**Document:** 14 | **Module:** API Layer — HTTP Handlers & Router  
**Status:** `[ ]` Chưa hoàn thành | `[x]` Đã hoàn thành  
**Phụ thuộc:** Module 11 (domain services), Module 12 (DTOs), Module 13 (middleware)

---

## 1. Mục tiêu Module

Implement HTTP handlers cho Cart API endpoints và router setup với Gin. Handlers nhận HTTP requests, gọi domain services, và trả về responses.

---

## 2. Chi tiết triển khai

### 2.1. Cart Handler (`internal/api/handler/cart_handler.go`)

```go
package handler

import (
    "net/http"

    "github.com/gin-gonic/gin"
    "go.uber.org/zap"

    "cart-service/internal/api/dto/request"
    "cart-service/internal/api/dto/response"
    "cart-service/internal/api/middleware"
    "cart-service/internal/domain/entity"
    "cart-service/internal/domain/service"
)

// CartHandler handles cart HTTP endpoints.
type CartHandler struct {
    cartService *service.CartService
    logger     *zap.Logger
}

// NewCartHandler creates a new cart handler.
func NewCartHandler(cartService *service.CartService, logger *zap.Logger) *CartHandler {
    return &CartHandler{
        cartService: cartService,
        logger:     logger,
    }
}

// AddItem handles POST /api/v1/carts/items
// Ref: cart_workflow.md §2
func (h *CartHandler) AddItem(c *gin.Context) {
    ctx := c.Request.Context()
    requestID := middleware.GetRequestID(c)
    userID := middleware.GetUserID(c)

    // Step 1: Parse request body
    var req request.AddItemRequest
    if err := c.ShouldBindJSON(&req); err != nil {
        h.logger.Warn("invalid request body",
            zap.String("request_id", requestID),
            zap.String("user_id", userID),
            zap.Error(err),
        )
        h.abortWithError(c, entity.ErrInvalidQuantity, requestID)
        return
    }

    // Step 2: Validate request
    if err := req.Validate(); err != nil {
        h.logger.Warn("validation failed",
            zap.String("request_id", requestID),
            zap.Error(err),
        )
        h.abortWithError(c, entity.ErrInvalidQuantity, requestID)
        return
    }

    // Step 3: Call domain service
    result, err := h.cartService.AddItem(ctx, userID, &service.AddItemRequest{
        SkuID:    req.SkuID,
        Quantity: req.Quantity,
    })
    if err != nil {
        h.logger.Error("AddItem failed",
            zap.String("request_id", requestID),
            zap.String("user_id", userID),
            zap.String("sku_id", req.SkuID),
            zap.Error(err),
        )
        h.abortWithError(c, err, requestID)
        return
    }

    // Step 4: Return response
    c.JSON(http.StatusOK, request.AddItemResponse{
        Success:   true,
        SkuID:     result.SkuID,
        Quantity:  result.Quantity,
        UpdatedAt: result.UpdatedAt,
        Message:   "Thêm vào giỏ hàng thành công",
    })
}

// GetCart handles GET /api/v1/carts/mine
// Ref: cart_workflow.md §3
func (h *CartHandler) GetCart(c *gin.Context) {
    ctx := c.Request.Context()
    requestID := middleware.GetRequestID(c)
    userID := middleware.GetUserID(c)

    // Call domain service
    cartView, err := h.cartService.GetCart(ctx, userID)
    if err != nil {
        h.logger.Error("GetCart failed",
            zap.String("request_id", requestID),
            zap.String("user_id", userID),
            zap.Error(err),
        )
        h.abortWithError(c, err, requestID)
        return
    }

    // Convert to response
    cartResp := response.FromCartView(cartView)
    c.JSON(http.StatusOK, cartResp)
}

// BatchDelete handles DELETE /api/v1/carts/items
// Ref: cart_workflow.md §4
func (h *CartHandler) BatchDelete(c *gin.Context) {
    ctx := c.Request.Context()
    requestID := middleware.GetRequestID(c)
    userID := middleware.GetUserID(c)

    // Step 1: Parse request body
    var req request.BatchDeleteRequest
    if err := c.ShouldBindJSON(&req); err != nil {
        h.logger.Warn("invalid request body",
            zap.String("request_id", requestID),
            zap.Error(err),
        )
        h.abortWithError(c, entity.ErrInvalidQuantity, requestID)
        return
    }

    // Step 2: Validate request
    if err := req.Validate(); err != nil {
        h.logger.Warn("validation failed",
            zap.String("request_id", requestID),
            zap.Error(err),
        )
        h.abortWithError(c, entity.ErrInvalidQuantity, requestID)
        return
    }

    // Step 3: Call domain service (default to CART_CLEARED for user-initiated delete)
    count, err := h.cartService.BatchDelete(ctx, userID, req.SkuIDs, "CART_CLEARED")
    if err != nil {
        h.logger.Error("BatchDelete failed",
            zap.String("request_id", requestID),
            zap.String("user_id", userID),
            zap.Error(err),
        )
        h.abortWithError(c, err, requestID)
        return
    }

    c.JSON(http.StatusOK, gin.H{
        "success":        true,
        "deleted_count": count,
        "message":        "Xóa sản phẩm thành công",
    })
}

// abortWithError converts domain errors to HTTP responses.
func (h *CartHandler) abortWithError(c *gin.Context, err error, requestID string) {
    errResp := response.FromDomainError(err, requestID)
    statusCode := response.HTTPStatusCode(errResp.Code)
    c.AbortWithStatusJSON(statusCode, errResp)
}
```

### 2.2. Health Handler (`internal/api/handler/health_handler.go`)

```go
package handler

import (
    "net/http"
    "time"

    "github.com/gin-gonic/gin"

    "cart-service/internal/domain/port"
)

// HealthHandler handles health check endpoints.
type HealthHandler struct {
    redisHealth port.HealthChecker
    pgHealth    port.HealthChecker
}

// NewHealthHandler creates a new health handler.
func NewHealthHandler(redisHealth, pgHealth port.HealthChecker) *HealthHandler {
    return &HealthHandler{
        redisHealth: redisHealth,
        pgHealth:    pgHealth,
    }
}

// HealthResponse represents the health check response.
type HealthResponse struct {
    Status    string            `json:"status"`
    Timestamp string            `json:"timestamp"`
    Checks    map[string]string `json:"checks"`
}

// Liveness handles GET /healthz
// Basic liveness check — is the process running?
func (h *HealthHandler) Liveness(c *gin.Context) {
    c.JSON(http.StatusOK, HealthResponse{
        Status:    "ok",
        Timestamp: time.Now().UTC().Format(time.RFC3339),
        Checks:    map[string]string{},
    })
}

// Readiness handles GET /readyz
// Readiness check — are dependencies available?
func (h *HealthHandler) Readiness(c *gin.Context) {
    checks := make(map[string]string)
    allHealthy := true

    // Check Redis
    if h.redisHealth != nil {
        if err := h.redisHealth.Ping(c.Request.Context()); err != nil {
            checks["redis"] = "unhealthy: " + err.Error()
            allHealthy = false
        } else {
            checks["redis"] = "ok"
        }
    }

    // Check PostgreSQL
    if h.pgHealth != nil {
        if err := h.pgHealth.Ping(c.Request.Context()); err != nil {
            checks["postgres"] = "unhealthy: " + err.Error()
            allHealthy = false
        } else {
            checks["postgres"] = "ok"
        }
    }

    resp := HealthResponse{
        Timestamp: time.Now().UTC().Format(time.RFC3339),
        Checks:    checks,
    }

    if allHealthy {
        resp.Status = "ok"
        c.JSON(http.StatusOK, resp)
    } else {
        resp.Status = "unhealthy"
        c.JSON(http.StatusServiceUnavailable, resp)
    }
}

// Metrics handles GET /metrics
// Stub for Prometheus metrics endpoint
func (h *HealthHandler) Metrics(c *gin.Context) {
    // In production, this would expose Prometheus metrics
    // using github.com/prometheus/client_golang
    c.JSON(http.StatusOK, gin.H{
        "message": "Metrics endpoint — integrate with prometheus/client_golang",
    })
}
```

### 2.3. Router Setup (`internal/api/router/router.go`)

```go
package router

import (
    "github.com/gin-gonic/gin"
    "go.uber.org/zap"

    "cart-service/internal/api/handler"
    "cart-service/internal/api/middleware"
    "cart-service/internal/domain/port"
    "cart-service/internal/infrastructure/config"
)

// Router holds all handlers and the Gin engine.
type Router struct {
    engine      *gin.Engine
    cartHandler *handler.CartHandler
    healthHandler *handler.HealthHandler
    logger     *zap.Logger
}

// NewRouter creates a new router with all routes configured.
func NewRouter(
    cartHandler *handler.CartHandler,
    healthHandler *handler.HealthHandler,
    logger *zap.Logger,
    redisHealth, pgHealth port.HealthChecker,
    cfg *config.Config,
) *Router {
    // Set Gin mode based on environment
    if cfg.App.Env == "production" {
        gin.SetMode(gin.ReleaseMode)
    }

    engine := gin.New()

    // Apply middleware stack
    mwCfg := middleware.MiddlewareConfig{
        RateLimit: middleware.RateLimitConfig{
            Enabled:     cfg.App.Env != "development",
            MaxReqsPerMin: 1000,
        },
    }
    engine.Use(middleware.Stack(logger, mwCfg)...)

    router := &Router{
        engine:       engine,
        cartHandler:   cartHandler,
        healthHandler: healthHandler,
        logger:       logger,
    }

    router.setupRoutes()

    return router
}

// setupRoutes configures all API routes.
func (r *Router) setupRoutes() {
    // Health check routes (no auth required)
    r.engine.GET("/healthz", r.healthHandler.Liveness)
    r.engine.GET("/readyz", r.healthHandler.Readiness)
    r.engine.GET("/metrics", r.healthHandler.Metrics)

    // API v1 routes
    v1 := r.engine.Group("/api/v1")
    {
        // Cart routes
        carts := v1.Group("/carts")
        {
            // Auth middleware required for all cart routes
            carts.Use(middleware.Auth())

            // GET /api/v1/carts/mine — get user's cart
            carts.GET("/mine", r.cartHandler.GetCart)

            // POST /api/v1/carts/items — add item to cart
            carts.POST("/items", r.cartHandler.AddItem)

            // DELETE /api/v1/carts/items — batch delete items
            carts.DELETE("/items", r.cartHandler.BatchDelete)
        }
    }
}

// Engine returns the Gin engine.
func (r *Router) Engine() *gin.Engine {
    return r.engine
}
```

---

## 3. API Endpoints Summary

| Method | Path | Handler | Description |
|---------|------|---------|-------------|
| GET | `/healthz` | HealthHandler.Liveness | Kubernetes liveness probe |
| GET | `/readyz` | HealthHandler.Readiness | Kubernetes readiness probe |
| GET | `/metrics` | HealthHandler.Metrics | Prometheus metrics |
| GET | `/api/v1/carts/mine` | CartHandler.GetCart | Get current user's cart |
| POST | `/api/v1/carts/items` | CartHandler.AddItem | Add item to cart |
| DELETE | `/api/v1/carts/items` | CartHandler.BatchDelete | Batch delete items |

---

## 4. File Locations

| File | Vị trí | Mô tả |
|------|---------|--------|
| `cart_handler.go` | `internal/api/handler/cart_handler.go` | Cart HTTP handlers |
| `health_handler.go` | `internal/api/handler/health_handler.go` | Health check handlers |
| `router.go` | `internal/api/router/router.go` | Gin router setup |

---

## 5. Checklist triển khai Module 14

```markdown
## 14 - API Handlers
- [ ] Tạo `internal/api/handler/cart_handler.go` với AddItem, GetCart, BatchDelete
- [ ] Tạo `internal/api/handler/health_handler.go` với liveness/readiness/metrics
- [ ] Tạo `internal/api/router/router.go` với đầy đủ route configuration
- [ ] Viết unit tests: handler with mock service
- [ ] Viết unit tests: health handler with mock health checkers
- [ ] Verify router registration with all 6 endpoints
- [ ] Verify `go build ./internal/api/...` compile thành công
```

---

*Tiếp theo: [15-21-workers.md](15-21-workers.md) — All 6 background workers.*
