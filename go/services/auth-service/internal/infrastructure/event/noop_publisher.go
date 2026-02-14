package event

import (
	"context"

	"go.uber.org/zap"

	common "github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/common"
)

// NoOpEventPublisher is a no-op implementation of EventPublisher for testing/mock purposes
type NoOpEventPublisher struct {
	logger *zap.Logger
}

// NewNoOpEventPublisher creates a new no-op event publisher
func NewNoOpEventPublisher(logger *zap.Logger) common.EventPublisher {
	return &NoOpEventPublisher{
		logger: logger,
	}
}

// Close is a no-op
func (p *NoOpEventPublisher) Close() error {
	p.logger.Info("NoOp event publisher closed")
	return nil
}

// PublishUserRegistered is a no-op
func (p *NoOpEventPublisher) PublishUserRegistered(ctx context.Context, event *common.UserRegisteredEvent) error {
	p.logger.Debug("NoOp: UserRegistered event",
		zap.String("user_id", event.UserID.String()))
	return nil
}

// PublishManagedAccountCreated is a no-op
func (p *NoOpEventPublisher) PublishManagedAccountCreated(ctx context.Context, event *common.ManagedAccountCreatedEvent) error {
	p.logger.Debug("NoOp: ManagedAccountCreated event",
		zap.String("user_id", event.UserID.String()))
	return nil
}

// PublishUserLoggedIn is a no-op
func (p *NoOpEventPublisher) PublishUserLoggedIn(ctx context.Context, event *common.UserLoggedInEvent) error {
	p.logger.Debug("NoOp: UserLoggedIn event",
		zap.String("user_id", event.UserID.String()))
	return nil
}

// PublishUserLoggedOut is a no-op
func (p *NoOpEventPublisher) PublishUserLoggedOut(ctx context.Context, event *common.UserLoggedOutEvent) error {
	p.logger.Debug("NoOp: UserLoggedOut event",
		zap.String("user_id", event.UserID.String()))
	return nil
}

// PublishPasswordChanged is a no-op
func (p *NoOpEventPublisher) PublishPasswordChanged(ctx context.Context, event *common.PasswordChangedEvent) error {
	if event.UserID != nil {
		p.logger.Debug("NoOp: PasswordChanged event",
			zap.String("user_id", event.UserID.String()))
	}
	return nil
}

// PublishPasswordResetRequested is a no-op
func (p *NoOpEventPublisher) PublishPasswordResetRequested(ctx context.Context, event *common.PasswordResetRequestedEvent) error {
	if event.UserID != nil {
		p.logger.Debug("NoOp: PasswordResetRequested event",
			zap.String("user_id", event.UserID.String()))
	}
	return nil
}

// PublishSuspiciousActivity is a no-op
func (p *NoOpEventPublisher) PublishSuspiciousActivity(ctx context.Context, event *common.SuspiciousActivityEvent) error {
	if event.UserID != nil {
		p.logger.Debug("NoOp: SuspiciousActivity event",
			zap.String("user_id", event.UserID.String()))
	}
	return nil
}
