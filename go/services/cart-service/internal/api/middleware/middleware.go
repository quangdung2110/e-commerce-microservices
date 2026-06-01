package middleware

import (
	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
)

// MiddlewareConfig holds middleware configuration.
type MiddlewareConfig struct {
	RateLimit RateLimitConfig
}

// RateLimitConfig holds rate limiting settings.
type RateLimitConfig struct {
	Enabled       bool
	MaxReqsPerMin int
}

// Stack applies all middleware in order.
func Stack(logger *zap.Logger, cfg MiddlewareConfig) []gin.HandlerFunc {
	return []gin.HandlerFunc{
		Recovery(logger),
		RequestID(),
		Logging(logger),
		Tracing(),
		RateLimit(cfg.RateLimit),
		CORS(),
	}
}
