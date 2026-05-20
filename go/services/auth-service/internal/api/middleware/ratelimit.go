package middleware

import (
	"fmt"
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/common/constants"
	"github.com/ecommerce/auth-service/internal/domain/port"
)

type RateLimiterMiddleware struct {
	limiter       port.RateLimiter
	defaultLimit  int
	defaultWindow time.Duration
	logger        *zap.Logger
}

func NewRateLimiterMiddleware(limiter port.RateLimiter, logger *zap.Logger) *RateLimiterMiddleware {
	return &RateLimiterMiddleware{
		limiter:       limiter,
		defaultLimit:  constants.DefaultRateLimitPerMinute,
		defaultWindow: 1 * time.Minute,
		logger:        logger.With(zap.String("middleware", "RateLimiter")),
	}
}

// RateLimit returns a rate limiting middleware with custom limits
func (m *RateLimiterMiddleware) RateLimit(action string, limit int, window time.Duration) gin.HandlerFunc {
	return func(c *gin.Context) {
		logger := m.logger.With(
			zap.String("action", action),
			zap.Int("limit", limit),
			zap.Duration("window", window),
		)

		identifier := c.ClientIP()
		if userID, exists := c.Get("user_id"); exists {
			identifier = userID.(string)
		}

		allowed, err := m.limiter.Allow(c.Request.Context(), action, identifier, limit, window)
		if err != nil {
			logger.Error("Rate limiter error",
				zap.String("identifier", identifier),
				zap.Error(err),
			)
			c.Next()
			return
		}

		remaining, _ := m.limiter.GetCount(c.Request.Context(), action, identifier)
		retryAfter := int(window.Seconds())

		c.Header("X-RateLimit-Limit", fmt.Sprintf("%d", limit))
		c.Header("X-RateLimit-Remaining", fmt.Sprintf("%d", max(0, limit-remaining)))
		c.Header("Retry-After", fmt.Sprintf("%d", retryAfter))

		if !allowed {
			logger.Warn("Rate limit exceeded",
				zap.String("identifier", identifier),
				zap.Int("retry_after", retryAfter),
			)

			c.AbortWithStatusJSON(http.StatusTooManyRequests, gin.H{
				"error": gin.H{
					"code":                "RATE_LIMIT_EXCEEDED",
					"message":             "Too many requests. Please try again later.",
					"retry_after_seconds": retryAfter,
				},
			})
			return
		}

		c.Next()
	}
}

// RateLimitByIP returns a rate limiting middleware for IP-based limiting
func (m *RateLimiterMiddleware) RateLimitByIP(limit int, window time.Duration) gin.HandlerFunc {
	return m.RateLimit("ip", limit, window)
}

// RateLimitByUser returns a rate limiting middleware for user-based limiting
func (m *RateLimiterMiddleware) RateLimitByUser(limit int, window time.Duration) gin.HandlerFunc {
	return func(c *gin.Context) {
		_, exists := c.Get("user_id")
		if !exists {
			c.Next()
			return
		}

		m.RateLimit("user", limit, window)(c)
	}
}

// LoginRateLimit applies strict rate limiting for login endpoint
func (m *RateLimiterMiddleware) LoginRateLimit() gin.HandlerFunc {
	return m.RateLimit("login", constants.LoginRateLimitPerMinute, 1*time.Minute)
}

// APIRateLimit applies general rate limiting for API endpoints
func (m *RateLimiterMiddleware) APIRateLimit() gin.HandlerFunc {
	return m.RateLimit("api", constants.DefaultRateLimitPerMinute, 1*time.Minute)
}
