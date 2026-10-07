package handlers

import (
	"context"
	"fmt"

	"github.com/IBM/sarama"
	"github.com/google/uuid"

	apperr "github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/common/logging"
	"github.com/ecommerce/notification-service/internal/infrastructure/kafka"
)

// TypeAuthUserDeleted is the identity event on t.auth.events Notification
// consumes (11-privacy.md §4).
const TypeAuthUserDeleted = "com.ecommerce.auth.user-deleted.v1"

type userDeletedData struct {
	UserID string `json:"user_id"`
}

// UserLifecycle runs the user-deleted anonymisation; the other identity
// events on the topic (USER_VERIFIED_SUCCESS) are acknowledged.
func UserLifecycle(deps HandlerDeps) kafka.MessageHandler {
	return func(ctx context.Context, msg *sarama.ConsumerMessage) error {
		ctx, env, err := parse(ctx, deps, msg)
		if err != nil {
			return err
		}
		if env.Type != TypeAuthUserDeleted {
			logging.FromContext(ctx).Debug("ignoring an auth identity event this consumer does not consume")
			return nil
		}
		var data userDeletedData
		if err := decodeData(env, &data); err != nil {
			return err
		}
		userID, err := uuid.Parse(data.UserID)
		if err != nil || userID == uuid.Nil {
			return apperr.NewPermanent(ReasonInvalidPayload, fmt.Errorf("user-deleted: user_id %q is not a uuid", data.UserID))
		}
		_, err = deps.Lifecycle.HandleUserDeleted(ctx, userID)
		return err
	}
}
