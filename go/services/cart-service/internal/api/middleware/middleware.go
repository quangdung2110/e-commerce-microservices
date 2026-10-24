// Package middleware holds the Gin middleware chain: request identity,
// correlation, logging, panic recovery and per-user rate limiting.
package middleware

import (
	"context"
	obs "github.com/ecommerce/observability"
	"net/http"
	"runtime/debug"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"cart-service/internal/api/dto/response"
	"cart-service/internal/domain"
	"cart-service/internal/reqctx"
)

// Header and context keys.
const (
	HeaderRequestID      = "X-Request-Id"
	HeaderUserID         = "X-User-Id"
	HeaderIdempotencyKey = "Idempotency-Key"

	ctxRequestID = "request_id"
	ctxIdentity  = "cart_identity"
)

// Idempotency key length bounds from the OpenAPI contract.
const (
	minIdempotencyKeyLength = 16
	maxIdempotencyKeyLength = 128
)

// RequestID normalizes the request id and publishes it, with the inbound W3C trace context, on the
// request's context.Context so the Product client can pass them on (contracts ADR-002). A missing
// or malformed X-Request-Id is replaced, never rejected; Cart has no tracer, so it never invents a
// traceparent.
func RequestID() gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Request = obs.EnsureContext(c.Request)
		traceparent := obs.Traceparent(c.Request.Context())
		tracestate := reqctx.ValidTracestate(c.GetHeader(reqctx.HeaderTracestate), traceparent)
		requestID := reqctx.NormalizeRequestID(c.GetHeader(HeaderRequestID), traceparent)

		ctx := reqctx.WithRequestID(c.Request.Context(), requestID)
		ctx = reqctx.WithTrace(ctx, traceparent, tracestate)
		c.Request = c.Request.WithContext(ctx)

		c.Set(ctxRequestID, requestID)
		c.Header(HeaderRequestID, requestID)
		c.Next()
	}
}

// GetRequestID returns the correlation id of the current request.
func GetRequestID(c *gin.Context) string {
	if value, ok := c.Get(ctxRequestID); ok {
		if requestID, ok := value.(string); ok {
			return requestID
		}
	}
	return ""
}

// Identity derives the cart routing identity from the trusted header the API
// Gateway injects.
//
// The Gateway strips any client-supplied X-User-Id before authentication, so
// by the time a request reaches Cart this header is trustworthy. Cart derives
// the user token, the cart id and the bucket itself; it never accepts a
// cart_id from a client.
func Identity() gin.HandlerFunc {
	return func(c *gin.Context) {
		identity, err := domain.NewIdentity(c.GetHeader(HeaderUserID))
		if err != nil {
			AbortWithDomainError(c, domain.ErrUnauthenticated)
			return
		}
		c.Set(ctxIdentity, identity)
		c.Next()
	}
}

// GetIdentity returns the derived identity of the current request.
func GetIdentity(c *gin.Context) domain.Identity {
	if value, ok := c.Get(ctxIdentity); ok {
		if identity, ok := value.(domain.Identity); ok {
			return identity
		}
	}
	return domain.Identity{}
}

// RequireIdempotencyKey enforces the Idempotency-Key header on write routes.
func RequireIdempotencyKey() gin.HandlerFunc {
	return func(c *gin.Context) {
		key := c.GetHeader(HeaderIdempotencyKey)
		if len(key) < minIdempotencyKeyLength || len(key) > maxIdempotencyKeyLength {
			AbortWithProblem(c, response.NewProblem(
				http.StatusBadRequest,
				domain.CodeValidationFailed,
				"Idempotency-Key must be 16 to 128 characters",
				c.Request.URL.Path,
				GetRequestID(c),
			))
			return
		}
		c.Next()
	}
}

// GetIdempotencyKey returns the request's idempotency key.
func GetIdempotencyKey(c *gin.Context) string {
	return c.GetHeader(HeaderIdempotencyKey)
}

// Logging records one structured line per request.
//
// It logs the privacy-safe cart id rather than the user id, and never the item
// payload: cart contents are behavioural data (docs/cart/data.md §9).
func Logging(logger *zap.Logger) gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		c.Next()

		fields := []zap.Field{
			zap.String("method", c.Request.Method),
			zap.String("path", c.FullPath()),
			zap.Int("status", c.Writer.Status()),
			zap.Duration("latency", time.Since(start)),
			zap.String("request_id", GetRequestID(c)),
		}
		if traceID := reqctx.TraceID(reqctx.Traceparent(c.Request.Context())); traceID != "" {
			fields = append(fields, zap.String("trace_id", traceID))
		}
		if identity := GetIdentity(c); identity.CartID != "" {
			fields = append(fields, zap.String("cart_id", identity.CartID), zap.Int("bucket", identity.Bucket))
		}

		if c.Writer.Status() >= http.StatusInternalServerError {
			logger.Error("request failed", fields...)
			return
		}
		logger.Info("request completed", fields...)
	}
}

// Recovery turns a panic into a 500 problem document.
func Recovery(logger *zap.Logger) gin.HandlerFunc {
	return func(c *gin.Context) {
		defer func() {
			if recovered := recover(); recovered != nil {
				logger.Error("panic recovered",
					zap.Any("panic", recovered),
					zap.String("stack", string(debug.Stack())),
					zap.String("path", c.Request.URL.Path),
					zap.String("request_id", GetRequestID(c)),
				)
				AbortWithProblem(c, response.NewProblem(
					http.StatusInternalServerError,
					domain.CodeInternal,
					"the request could not be completed",
					c.Request.URL.Path,
					GetRequestID(c),
				))
			}
		}()
		c.Next()
	}
}

// defaultRetryAfterSeconds is the wait advertised on a retryable 503 when Cart has no better estimate
// (contracts ADR-005). A 429 sets its own value from the rate limiter's window.
const defaultRetryAfterSeconds = 2

// AbortWithProblem writes a problem document and stops the chain. Every retryable 503 carries
// Retry-After (contracts ADR-005); a 503 that is not retryable does not.
func AbortWithProblem(c *gin.Context, problem response.Problem) {
	c.Header("Content-Type", response.ProblemContentType)
	if problem.Status == http.StatusServiceUnavailable && problem.Retryable && c.Writer.Header().Get("Retry-After") == "" {
		c.Header("Retry-After", strconv.Itoa(defaultRetryAfterSeconds))
	}
	c.AbortWithStatusJSON(problem.Status, problem)
}

// AbortWithDomainError writes the problem document for a domain error.
func AbortWithDomainError(c *gin.Context, err *domain.Error) {
	AbortWithProblem(c, response.FromDomainError(err, c.Request.URL.Path, GetRequestID(c)))
}

// CallerDeadline bounds a read-only internal request by the time its caller announced in
// X-Request-Timeout-Ms (contracts ADR-003): once the caller has stopped waiting, Redis and Product
// calls made for the request are cancelled instead of finishing for nobody. A missing or malformed
// header means no deadline. Attach it only to routes that write nothing; an abandoned write could be
// left half done.
func CallerDeadline() gin.HandlerFunc {
	return func(c *gin.Context) {
		if timeout, ok := reqctx.TimeoutFromHeader(c.GetHeader(reqctx.HeaderTimeoutMs)); ok {
			ctx, cancel := context.WithTimeout(c.Request.Context(), timeout)
			defer cancel()
			c.Request = c.Request.WithContext(ctx)
		}
		c.Next()
	}
}
