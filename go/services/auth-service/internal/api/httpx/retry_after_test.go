package httpx

import (
	"net/http"
	"testing"
	"time"
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
