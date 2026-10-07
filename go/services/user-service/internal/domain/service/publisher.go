// Package service holds the User use cases. Services own the business rules and
// the transaction boundary; adapters stay behind the ports.
package service

import (
	"context"

	"go.uber.org/zap"

	"github.com/ecommerce/user-service/internal/domain/event"
	"github.com/ecommerce/user-service/internal/domain/port"
)

// EventEmitter publishes an integration event after the transaction committed.
//
// It never returns an error to the use case: the domain change is already
// durable, so failing the HTTP request would tell the client the update did not
// happen when it did. An exhausted publish is logged and counted instead, and
// the daily reconciliation sweep republishes the current snapshot (ADR-004,
// ADR-007).
type EventEmitter struct {
	publisher port.EventPublisher
	logger    *zap.Logger
}

// NewEventEmitter wires the post-commit publisher.
func NewEventEmitter(publisher port.EventPublisher, logger *zap.Logger) *EventEmitter {
	return &EventEmitter{publisher: publisher, logger: logger}
}

// Emit publishes one envelope and reports whether it reached the broker.
func (e *EventEmitter) Emit(ctx context.Context, envelope *event.Envelope) bool {
	if err := e.publisher.Publish(ctx, envelope); err != nil {
		e.logger.Error("user event could not be published",
			zap.String("event_id", envelope.ID),
			zap.String("event_type", envelope.Type),
			zap.String("user_id", envelope.Key),
			zap.Error(err),
		)
		return false
	}
	return true
}
