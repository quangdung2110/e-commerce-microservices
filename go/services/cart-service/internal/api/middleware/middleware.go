// Package middleware holds the Gin middleware chain: request identity,
// correlation, logging, panic recovery and per-user rate limiting.
package middleware

import (
	"net/http"
	"runtime/debug"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"go.uber.org/zap"

	"cart-service/internal/api/dto/response"
	"cart-service/internal/domain"
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

// RequestID propagates or mints a correlation id for every request.
func RequestID() gin.HandlerFunc {
	return func(c *gin.Context) {
		requestID := c.GetHeader(HeaderRequestID)
		if len(requestID) < 8 || len(requestID) > 128 {
			requestID = uuid.NewString()
		}
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

// AbortWithProblem writes a problem document and stops the chain.
func AbortWithProblem(c *gin.Context, problem response.Problem) {
	c.Header("Content-Type", response.ProblemContentType)
	c.AbortWithStatusJSON(problem.Status, problem)
}

// AbortWithDomainError writes the problem document for a domain error.
func AbortWithDomainError(c *gin.Context, err *domain.Error) {
	AbortWithProblem(c, response.FromDomainError(err, c.Request.URL.Path, GetRequestID(c)))
}
