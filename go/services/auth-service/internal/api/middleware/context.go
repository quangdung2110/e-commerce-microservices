// Package middleware holds the transport concerns of the HTTP adapters:
// correlation, logging, panic recovery, trusted identity and rate limiting.
package middleware

import (
	obs "github.com/ecommerce/observability"
	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/common/constants"
	"github.com/ecommerce/auth-service/internal/common/logging"
	"github.com/ecommerce/auth-service/internal/common/reqctx"
)

// Correlation puts a request id and the W3C trace context into the request
// context and echoes the request id back to the caller.
func Correlation(logger *zap.Logger) gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Request = obs.EnsureContext(c.Request)
		requestID := obs.NormalizeRequestID(c.GetHeader(constants.HeaderRequestID))
		traceparent := obs.Traceparent(c.Request.Context())

		ctx := reqctx.WithRequestID(c.Request.Context(), requestID)
		ctx = reqctx.WithTraceparent(ctx, traceparent)

		scoped := logger.With(
			zap.String("request_id", requestID),
			zap.String("operation", c.FullPath()),
		)
		if traceparent != "" {
			scoped = scoped.With(zap.String("trace_id", obs.TraceID(c.Request.Context())))
		}

		c.Request = c.Request.WithContext(logging.WithContext(ctx, scoped))
		c.Header(constants.HeaderRequestID, requestID)
		c.Next()
	}
}
