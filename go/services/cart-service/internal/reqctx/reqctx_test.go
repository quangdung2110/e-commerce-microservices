package reqctx

import (
	"context"
	"regexp"
	"strings"
	"testing"
	"time"
)

const (
	traceID     = "4bf92f3577b34da6a3ce929d0e0e4736"
	traceparent = "00-" + traceID + "-00f067aa0ba902b7-01"
)

func TestValidTraceparent(t *testing.T) {
	if got := ValidTraceparent("  " + traceparent + " "); got != traceparent {
		t.Fatalf("a valid traceparent is returned trimmed, got %q", got)
	}
	invalid := []string{
		"",
		"garbage",
		"00-" + strings.Repeat("0", 32) + "-00f067aa0ba902b7-01",
		"00-" + traceID + "-" + strings.Repeat("0", 16) + "-01",
		"ff-" + traceID + "-00f067aa0ba902b7-01",
		"00-" + strings.ToUpper(traceID) + "-00f067aa0ba902b7-01",
		"00-" + traceID + "-00f067aa0ba902b7",
	}
	for _, value := range invalid {
		if got := ValidTraceparent(value); got != "" {
			t.Errorf("ValidTraceparent(%q) = %q, want empty", value, got)
		}
	}
}

func TestNormalizeRequestID(t *testing.T) {
	uuidShape := regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

	if got := NormalizeRequestID("checkout:req.0001", ""); got != "checkout:req.0001" {
		t.Errorf("a well-formed id is kept, got %q", got)
	}
	for _, bad := range []string{"", "short", strings.Repeat("x", 129), "has space in it", "bad/char/!!"} {
		if got := NormalizeRequestID(bad, traceparent); got != traceID {
			t.Errorf("NormalizeRequestID(%q) with traceparent = %q, want the trace id", bad, got)
		}
		if got := NormalizeRequestID(bad, ""); !uuidShape.MatchString(got) {
			t.Errorf("NormalizeRequestID(%q) without traceparent = %q, want a UUID", bad, got)
		}
	}
}

func TestValidTracestate(t *testing.T) {
	if got := ValidTracestate("vendor=a,b=c", traceparent); got != "vendor=a,b=c" {
		t.Errorf("a plain tracestate is kept, got %q", got)
	}
	cases := map[string]struct{ value, parent string }{
		"no traceparent":   {"vendor=a", ""},
		"empty":            {"", traceparent},
		"too long":         {"k=" + strings.Repeat("v", 600), traceparent},
		"control char":     {"k=v\x01", traceparent},
		"non ascii":        {"k=vé", traceparent},
		"newline injected": {"k=v\r\nX: y", traceparent},
	}
	for name, c := range cases {
		if got := ValidTracestate(c.value, c.parent); got != "" {
			t.Errorf("%s: got %q, want empty", name, got)
		}
	}
}

func TestContextRoundTrip(t *testing.T) {
	ctx := WithRequestID(context.Background(), "request-id-1")
	ctx = WithTrace(ctx, traceparent, "vendor=a")

	if RequestID(ctx) != "request-id-1" || Traceparent(ctx) != traceparent || Tracestate(ctx) != "vendor=a" {
		t.Fatalf("values were not stored: %q %q %q", RequestID(ctx), Traceparent(ctx), Tracestate(ctx))
	}
	if TraceID(Traceparent(ctx)) != traceID {
		t.Fatalf("trace id = %q, want %q", TraceID(Traceparent(ctx)), traceID)
	}

	empty := WithTrace(context.Background(), "", "vendor=a")
	if Traceparent(empty) != "" || Tracestate(empty) != "" {
		t.Fatal("without a traceparent nothing is stored")
	}
}

func TestTimeoutFromHeader(t *testing.T) {
	good := map[string]time.Duration{
		"1":      time.Millisecond,
		"350":    350 * time.Millisecond,
		" 2000 ": 2 * time.Second,
		"600000": 10 * time.Minute,
	}
	for value, want := range good {
		if got, ok := TimeoutFromHeader(value); !ok || got != want {
			t.Errorf("TimeoutFromHeader(%q) = %v, %v; want %v, true", value, got, ok, want)
		}
	}
	for _, bad := range []string{"", "0", "-5", "abc", "1.5", "600001", "9999999", "1e3", "0x10"} {
		if got, ok := TimeoutFromHeader(bad); ok {
			t.Errorf("TimeoutFromHeader(%q) = %v, true; want no deadline", bad, got)
		}
	}
}
