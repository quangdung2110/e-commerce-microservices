package middleware

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"

	"cart-service/internal/reqctx"
)

const (
	testTraceID     = "4bf92f3577b34da6a3ce929d0e0e4736"
	testTraceparent = "00-" + testTraceID + "-00f067aa0ba902b7-01"
)

type seen struct {
	requestID   string
	traceparent string
	tracestate  string
}

func serve(t *testing.T, headers map[string]string) (*httptest.ResponseRecorder, seen) {
	t.Helper()
	gin.SetMode(gin.TestMode)
	engine := gin.New()
	engine.Use(RequestID())

	var got seen
	engine.GET("/x", func(c *gin.Context) {
		ctx := c.Request.Context()
		got = seen{reqctx.RequestID(ctx), reqctx.Traceparent(ctx), reqctx.Tracestate(ctx)}
		c.Status(http.StatusNoContent)
	})

	request := httptest.NewRequest(http.MethodGet, "/x", nil)
	for name, value := range headers {
		request.Header.Set(name, value)
	}
	recorder := httptest.NewRecorder()
	engine.ServeHTTP(recorder, request)
	return recorder, got
}

func TestRequestIDPublishesTheInboundContextOnTheRequestContext(t *testing.T) {
	recorder, got := serve(t, map[string]string{
		"X-Request-Id": "checkout-request-1",
		"Traceparent":  testTraceparent,
		"Tracestate":   "vendor=a",
	})

	if got.requestID != "checkout-request-1" || got.traceparent != testTraceparent || got.tracestate != "vendor=a" {
		t.Fatalf("handler context = %+v", got)
	}
	if header := recorder.Header().Get("X-Request-Id"); header != "checkout-request-1" {
		t.Fatalf("response X-Request-Id = %q", header)
	}
}

func TestRequestIDReplacesAMalformedIDWithTheTraceIDAndNeverRejects(t *testing.T) {
	for _, bad := range []string{"short", strings.Repeat("x", 129), "has space in it", "bad/char/!!"} {
		recorder, got := serve(t, map[string]string{"X-Request-Id": bad, "Traceparent": testTraceparent})

		if recorder.Code != http.StatusNoContent {
			t.Fatalf("%q: status = %d, a bad request id must not fail the request", bad, recorder.Code)
		}
		if got.requestID != testTraceID {
			t.Errorf("%q: request id = %q, want the trace id", bad, got.requestID)
		}
	}
}

func TestRequestIDMintsAUUIDWithoutAnyInboundContextAndInventsNoTraceparent(t *testing.T) {
	recorder, got := serve(t, nil)

	if len(got.requestID) != 36 {
		t.Fatalf("request id = %q, want a UUID", got.requestID)
	}
	if got.traceparent != "" || got.tracestate != "" {
		t.Fatalf("a traceparent must never be invented: %+v", got)
	}
	if recorder.Header().Get("X-Request-Id") != got.requestID {
		t.Fatal("the minted id must be returned to the caller")
	}
}

func TestRequestIDDropsAnInvalidTraceparentAndItsTracestate(t *testing.T) {
	_, got := serve(t, map[string]string{
		"X-Request-Id": "checkout-request-1",
		"Traceparent":  "00-not-a-traceparent",
		"Tracestate":   "vendor=a",
	})

	if got.traceparent != "" || got.tracestate != "" {
		t.Fatalf("invalid trace context must not be forwarded: %+v", got)
	}
}

func serveWithDeadline(t *testing.T, header string) (time.Duration, bool) {
	t.Helper()
	gin.SetMode(gin.TestMode)
	engine := gin.New()
	engine.Use(RequestID(), CallerDeadline())

	var remaining time.Duration
	var bounded bool
	engine.GET("/x", func(c *gin.Context) {
		if deadline, ok := c.Request.Context().Deadline(); ok {
			bounded = true
			remaining = time.Until(deadline)
		}
		c.Status(http.StatusNoContent)
	})

	request := httptest.NewRequest(http.MethodGet, "/x", nil)
	if header != "" {
		request.Header.Set("X-Request-Timeout-Ms", header)
	}
	engine.ServeHTTP(httptest.NewRecorder(), request)
	return remaining, bounded
}

func TestCallerDeadlineBoundsTheRequestContextByTheAnnouncedTimeout(t *testing.T) {
	remaining, bounded := serveWithDeadline(t, "1500")

	if !bounded || remaining <= 0 || remaining > 1500*time.Millisecond {
		t.Fatalf("deadline = %v (bounded=%v), want within (0, 1500ms]", remaining, bounded)
	}
}

func TestCallerDeadlineIgnoresAMissingOrMalformedHeader(t *testing.T) {
	for _, header := range []string{"", "abc", "0", "-1", "600001"} {
		if _, bounded := serveWithDeadline(t, header); bounded {
			t.Errorf("header %q must not bound the request", header)
		}
	}
}
