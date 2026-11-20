// Package middleware holds the transport concerns of the two HTTP listeners:
// correlation, access logging, panic recovery, the gateway identity of the
// public API, the service identity of the internal API, ops idempotency and
// the ops audit trail. No business rule lives here.
package middleware

import (
	obs "github.com/ecommerce/observability"
	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/common/constants"
	"github.com/ecommerce/notification-service/internal/common/logging"
	"github.com/ecommerce/notification-service/internal/common/reqctx"
)

const (
	minRequestIDLength = 8
	maxRequestIDLength = 128
	headerTraceParent  = "traceparent"
)

// RequestID accepts a caller-supplied X-Request-Id within the contract length
// (8..128) or generates one, stores it in the request context together with
// a scoped logger, and echoes it back.
func RequestID(logger *zap.Logger) gin.HandlerFunc {
	return func(c *gin.Context) {
		c.Request = obs.EnsureContext(c.Request)
		requestID := obs.NormalizeRequestID(c.GetHeader(constants.HeaderRequestID))
		traceParent := obs.Traceparent(c.Request.Context())

		ctx := reqctx.WithRequestID(c.Request.Context(), requestID)
		if traceParent != "" {
			ctx = reqctx.WithTraceParent(ctx, traceParent)
		}

		scoped := logger.With(
			zap.String("request_id", requestID),
			zap.String("route", c.FullPath()),
		)
		if traceParent != "" {
			scoped = scoped.With(zap.String("trace_id", obs.TraceID(c.Request.Context())))
		}

		c.Request = c.Request.WithContext(logging.WithContext(ctx, scoped))
		c.Header(constants.HeaderRequestID, requestID)
		c.Next()
	}
}
