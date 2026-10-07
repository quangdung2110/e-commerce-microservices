// Package reqctx carries request-scoped identifiers (request id, principal,
// traceparent) through context without importing the HTTP framework.
package reqctx

import "context"

type key int

const (
	keyRequestID key = iota
	keyPrincipal
	keyTraceParent
)

func WithRequestID(ctx context.Context, id string) context.Context {
	return context.WithValue(ctx, keyRequestID, id)
}

func RequestID(ctx context.Context) string {
	v, _ := ctx.Value(keyRequestID).(string)
	return v
}

// WithPrincipal stores the verified service identity (internal listener).
func WithPrincipal(ctx context.Context, principal string) context.Context {
	return context.WithValue(ctx, keyPrincipal, principal)
}

func Principal(ctx context.Context) string {
	v, _ := ctx.Value(keyPrincipal).(string)
	return v
}

func WithTraceParent(ctx context.Context, tp string) context.Context {
	return context.WithValue(ctx, keyTraceParent, tp)
}

func TraceParent(ctx context.Context) string {
	v, _ := ctx.Value(keyTraceParent).(string)
	return v
}
