package middleware

import (
	"net/http"
	"sync"
	"time"

	"github.com/gin-gonic/gin"

	"cart-service/internal/api/dto/response"
)

type rateLimiterEntry struct {
	mu        sync.Mutex
	tokens    float64
	lastRefill time.Time
	burst     int
	rps       float64
}

// RateLimiter implements per-user rate limiting using token bucket.
type RateLimiter struct {
	limiters sync.Map
	rps      float64
	burst    int
}

// NewRateLimiter creates a new rate limiter.
func NewRateLimiter(rps float64, burst int) *RateLimiter {
	return &RateLimiter{
		rps:   rps,
		burst: burst,
	}
}

func (rl *RateLimiter) getEntry(userID string) *rateLimiterEntry {
	entry, ok := rl.limiters.Load(userID)
	if ok {
		return entry.(*rateLimiterEntry)
	}

	e := &rateLimiterEntry{
		tokens:     float64(rl.burst),
		lastRefill: time.Now(),
		burst:      rl.burst,
		rps:        rl.rps,
	}
	actual, _ := rl.limiters.LoadOrStore(userID, e)
	return actual.(*rateLimiterEntry)
}

// Allow checks if a request is allowed for the given user.
func (rl *RateLimiter) Allow(userID string) bool {
	now := time.Now()
	entry := rl.getEntry(userID)

	entry.mu.Lock()
	defer entry.mu.Unlock()

	elapsed := now.Sub(entry.lastRefill).Seconds()
	entry.lastRefill = now
	entry.tokens += elapsed * entry.rps
	if entry.tokens > float64(entry.burst) {
		entry.tokens = float64(entry.burst)
	}

	if entry.tokens >= 1 {
		entry.tokens--
		return true
	}
	return false
}

// RateLimit returns the rate limiting middleware.
func RateLimit(cfg RateLimitConfig) gin.HandlerFunc {
	if !cfg.Enabled {
		return func(c *gin.Context) { c.Next() }
	}

	burst := cfg.MaxReqsPerMin / 10
	if burst < 1 {
		burst = 1
	}
	limiter := NewRateLimiter(float64(cfg.MaxReqsPerMin)/60.0, burst)

	return func(c *gin.Context) {
		userID := GetUserID(c)
		if userID == "" {
			userID = c.ClientIP()
		}

		if !limiter.Allow(userID) {
			c.AbortWithStatusJSON(http.StatusTooManyRequests,
				response.NewErrorResponse(
					response.ErrRateLimited,
					"Too many requests",
					GetRequestID(c),
				),
			)
			return
		}
		c.Next()
	}
}
