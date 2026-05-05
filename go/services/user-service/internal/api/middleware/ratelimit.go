package middleware

import (
	"net/http"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
)

// RateLimiter implements a simple token bucket rate limiter
type RateLimiter struct {
	mu       sync.Mutex
	buckets  map[string]*bucket
	rate     int           // requests per window
	window   time.Duration // time window
	capacity int           // max tokens
}

type bucket struct {
	tokens    int
	lastReset time.Time
}

// NewRateLimiter creates a new rate limiter
func NewRateLimiter(rate int, window time.Duration, capacity int) *RateLimiter {
	return &RateLimiter{
		buckets:  make(map[string]*bucket),
		rate:     rate,
		window:   window,
		capacity: capacity,
	}
}

// Allow checks if a request is allowed
func (rl *RateLimiter) Allow(key string) bool {
	rl.mu.Lock()
	defer rl.mu.Unlock()

	now := time.Now()
	b, exists := rl.buckets[key]

	if !exists || now.Sub(b.lastReset) >= rl.window {
		rl.buckets[key] = &bucket{
			tokens:    rl.capacity - 1,
			lastReset: now,
		}
		return true
	}

	if b.tokens > 0 {
		b.tokens--
		return true
	}

	return false
}

// RateLimitMiddleware creates a rate limiting middleware
func RateLimitMiddleware(limiter *RateLimiter) gin.HandlerFunc {
	return func(c *gin.Context) {
		key := c.ClientIP()

		if !limiter.Allow(key) {
			c.AbortWithStatusJSON(http.StatusTooManyRequests, gin.H{
				"error": gin.H{
					"code":    "RATE_LIMIT_EXCEEDED",
					"message": "Too many requests. Please try again later.",
				},
			})
			return
		}

		c.Next()
	}
}
