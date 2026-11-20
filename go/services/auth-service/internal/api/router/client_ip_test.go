package router

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"

	"github.com/ecommerce/auth-service/internal/common/config"
)

// clientIP returns what the per-IP rate limiter keys on for a request from
// remoteAddr carrying headers.
func clientIP(t *testing.T, security config.SecurityConfig, remoteAddr string, headers map[string]string) string {
	t.Helper()
	gin.SetMode(gin.TestMode)
	engine := gin.New()
	if err := configureClientIP(engine, security); err != nil {
		t.Fatalf("configureClientIP: %v", err)
	}
	var seen string
	engine.GET("/ip", func(c *gin.Context) { seen = c.ClientIP() })
	req := httptest.NewRequest(http.MethodGet, "/ip", nil)
	req.RemoteAddr = remoteAddr
	for name, value := range headers {
		req.Header.Set(name, value)
	}
	engine.ServeHTTP(httptest.NewRecorder(), req)
	return seen
}

// The shipped configuration: only the gateway is trusted and only X-Real-IP is
// read (security review 2026-10-22, H2).
var gatewayOnly = config.SecurityConfig{
	TrustedProxies:  []string{"10.89.3.2/32"},
	ClientIPHeaders: []string{"X-Real-IP"},
}

func TestTheGatewayNamesTheClientInXRealIP(t *testing.T) {
	got := clientIP(t, gatewayOnly, "10.89.3.2:41000", map[string]string{
		"X-Real-IP":       "203.0.113.7",
		"X-Forwarded-For": "198.51.100.1, 10.89.8.200",
	})
	if got != "203.0.113.7" {
		t.Fatalf("client IP = %q, want the X-Real-IP the gateway set", got)
	}
}

// Two browsers behind the same gateway get two budgets, not one shared one.
func TestTwoClientsBehindTheGatewayAreTwoKeys(t *testing.T) {
	a := clientIP(t, gatewayOnly, "10.89.3.2:41000", map[string]string{"X-Real-IP": "203.0.113.7"})
	b := clientIP(t, gatewayOnly, "10.89.3.2:41001", map[string]string{"X-Real-IP": "203.0.113.8"})
	if a == b {
		t.Fatalf("both clients keyed as %q", a)
	}
}

// A container that calls Auth directly is not the gateway: its headers are
// ignored and it is its own client.
func TestAPeerOtherThanTheGatewayCannotNameAClient(t *testing.T) {
	got := clientIP(t, gatewayOnly, "10.89.0.40:52000", map[string]string{
		"X-Real-IP":       "203.0.113.7",
		"X-Forwarded-For": "203.0.113.7",
	})
	if got != "10.89.0.40" {
		t.Fatalf("client IP = %q, want the peer address", got)
	}
}

// X-Forwarded-For is not read even from the gateway: through Next its last hop
// is the frontend container, which would put every browser in one bucket.
func TestXForwardedForIsNotReadFromTheGateway(t *testing.T) {
	got := clientIP(t, gatewayOnly, "10.89.3.2:41000", map[string]string{"X-Forwarded-For": "198.51.100.1"})
	if got != "10.89.3.2" {
		t.Fatalf("client IP = %q, want the gateway address when X-Real-IP is absent", got)
	}
}
