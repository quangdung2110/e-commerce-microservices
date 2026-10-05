package handlers

import (
	"context"
	"time"

	"github.com/IBM/sarama"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/common/logging"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/infrastructure/kafka"
)

// Auth event types on t.auth.notification.events (contracts/events/auth/v1).
const (
	TypeAuthOTPRequested           = "com.ecommerce.auth.user-otp-requested.v1"
	TypeAuthPasswordResetRequested = "com.ecommerce.auth.password-reset-requested.v1"
	TypeAuthEmailChanged           = "com.ecommerce.auth.user-email-changed.v1"
	TypeAuthPasswordChanged        = "com.ecommerce.auth.user-password-changed.v1"
	TypeAuthAccountLocked          = "com.ecommerce.auth.user-account-locked.v1"
	TypeAuthUnlocked               = "com.ecommerce.auth.user-unlocked.v1"
)

// Templates the Auth mapping produces (brief "Auth-sourced requests").
const (
	TemplatePasswordReset   = "PASSWORD_RESET"
	TemplateEmailChanged    = "EMAIL_CHANGED"
	TemplatePasswordChanged = "PASSWORD_CHANGED"
	TemplateAccountLocked   = "ACCOUNT_LOCKED"
	TemplateAccountUnlocked = "ACCOUNT_UNLOCKED"
)

// authData is the union of the six Auth payloads. Timestamps stay strings so
// they reach template_data exactly as the producer wrote them (the template
// schema checks date-time format).
type authData struct {
	DeliveryID  string     `json:"delivery_id"`
	UserID      string     `json:"user_id"`
	Purpose     string     `json:"purpose"`
	RequestedAt string     `json:"requested_at"`
	ExpiresAt   *time.Time `json:"expires_at"`
	ChangedAt   string     `json:"changed_at"`
	LockedAt    string     `json:"locked_at"`
	LockedUntil string     `json:"locked_until"`
	UnlockedAt  string     `json:"unlocked_at"`
}

// Auth maps the Auth request events to critical-lane IngestInputs. OTP and
// reset carry no template_data (secret, resolved at send time via
// ContactResolver.Content) and take the Auth expires_at; the four warnings
// carry a small template_data built from the event and a dedup key on
// delivery_id. Types Notification does not consume are acknowledged.
func Auth(deps HandlerDeps) kafka.MessageHandler {
	return func(ctx context.Context, msg *sarama.ConsumerMessage) error {
		ctx, env, err := parse(ctx, deps, msg)
		if err != nil {
			return err
		}
		var data authData
		switch env.Type {
		case TypeAuthOTPRequested, TypeAuthPasswordResetRequested, TypeAuthEmailChanged,
			TypeAuthPasswordChanged, TypeAuthAccountLocked, TypeAuthUnlocked:
			if err := decodeData(env, &data); err != nil {
				return err
			}
		default:
			logging.FromContext(ctx).Debug("ignoring an auth event this consumer does not map")
			return nil
		}

		in := base(env, entity.InputRequest)
		in.Lane = entity.LaneCritical
		in.UserID = data.UserID
		in.SourceRef = data.DeliveryID

		switch env.Type {
		case TypeAuthOTPRequested:
			// purpose is the template name (REGISTRATION_VERIFICATION |
			// EMAIL_CHANGE_VERIFICATION); an unknown purpose fails at step 1.
			in.Template = data.Purpose
			in.ExpiresAt = data.ExpiresAt
		case TypeAuthPasswordResetRequested:
			in.Template = TemplatePasswordReset
			in.ExpiresAt = data.ExpiresAt
		case TypeAuthEmailChanged:
			in.Template = TemplateEmailChanged
			in.TemplateData = mustJSON(map[string]string{"event": TemplateEmailChanged, "changed_at": data.ChangedAt})
		case TypeAuthPasswordChanged:
			in.Template = TemplatePasswordChanged
			in.TemplateData = mustJSON(map[string]string{"event": TemplatePasswordChanged, "changed_at": data.ChangedAt})
		case TypeAuthAccountLocked:
			in.Template = TemplateAccountLocked
			in.TemplateData = mustJSON(map[string]string{"event": TemplateAccountLocked, "locked_at": data.LockedAt, "locked_until": data.LockedUntil})
		case TypeAuthUnlocked:
			in.Template = TemplateAccountUnlocked
			in.TemplateData = mustJSON(map[string]string{"event": TemplateAccountUnlocked, "unlocked_at": data.UnlockedAt})
		}
		if tpl, ok := deps.Catalog.Get(in.Template); ok {
			// Catalog formula, e.g. "email_changed:{delivery_id}"; mode none
			// (OTP/reset) yields "" so every resend is a new delivery.
			in.DedupKey = dedupKey(tpl, map[string]string{"delivery_id": data.DeliveryID})
		}
		logging.FromContext(ctx).Debug("auth event mapped", zap.String("template", in.Template), zap.String("auth_delivery_id", data.DeliveryID))
		return runIngest(ctx, deps, in)
	}
}
