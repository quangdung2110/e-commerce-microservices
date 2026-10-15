// Package reqctx carries the request ID and the W3C trace context of the request being served
// through context.Context, so outbound calls made deep in the service can pass them on
// (contracts ADR-002). Cart has no tracer, so it passes the inbound context through and never
// invents a traceparent.
package reqctx

import (
	"context"
	"regexp"
	"strings"

	"github.com/google/uuid"
)

// Header and gRPC metadata names. gRPC metadata keys are lower case.
const (
	HeaderRequestID   = "X-Request-Id"
	HeaderTraceparent = "traceparent"
	HeaderTracestate  = "tracestate"
)

// maxTracestate is the W3C limit for a whole tracestate value.
const maxTracestate = 512

var (
	requestIDPattern = regexp.MustCompile(`^[A-Za-z0-9._:-]{8,128}$`)
	traceparentRe    = regexp.MustCompile(`^([0-9a-f]{2})-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}$`)
)

type key int

const (
	keyRequestID key = iota
	keyTraceparent
	keyTracestate
)

// WithRequestID stores the normalized request ID.
func WithRequestID(ctx context.Context, id string) context.Context {
	return context.WithValue(ctx, keyRequestID, id)
}

// RequestID returns the request ID, or "" when the context has none.
func RequestID(ctx context.Context) string {
	value, _ := ctx.Value(keyRequestID).(string)
	return value
}

// WithTrace stores a valid traceparent and, with it, an optional tracestate.
func WithTrace(ctx context.Context, traceparent, tracestate string) context.Context {
	if traceparent == "" {
		return ctx
	}
	ctx = context.WithValue(ctx, keyTraceparent, traceparent)
	if tracestate != "" {
		ctx = context.WithValue(ctx, keyTracestate, tracestate)
	}
	return ctx
}

// Traceparent returns the inbound traceparent, or "".
func Traceparent(ctx context.Context) string {
	value, _ := ctx.Value(keyTraceparent).(string)
	return value
}

// Tracestate returns the inbound tracestate, or "".
func Tracestate(ctx context.Context) string {
	value, _ := ctx.Value(keyTracestate).(string)
	return value
}

// ValidTraceparent returns the trimmed value when it is a well-formed, non-zero W3C traceparent
// and "" otherwise.
func ValidTraceparent(value string) string {
	value = strings.TrimSpace(value)
	match := traceparentRe.FindStringSubmatch(value)
	if match == nil || match[1] == "ff" || match[2] == strings.Repeat("0", 32) || match[3] == strings.Repeat("0", 16) {
		return ""
	}
	return value
}

// TraceID returns the 32-hex trace ID of a valid traceparent, or "".
func TraceID(traceparent string) string {
	valid := ValidTraceparent(traceparent)
	if valid == "" {
		return ""
	}
	return valid[3:35]
}

// ValidTracestate returns the value only when a traceparent accompanies it and it is printable
// ASCII within the W3C size limit. gRPC rejects a metadata value outside printable ASCII, so an
// unchecked tracestate could fail the call it was meant to describe.
func ValidTracestate(value, traceparent string) string {
	if traceparent == "" || value == "" || len(value) > maxTracestate {
		return ""
	}
	for i := 0; i < len(value); i++ {
		if value[i] < 0x20 || value[i] > 0x7e {
			return ""
		}
	}
	return value
}

// NormalizeRequestID keeps a well-formed X-Request-Id and otherwise falls back to the trace ID of
// a valid traceparent, then to a new UUID. A bad value never fails a request.
func NormalizeRequestID(header, traceparent string) string {
	if requestIDPattern.MatchString(header) {
		return header
	}
	if id := TraceID(traceparent); id != "" {
		return id
	}
	return uuid.NewString()
}
