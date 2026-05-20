package port

import "context"

type EventPublisher interface {
	Publish(ctx context.Context, event interface{}) error
}

type NoOpEventPublisher struct{}

func (p *NoOpEventPublisher) Publish(ctx context.Context, event interface{}) error {
	return nil
}
