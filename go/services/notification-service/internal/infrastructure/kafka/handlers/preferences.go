package handlers

import (
	"context"
	"fmt"
	"time"

	"github.com/IBM/sarama"
	"github.com/google/uuid"
	"go.uber.org/zap"

	apperr "github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/common/logging"
	"github.com/ecommerce/notification-service/internal/domain/service/preference"
	"github.com/ecommerce/notification-service/internal/infrastructure/kafka"
)

// TypePreferencesUpdated is the state event replicated from t.user.events.
const TypePreferencesUpdated = "com.ecommerce.user.preferences-updated.v1"

type preferencesData struct {
	UserID           string    `json:"user_id"`
	EmailEnabled     bool      `json:"email_enabled"`
	InAppEnabled     bool      `json:"in_app_enabled"`
	MarketingEnabled bool      `json:"marketing_enabled"`
	UpdatedAt        time.Time `json:"updated_at"`
	Timezone         *string   `json:"timezone"`
	Locale           *string   `json:"locale"`
}

// Preferences applies preferences-updated compare-and-set on updated_at
// (05-ordering.md §6). Other user events are acknowledged.
func Preferences(deps HandlerDeps) kafka.MessageHandler {
	return func(ctx context.Context, msg *sarama.ConsumerMessage) error {
		ctx, env, err := parse(ctx, deps, msg)
		if err != nil {
			return err
		}
		if env.Type != TypePreferencesUpdated {
			logging.FromContext(ctx).Debug("ignoring a user event this consumer does not consume")
			return nil
		}
		var data preferencesData
		if err := decodeData(env, &data); err != nil {
			return err
		}
		userID, err := uuid.Parse(data.UserID)
		if err != nil || userID == uuid.Nil {
			return apperr.NewPermanent(ReasonInvalidPayload, fmt.Errorf("preferences-updated: user_id %q is not a uuid", data.UserID))
		}
		if data.UpdatedAt.IsZero() {
			return apperr.NewPermanent(ReasonInvalidPayload, fmt.Errorf("preferences-updated: missing updated_at"))
		}
		applied, err := deps.Preferences.Apply(ctx, preference.Update{
			UserID: userID, EmailEnabled: data.EmailEnabled, InAppEnabled: data.InAppEnabled,
			MarketingEnabled: data.MarketingEnabled, UpdatedAt: data.UpdatedAt,
			Timezone: data.Timezone, Locale: data.Locale,
		})
		if err != nil {
			return err
		}
		logging.FromContext(ctx).Info("processed preferences-updated",
			zap.String("user_id", userID.String()), zap.Bool("applied", applied))
		return nil
	}
}
