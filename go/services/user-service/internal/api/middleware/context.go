// Package middleware holds the transport concerns of the HTTP adapters:
// correlation, logging, panic recovery, trusted identity and rate limiting.
// None of it contains a business rule (docs/user/architecture.md §2).
package middleware

import (
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/ecommerce/user-service/internal/common/constants"
	"github.com/ecommerce/user-service/internal/common/logging"
	"github.com/ecommerce/user-service/internal/common/reqctx"
)

// Correlation puts a request id and the W3C trace context into the request
// context and echoes the request id back to the caller.
func Correlation(logger *zap.Logger) gin.HandlerFunc {
	return func(c *gin.Context) {
		requestID := c.GetHeader(constants.HeaderRequestID)
		if requestID == "" || len(requestID) > constants.MaxRequestIDLength {
			requestID = uuid.NewString()
		}
		traceparent := c.GetHeader(constants.HeaderTraceparent)

		ctx := reqctx.WithRequestID(c.Request.Context(), requestID)
		ctx = reqctx.WithTraceparent(ctx, traceparent)

		scoped := logger.With(
			zap.String("request_id", requestID),
			zap.String("operation", c.FullPath()),
		)
		if traceparent != "" {
			scoped = scoped.With(zap.String("traceparent", traceparent))
		}

		c.Request = c.Request.WithContext(logging.WithContext(ctx, scoped))
		c.Header(constants.HeaderRequestID, requestID)
		c.Next()
	}
}
