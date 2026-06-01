# Cart Service — Module 13: API Layer — Middleware

**Document:** 13 | **Module:** API Layer — HTTP Middleware Stack  
**Status:** `[ ]` Chưa hoàn thành | `[x]` Đã hoàn thành  
**Phụ thuộc:** Module 10 (config), Module 12 (DTO error response)

---

## 1. Mục tiêu Module

Implement HTTP middleware chain cho Gin. Middleware xử lý cross-cutting concerns: authentication, logging, panic recovery, rate limiting, và distributed tracing.

---

## 2. Chi tiết triển khai

### 2.1. Middleware Stack (`internal/api/middleware/middleware.go`)

```go
package middleware

import (
    "context"

    "github.com/gin-gonic/gin"
    "go.uber.org/zap"
)

// MiddlewareFunc is the middleware function signature.
type MiddlewareFunc func(gin.HandlerFunc) gin.HandlerFunc

// Stack applies all middleware in order.
func Stack(logger *zap.Logger, cfg MiddlewareConfig) []gin.HandlerFunc {
    return []gin.HandlerFunc{
        // 1. Recovery (panic → 500)
        Recovery(logger),

        // 2. Request ID (unique per request)
        RequestID(),

        // 3. Logging
        Logging(logger),

        // 4. Tracing (OpenTelemetry)
        Tracing(),

        // 5. Rate limiting
        RateLimit(cfg.RateLimit),

        // 6. CORS
        CORS(),

        // 7. User ID extraction
        Auth(),
    }
}

// MiddlewareConfig holds middleware configuration.
type MiddlewareConfig struct {
    RateLimit RateLimitConfig
}

// RateLimitConfig holds rate limiting settings.
type RateLimitConfig struct {
    Enabled    bool
    MaxReqsPerMin int
}
```

### 2.2. Recovery Middleware (`internal/api/middleware/recovery.go`)

```go
package middleware

import (
    "fmt"
    "net/http"
    "runtime/debug"

    "github.com/gin-gonic/gin"
    "go.uber.org/zap"

    "cart-service/internal/api/dto/response"
)

// Recovery recovers from panics and returns 500.
func Recovery(logger *zap.Logger) gin.HandlerFunc {
    return func(c *gin.Context) {
        defer func() {
            if r := recover(); r != nil {
                logger.Error("panic recovered",
                    zap.Any("error", r),
                    zap.String("stack", string(debug.Stack())),
                    zap.String("path", c.Request.URL.Path),
                )

                c.AbortWithStatusJSON(http.StatusInternalServerError,
                    response.NewErrorResponse(
                        response.ErrInternal,
                        "Internal server error",
                        GetRequestID(c),
                    ),
                )
            }
        }()
        c.Next()
    }
}
```

### 2.3. Request ID Middleware (`internal/api/middleware/request_id.go`)

```go
package middleware

import (
    "github.com/gin-gonic/gin"
    "github.com/google/uuid"
)

const RequestIDHeader = "X-Request-ID"
const RequestIDKey = "request_id"

// RequestID generates or extracts a unique request ID.
func RequestID() gin.HandlerFunc {
    return func(c *gin.Context) {
        requestID := c.GetHeader(RequestIDHeader)
        if requestID == "" {
            requestID = uuid.New().String()
        }
        c.Set(RequestIDKey, requestID)
        c.Header(RequestIDHeader, requestID)
        c.Next()
    }
}

// GetRequestID retrieves the request ID from context.
func GetRequestID(c *gin.Context) string {
    if id, exists := c.Get(RequestIDKey); exists {
        return id.(string)
    }
    return ""
}
```

### 2.4. Logging Middleware (`internal/api/middleware/logging.go`)

```go
package middleware

import (
    "time"

    "github.com/gin-gonic/gin"
    "go.uber.org/zap"
)

// Logging logs HTTP requests with structured fields.
func Logging(logger *zap.Logger) gin.HandlerFunc {
    return func(c *gin.Context) {
        start := time.Now()
        path := c.Request.URL.Path
        query := c.Request.URL.RawQuery

        c.Next()

        latency := time.Since(start)
        status := c.Writer.Status()

        logger.Info("http request",
            zap.String("method", c.Request.Method),
            zap.String("path", path),
            zap.String("query", query),
            zap.Int("status", status),
            zap.Duration("latency", latency),
            zap.String("client_ip", c.ClientIP()),
            zap.String("request_id", GetRequestID(c)),
            zap.String("user_id", GetUserID(c)),
            zap.Int("body_size", c.Writer.Size()),
        )
    }
}
```

### 2.5. Auth Middleware (`internal/api/middleware/auth.go`)

```go
package middleware

import (
    "net/http"

    "github.com/gin-gonic/gin"

    "cart-service/internal/api/dto/response"
)

const UserIDHeader = "X-User-Id"
const UserIDKey = "user_id"

// Auth extracts user ID from header (injected by API Gateway).
// In production, this would validate JWT or session token.
func Auth() gin.HandlerFunc {
    return func(c *gin.Context) {
        userID := c.GetHeader(UserIDHeader)
        if userID == "" {
            c.AbortWithStatusJSON(http.StatusUnauthorized,
                response.NewErrorResponse(
                    response.ErrUnauthorized,
                    "Missing X-User-Id header",
                    GetRequestID(c),
                ),
            )
            return
        }
        c.Set(UserIDKey, userID)
        c.Next()
    }
}

// GetUserID retrieves the user ID from context.
func GetUserID(c *gin.Context) string {
    if uid, exists := c.Get(UserIDKey); exists {
        return uid.(string)
    }
    return ""
}
```

### 2.6. Rate Limiting Middleware (`internal/api/middleware/ratelimit.go`)

```go
package middleware

import (
    "net/http"
    "sync"
    "time"

    "github.com/gin-gonic/gin"
    "golang.org/x/time/rate"

    "cart-service/internal/api/dto/response"
)

// RateLimit implements per-user rate limiting using token bucket.
type RateLimiter struct {
    limiters map[string]*rate.Limiter
    mu       sync.RWMutex
    rps      rate.Limit
    burst    int
}

func NewRateLimiter(rps float64, burst int) *RateLimiter {
    return &RateLimiter{
        limiters: make(map[string]*rate.Limiter),
        rps:      rate.Limit(rps),
        burst:    burst,
    }
}

func (rl *RateLimiter) getLimiter(userID string) *rate.Limiter {
    rl.mu.RLock()
    lim, ok := rl.limiters[userID]
    rl.mu.RUnlock()
    if ok {
        return lim
    }

    rl.mu.Lock()
    defer rl.mu.Unlock()
    // Double-check after acquiring write lock
    if lim, ok := rl.limiters[userID]; ok {
        return lim
    }
    lim = rate.NewLimiter(rl.rps, rl.burst)
    rl.limiters[userID] = lim
    return lim
}

// Cleanup runs periodically to remove stale limiters.
func (rl *RateLimiter) Cleanup(interval time.Duration) {
    ticker := time.NewTicker(interval)
    go func() {
        for range ticker.C {
            rl.mu.Lock()
            rl.limiters = make(map[string]*rate.Limiter)
            rl.mu.Unlock()
        }
    }()
}

// RateLimit returns the rate limiting middleware.
func RateLimit(cfg RateLimitConfig) gin.HandlerFunc {
    if !cfg.Enabled {
        return func(c *gin.Context) { c.Next() }
    }

    limiter := NewRateLimiter(rate.Limit(cfg.MaxReqsPerMin)/60.0, cfg.MaxReqsPerMin/10)
    limiter.Cleanup(5 * time.Minute)

    return func(c *gin.Context) {
        userID := GetUserID(c)
        if userID == "" {
            userID = c.ClientIP()
        }

        if !limiter.getLimiter(userID).Allow() {
            c.AbortWithStatusJSON(http.StatusTooManyRequests,
                response.NewErrorResponse(
                    response.ErrorCode("RATE_LIMITED"),
                    "Too many requests",
                    GetRequestID(c),
                ),
            )
            return
        }
        c.Next()
    }
}
```

### 2.7. CORS Middleware (`internal/api/middleware/cors.go`)

```go
package middleware

import (
    "github.com/gin-gonic/gin"
)

// CORS configures Cross-Origin Resource Sharing.
func CORS() gin.HandlerFunc {
    return func(c *gin.Context) {
        c.Header("Access-Control-Allow-Origin", "*")
        c.Header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        c.Header("Access-Control-Allow-Headers", "Origin, Content-Type, Accept, Authorization, X-User-Id, X-Request-ID")
        c.Header("Access-Control-Expose-Headers", "X-Request-ID")
        c.Header("Access-Control-Max-Age", "86400")

        if c.Request.Method == "OPTIONS" {
            c.AbortWithStatus(204)
            return
        }

        c.Next()
    }
}
```

### 2.8. Tracing Middleware (`internal/api/middleware/tracing.go`)

```go
package middleware

import (
    "github.com/gin-gonic/gin"
    "go.opentelemetry.io/otel"
    "go.opentelemetry.io/otel/attribute"
    "go.opentelemetry.io/otel/propagation"
    "go.opentelemetry.io/otel/trace"
)

// Tracing middleware for OpenTelemetry distributed tracing.
func Tracing() gin.HandlerFunc {
    tracer := otel.Tracer("cart-service")
    propagator := otel.GetTextMapPropagator()

    return func(c *gin.Context) {
        // Extract trace context from incoming request headers
        ctx := propagator.Extract(c.Request.Context(), propagation.HeaderCarrier(c.Request.Header))

        // Start a new span
        ctx, span := tracer.Start(ctx, c.Request.Method+" "+c.FullPath(),
            trace.WithAttributes(
                attribute.String("http.method", c.Request.Method),
                attribute.String("http.url", c.Request.URL.String()),
                attribute.String("http.user_agent", c.Request.UserAgent()),
            ),
        )
        defer span.End()

        // Store span context in Gin context
        c.Request = c.Request.WithContext(ctx)

        c.Next()

        span.SetAttributes(
            attribute.Int("http.status_code", c.Writer.Status()),
            attribute.Int("http.response_size", c.Writer.Size()),
        )
    }
}
```

---

## 3. File Locations

| File | Vị trí | Mô tả |
|------|---------|--------|
| `middleware.go` | `internal/api/middleware/middleware.go` | Middleware stack |
| `recovery.go` | `internal/api/middleware/recovery.go` | Panic recovery |
| `request_id.go` | `internal/api/middleware/request_id.go` | Request ID |
| `logging.go` | `internal/api/middleware/logging.go` | Structured logging |
| `auth.go` | `internal/api/middleware/auth.go` | User ID extraction |
| `ratelimit.go` | `internal/api/middleware/ratelimit.go` | Rate limiting |
| `cors.go` | `internal/api/middleware/cors.go` | CORS |
| `tracing.go` | `internal/api/middleware/tracing.go` | OpenTelemetry tracing |

---

## 4. Checklist triển khai Module 13

```markdown
## 13 - API Middleware
- [ ] Tạo `internal/api/middleware/middleware.go` với stack definition
- [ ] Tạo `internal/api/middleware/recovery.go` với panic recovery + stack trace logging
- [ ] Tạo `internal/api/middleware/request_id.go` với UUID generation
- [ ] Tạo `internal/api/middleware/logging.go` với structured zap logging
- [ ] Tạo `internal/api/middleware/auth.go` với X-User-Id extraction
- [ ] Tạo `internal/api/middleware/ratelimit.go` với token bucket rate limiter
- [ ] Tạo `internal/api/middleware/cors.go`
- [ ] Tạo `internal/api/middleware/tracing.go` với OpenTelemetry
- [ ] Viết unit tests: rate limiter state
- [ ] Verify `go build ./internal/api/middleware` compile thành công
```

---

*Tiếp theo: [14-api-handlers.md](14-api-handlers.md) — HTTP handlers và router.*
