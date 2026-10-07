package middleware

import (
	"time"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/common/logging"
)

// AccessLog records one line per request: method, route, status and timing
// only. It never logs a body, a header value or a query string, which on the
// status API can carry a subject (14-observability.md §6).
func AccessLog() gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		c.Next()

		route := c.FullPath()
		if route == "" {
			route = "unmatched"
		}
		fields := []zap.Field{
			zap.String("method", c.Request.Method),
			zap.String("route", route),
			zap.Int("status", c.Writer.Status()),
			zap.Int64("duration_ms", time.Since(start).Milliseconds()),
		}

		logger := logging.FromContext(c.Request.Context())
		switch {
		case c.Writer.Status() >= 500:
			logger.Error("request completed", fields...)
		case c.Writer.Status() >= 400:
			logger.Info("request rejected", fields...)
		default:
			logger.Info("request completed", fields...)
		}
	}
}
