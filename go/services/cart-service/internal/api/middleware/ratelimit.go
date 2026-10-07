package middleware

import (
	"net/http"
	"strconv"
	"sync"
	"time"

	"github.com/gin-gonic/gin"

	"cart-service/internal/api/dto/response"
	"cart-service/internal/domain"
)

// RateLimitConfig configures the per-user token bucket.
type RateLimitConfig struct {
	// RequestsPerSecond is the sustained refill rate.
	RequestsPerSecond float64
	// Burst is the bucket capacity.
	Burst int
	// IdleTimeout is how long an unused bucket is kept before eviction.
	IdleTimeout time.Duration
}

// RateLimit applies a per-user token bucket.
//
// The limit is keyed by the cart token rather than the user id, so no user
// identifier ends up in the limiter's memory or in any metric derived from it.
// This is a local safety net; the Gateway owns the real policy.
func RateLimit(cfg RateLimitConfig) gin.HandlerFunc {
	if cfg.RequestsPerSecond <= 0 {
		return func(c *gin.Context) { c.Next() }
	}
	limiter := newBucketLimiter(cfg)

	return func(c *gin.Context) {
		identity := GetIdentity(c)
		if identity.UserToken == "" {
			c.Next()
			return
		}

		if !limiter.allow(identity.UserToken) {
			retryAfter := int(1/cfg.RequestsPerSecond) + 1
			c.Header("Retry-After", strconv.Itoa(retryAfter))
			problem := response.NewProblem(
				http.StatusTooManyRequests,
				domain.CodeRateLimited,
				"too many cart requests; retry shortly",
				c.Request.URL.Path,
				GetRequestID(c),
			)
			problem.Retryable = true
			AbortWithProblem(c, problem)
			return
		}
		c.Next()
	}
}

type bucket struct {
	tokens   float64
	lastSeen time.Time
}

type bucketLimiter struct {
	cfg RateLimitConfig
	now func() time.Time

	mu      sync.Mutex
	buckets map[string]*bucket
}

func newBucketLimiter(cfg RateLimitConfig) *bucketLimiter {
	if cfg.Burst <= 0 {
		cfg.Burst = 1
	}
	if cfg.IdleTimeout <= 0 {
		cfg.IdleTimeout = 10 * time.Minute
	}
	return &bucketLimiter{cfg: cfg, now: time.Now, buckets: make(map[string]*bucket)}
}

func (l *bucketLimiter) allow(key string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()

	now := l.now()
	entry, ok := l.buckets[key]
	if !ok {
		l.evictIdle(now)
		l.buckets[key] = &bucket{tokens: float64(l.cfg.Burst) - 1, lastSeen: now}
		return true
	}

	elapsed := now.Sub(entry.lastSeen).Seconds()
	entry.tokens += elapsed * l.cfg.RequestsPerSecond
	if entry.tokens > float64(l.cfg.Burst) {
		entry.tokens = float64(l.cfg.Burst)
	}
	entry.lastSeen = now

	if entry.tokens < 1 {
		return false
	}
	entry.tokens--
	return true
}

// evictIdle drops buckets nobody has touched recently, so the map cannot grow
// without bound across the lifetime of the process.
func (l *bucketLimiter) evictIdle(now time.Time) {
	for key, entry := range l.buckets {
		if now.Sub(entry.lastSeen) > l.cfg.IdleTimeout {
			delete(l.buckets, key)
		}
	}
}
