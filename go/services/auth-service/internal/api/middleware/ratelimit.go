package middleware

import (
	"net/http"
	"sync"
	"time"

	"github.com/gin-gonic/gin"
)

type rateLimiter struct {
	visitors map[string]*visitor
	mu       sync.RWMutex
}

type visitor struct {
	count    int
	lastSeen time.Time
}

func newRateLimiter() *rateLimiter {
	rl := &rateLimiter{
		visitors: make(map[string]*visitor),
	}

	go rl.cleanupLoop()

	return rl
}

func (rl *rateLimiter) cleanupLoop() {
	ticker := time.NewTicker(time.Minute)
	for range ticker.C {
		rl.mu.Lock()
		for ip, v := range rl.visitors {
			if time.Since(v.lastSeen) > time.Minute*5 {
				delete(rl.visitors, ip)
			}
		}
		rl.mu.Unlock()
	}
}

func (rl *rateLimiter) isAllowed(ip string, maxRequests int, window time.Duration) bool {
	rl.mu.Lock()
	defer rl.mu.Unlock()

	v, exists := rl.visitors[ip]
	if !exists {
		rl.visitors[ip] = &visitor{count: 1, lastSeen: time.Now()}
		return true
	}

	if time.Since(v.lastSeen) > window {
		v.count = 1
		v.lastSeen = time.Now()
		return true
	}

	if v.count >= maxRequests {
		return false
	}

	v.count++
	v.lastSeen = time.Now()
	return true
}

var limiter = newRateLimiter()

func RateLimit(maxRequests int, window time.Duration) gin.HandlerFunc {
	return func(c *gin.Context) {
		ip := c.ClientIP()

		if !limiter.isAllowed(ip, maxRequests, window) {
			c.AbortWithStatusJSON(http.StatusTooManyRequests, gin.H{
				"code":    "RATE_LIMIT_EXCEEDED",
				"message": "Too many requests",
			})
			return
		}

		c.Next()
	}
}
