// Package service holds the Auth use cases. Services own the business rules and
// the transaction boundary; adapters stay behind the ports.
package service

import (
	"context"
	"strings"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/common/clock"
	"github.com/ecommerce/auth-service/internal/common/constants"
	"github.com/ecommerce/auth-service/internal/common/reqctx"
	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/event"
	"github.com/ecommerce/auth-service/internal/domain/port"
)

// Notifier turns a security event into a delivery state plus a Kafka message.
//
// The two writes happen after the database transaction committed, so a failure
// cannot roll the domain change back. Failures are logged with the delivery id
// and event type and left to operational reconciliation (ADR-005), which is why
// these methods never return an error to the caller.
type Notifier struct {
	deliveries port.DeliveryStore
	publisher  port.EventPublisher
	clock      clock.Clock
	logger     *zap.Logger
	ttl        time.Duration
}

// NewNotifier builds the notification side effect helper. deliveryTTL bounds how
// long Notification Service may resolve the render content.
func NewNotifier(deliveries port.DeliveryStore, publisher port.EventPublisher, clk clock.Clock, logger *zap.Logger, deliveryTTL time.Duration) *Notifier {
	return &Notifier{
		deliveries: deliveries,
		publisher:  publisher,
		clock:      clk,
		logger:     logger,
		ttl:        deliveryTTL,
	}
}

// OtpRequested stores the code for delivery and asks Notification to send it.
// The code itself never enters the event payload.
func (n *Notifier) OtpRequested(ctx context.Context, accountID uuid.UUID, recipient, purpose, code string, codeExpiresAt time.Time) {
	template := constants.TemplateRegistrationVerification
	if purpose == constants.OtpPurposeEmailChange {
		template = constants.TemplateEmailChangeVerification
	}
	now := n.clock.Now()
	delivery := entity.NewOtpDelivery(accountID, recipient, template, code, n.deliveryExpiry(now, codeExpiresAt))
	if !n.store(ctx, delivery) {
		return
	}
	n.publish(ctx, event.NewUserOtpRequested(accountID, delivery.DeliveryID, purpose, now, codeExpiresAt, reqctx.EventMetadata(ctx)))
}

// PasswordResetRequested stores the opaque reset URL and asks Notification to
// email it.
func (n *Notifier) PasswordResetRequested(ctx context.Context, accountID uuid.UUID, recipient, resetURL string, tokenExpiresAt time.Time) {
	now := n.clock.Now()
	delivery := entity.NewPasswordResetDelivery(accountID, recipient, resetURL, n.deliveryExpiry(now, tokenExpiresAt))
	if !n.store(ctx, delivery) {
		return
	}
	n.publish(ctx, event.NewPasswordResetRequested(accountID, delivery.DeliveryID, now, tokenExpiresAt, reqctx.EventMetadata(ctx)))
}

// EmailChanged warns the previous address that the identity moved.
func (n *Notifier) EmailChanged(ctx context.Context, accountID uuid.UUID, previousRecipient string, changedAt time.Time) {
	delivery := entity.NewSecurityNoticeDelivery(accountID, previousRecipient, constants.TemplateEmailChanged,
		map[string]string{
			"event":      constants.TemplateEmailChanged,
			"changed_at": event.FormatTime(changedAt),
		}, n.clock.Now().Add(n.ttl))
	if !n.store(ctx, delivery) {
		return
	}
	n.publish(ctx, event.NewUserEmailChanged(accountID, delivery.DeliveryID, changedAt, reqctx.EventMetadata(ctx)))
}

// PasswordChanged notifies the owner that the credential changed.
func (n *Notifier) PasswordChanged(ctx context.Context, accountID uuid.UUID, recipient string, changedAt time.Time) {
	delivery := entity.NewSecurityNoticeDelivery(accountID, recipient, constants.TemplatePasswordChanged,
		map[string]string{
			"event":      constants.TemplatePasswordChanged,
			"changed_at": event.FormatTime(changedAt),
		}, n.clock.Now().Add(n.ttl))
	if !n.store(ctx, delivery) {
		return
	}
	n.publish(ctx, event.NewUserPasswordChanged(accountID, delivery.DeliveryID, changedAt, reqctx.EventMetadata(ctx)))
}

// AccountLocked reports that the failed-login threshold locked the account.
func (n *Notifier) AccountLocked(ctx context.Context, accountID uuid.UUID, recipient string, lockedAt, lockedUntil time.Time) {
	delivery := entity.NewSecurityNoticeDelivery(accountID, recipient, constants.TemplateAccountLocked,
		map[string]string{
			"event":        constants.TemplateAccountLocked,
			"locked_at":    event.FormatTime(lockedAt),
			"locked_until": event.FormatTime(lockedUntil),
		}, n.clock.Now().Add(n.ttl))
	if !n.store(ctx, delivery) {
		return
	}
	n.publish(ctx, event.NewUserAccountLocked(accountID, delivery.DeliveryID, lockedAt, lockedUntil, reqctx.EventMetadata(ctx)))
}

// AccountUnlocked reports that the worker returned the account to ACTIVE.
func (n *Notifier) AccountUnlocked(ctx context.Context, accountID uuid.UUID, recipient string, unlockedAt time.Time) {
	delivery := entity.NewSecurityNoticeDelivery(accountID, recipient, constants.TemplateAccountUnlocked,
		map[string]string{
			"event":       constants.TemplateAccountUnlocked,
			"unlocked_at": event.FormatTime(unlockedAt),
		}, n.clock.Now().Add(n.ttl))
	if !n.store(ctx, delivery) {
		return
	}
	n.publish(ctx, event.NewUserUnlocked(accountID, delivery.DeliveryID, unlockedAt, reqctx.EventMetadata(ctx)))
}

// Publish emits an identity event that needs no delivery state.
func (n *Notifier) Publish(ctx context.Context, envelope *event.Envelope) {
	n.publish(ctx, envelope)
}

// deliveryExpiry keeps the render state alive at least as long as the secret it
// carries, but never longer than the configured delivery TTL after that.
func (n *Notifier) deliveryExpiry(now, secretExpiresAt time.Time) time.Time {
	floor := now.Add(n.ttl)
	if secretExpiresAt.After(floor) {
		return secretExpiresAt
	}
	return floor
}

func (n *Notifier) store(ctx context.Context, delivery *entity.NotificationDelivery) bool {
	ttl := delivery.ExpiresAt.Sub(n.clock.Now())
	if ttl <= 0 {
		ttl = n.ttl
	}
	if err := n.deliveries.Put(ctx, delivery, ttl); err != nil {
		n.logger.Error("notification delivery state could not be stored",
			zap.String("delivery_id", delivery.DeliveryID.String()),
			zap.String("template", delivery.Template),
			zap.Error(err),
		)
		return false
	}
	return true
}

func (n *Notifier) publish(ctx context.Context, envelope *event.Envelope) {
	if err := n.publisher.Publish(ctx, envelope); err != nil {
		// The domain change is already committed. Losing the event here is the
		// accepted trade-off of ADR-005 and must be caught by alerting.
		n.logger.Error("identity event could not be published",
			zap.String("event_id", envelope.ID),
			zap.String("event_type", envelope.Type),
			zap.String("channel", string(envelope.Channel)),
			zap.Error(err),
		)
	}
}

// recipientOf returns the address a notice should reach, or an empty string when
// the account has been anonymized.
func recipientOf(account *entity.Account) string {
	address := strings.TrimSpace(account.Email.String())
	return address
}
