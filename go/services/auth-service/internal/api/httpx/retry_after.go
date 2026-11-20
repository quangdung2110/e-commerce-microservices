package httpx

import (
	"math"
	"net/http"
	"time"
)

// Retry-After values (contracts ADR-005). A rate limiter passes the time left in its window; when a
// 429 has none, or a retryable 503 has no better estimate, these defaults apply.
const (
	defaultThrottleRetryAfterSeconds    = 1
	defaultUnavailableRetryAfterSeconds = 2
	maxRetryAfterSeconds                = 3600
)

// retryAfterSeconds returns the Retry-After value for a problem response, or 0 when the response must
// not carry one. Every 429 and every retryable 503 carries it; a 503 that is not retryable never does.
func retryAfterSeconds(status int, retryable bool, wait time.Duration) int {
	if status == http.StatusServiceUnavailable && !retryable {
		return 0
	}
	if wait > 0 {
		seconds := int(math.Ceil(wait.Seconds()))
		if seconds < 1 {
			seconds = 1
		}
		if seconds > maxRetryAfterSeconds {
			seconds = maxRetryAfterSeconds
		}
		return seconds
	}
	switch {
	case status == http.StatusTooManyRequests:
		return defaultThrottleRetryAfterSeconds
	case status == http.StatusServiceUnavailable:
		return defaultUnavailableRetryAfterSeconds
	}
	return 0
}
