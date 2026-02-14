package v1_middleware

import (
	"net/http"
	"time"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/repository"
)

const logWindow = 5 * time.Second

// RateLimitMiddleware creates rate limiting middleware using Redis
// Uses token bucket algorithm with configurable limit and burst

/*
lần đầu request
allowed true, lastseen = 0
-> ratelimit:count:{ip}   : 1   TTL 1 phút

lần đầu lố request trong 1 phút
allowed false, lastseen = 0
-> ratelimit:lastseen:{ip}   : timestamp   1 phút bằng với request
rồi ghi log

tiếp tục lố request
allowed false, lastseen = timestamp cụ thể
nếu không quá logWindow -> ratelimit:suppressed:{ip}  :   1   TTL = logWindow
không ghi log
cứ tiếp tục như thế thì ratelimit:suppressed:{ip} tăng

tiếp tục lố request
allowed false, lastseen = timestamp cụ thể
nếu quá logWindow -> reset suppressed -> xóa ratelimit:suppressed:{ip}, update lastseen ratelimit:lastseen:{ip}  : now()
có ghi log

cứ tiếp tục như thế cho đến khi hết 1 phút
*/
func RateLimitMiddleware(logger *zap.Logger, rateLimitRepo repository.RateLimitRepository, burst int) gin.HandlerFunc {
	return func(ctx *gin.Context) {
		ip := ctx.ClientIP()

		// Check rate limit using Redis
		allowed, _, lastSeen, err := rateLimitRepo.CheckRateLimit(ctx.Request.Context(), ip, int64(burst))
		if err != nil {
			logger.Error("Failed to check rate limit", zap.Error(err), zap.String("ip", ip))
			// On error, allow request to proceed (fail open)
			ctx.Next()
			return
		}

		if !allowed {
			// Check if we should log this (throttle logs)
			now := time.Now().UnixNano()
			shouldLog := false
			var suppressedCount int64 = 0

			if lastSeen == 0 || now-lastSeen >= logWindow.Nanoseconds() {
				// Reset suppressed counter and log
				suppressedCount, _ = rateLimitRepo.GetAndResetSuppressedCount(ctx.Request.Context(), ip)
				rateLimitRepo.UpdateLastSeen(ctx.Request.Context(), ip, now)
				shouldLog = true
			} else {
				// Increment suppressed counter
				rateLimitRepo.IncrementSuppressedCount(ctx.Request.Context(), ip)
			}

			if shouldLog {
				logger.Warn("Request rate limited",
					zap.String("path", ctx.Request.URL.Path),
					zap.String("query", ctx.Request.URL.RawQuery),
					zap.String("method", ctx.Request.Method),
					zap.Int64("suppressed", suppressedCount),
					zap.String("client_ip", ip),
					zap.String("protocol", ctx.Request.Proto))
			}

			ctx.AbortWithStatusJSON(http.StatusTooManyRequests, gin.H{
				"error": "Too many requests",
			})
			return
		}

		// Request allowed, increment counter
		if err := rateLimitRepo.IncrementRequestCount(ctx.Request.Context(), ip, time.Minute); err != nil {
			logger.Error("Failed to increment request count", zap.Error(err), zap.String("ip", ip))
		}

		ctx.Next()
	}
}
