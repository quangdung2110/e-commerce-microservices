package middleware

import (
	"time"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/api/httpx"
	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/common/logging"
)

// AccessLog records one line per request. It never logs a body, a header value,
// an email or a token: only the method, the route, the outcome and the timing.
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
			zap.Duration("duration", time.Since(start)),
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

// Recovery turns a panic into a problem response instead of a dropped
// connection, and logs the stack for the on-call engineer.
func Recovery() gin.HandlerFunc {
	return func(c *gin.Context) {
		defer func() {
			if recovered := recover(); recovered != nil {
				logging.FromContext(c.Request.Context()).Error("panic while handling a request",
					zap.Any("panic", recovered),
					zap.StackSkip("stack", 3),
				)
				httpx.WriteProblem(c, apperr.ErrInternal)
			}
		}()
		c.Next()
	}
}
