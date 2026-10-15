package httpx

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/gin-gonic/gin"

	apperr "github.com/ecommerce/notification-service/internal/common/errors"
)

func TestRetryAfterSeconds(t *testing.T) {
	cases := []struct {
		name      string
		status    int
		retryable bool
		wait      time.Duration
		want      int
	}{
		{"429 uses the limiter window rounded up", http.StatusTooManyRequests, true, 1200 * time.Millisecond, 2},
		{"429 sub-second window is one second", http.StatusTooManyRequests, true, 40 * time.Millisecond, 1},
		{"429 without an estimate gets the default", http.StatusTooManyRequests, true, 0, 1},
		{"429 window is capped", http.StatusTooManyRequests, true, 3 * time.Hour, 3600},
		{"retryable 503 gets the default", http.StatusServiceUnavailable, true, 0, 2},
		{"retryable 503 keeps its own estimate", http.StatusServiceUnavailable, true, 7 * time.Second, 7},
		{"non-retryable 503 has none", http.StatusServiceUnavailable, false, 5 * time.Second, 0},
		{"500 has none", http.StatusInternalServerError, true, 0, 0},
		{"404 has none", http.StatusNotFound, false, 0, 0},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := retryAfterSeconds(tc.status, tc.retryable, tc.wait); got != tc.want {
				t.Fatalf("retryAfterSeconds(%d, %v, %v) = %d, want %d", tc.status, tc.retryable, tc.wait, got, tc.want)
			}
		})
	}
}

func writeProblem(t *testing.T, err error) *httptest.ResponseRecorder {
	t.Helper()
	gin.SetMode(gin.TestMode)
	engine := gin.New()
	engine.GET("/x", func(c *gin.Context) { WriteProblem(c, err) })
	recorder := httptest.NewRecorder()
	engine.ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, "/x", nil))
	return recorder
}

func TestWriteProblemAdvertisesRetryAfterOnRetryableUnavailable(t *testing.T) {
	recorder := writeProblem(t, apperr.New(apperr.CodeDependencyUnavailable, "store down").AsRetryable(0))

	if recorder.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503", recorder.Code)
	}
	if got := recorder.Header().Get("Retry-After"); got != "2" {
		t.Fatalf("Retry-After = %q, want 2", got)
	}
}

func TestWriteProblemAdvertisesTheLimiterWindowOnRateLimit(t *testing.T) {
	recorder := writeProblem(t, apperr.New(apperr.CodeRateLimited, "slow down").AsRetryable(1500*time.Millisecond))

	if got := recorder.Header().Get("Retry-After"); got != "2" {
		t.Fatalf("Retry-After = %q, want 2", got)
	}
}

func TestWriteProblemLeavesOutRetryAfterOnANonRetryableUnavailable(t *testing.T) {
	recorder := writeProblem(t, apperr.New(apperr.CodeDependencyUnavailable, "cancelled"))

	if got := recorder.Header().Get("Retry-After"); got != "" {
		t.Fatalf("Retry-After = %q, want none", got)
	}
}
