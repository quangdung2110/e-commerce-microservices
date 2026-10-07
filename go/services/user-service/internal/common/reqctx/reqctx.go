// Package reqctx carries request correlation data from the HTTP adapters and
// from the Kafka consumer into the domain without leaking the transport itself.
package reqctx

import (
	"context"

	"github.com/google/uuid"

	"github.com/ecommerce/user-service/internal/domain/event"
)

type requestIDKey struct{}
type traceparentKey struct{}

// WithRequestID stores the correlation identifier of the current request.
func WithRequestID(ctx context.Context, requestID string) context.Context {
	return context.WithValue(ctx, requestIDKey{}, requestID)
}

// RequestID returns the correlation identifier, or an empty string.
func RequestID(ctx context.Context) string {
	value, _ := ctx.Value(requestIDKey{}).(string)
	return value
}

// WithTraceparent stores the W3C trace context of the current request.
func WithTraceparent(ctx context.Context, traceparent string) context.Context {
	return context.WithValue(ctx, traceparentKey{}, traceparent)
}

// Traceparent returns the W3C trace context, or an empty string.
func Traceparent(ctx context.Context) string {
	value, _ := ctx.Value(traceparentKey{}).(string)
	return value
}

// EventMetadata builds the correlation fields of an outgoing CloudEvent. The
// event schema constrains correlationid to a UUID, so a non-UUID edge request
// id is dropped rather than published.
func EventMetadata(ctx context.Context) event.Metadata {
	meta := event.Metadata{TraceParent: Traceparent(ctx)}
	if requestID := RequestID(ctx); requestID != "" {
		if _, err := uuid.Parse(requestID); err == nil {
			meta.CorrelationID = requestID
		}
	}
	return meta
}
