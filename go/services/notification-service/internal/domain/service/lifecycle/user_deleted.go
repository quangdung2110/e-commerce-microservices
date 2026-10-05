// Package lifecycle handles com.ecommerce.auth.user-deleted.v1
// (11-privacy.md §4, 09-data-model.md §10): every table keyed by the user is
// anonymised or emptied in one transaction, then the Redis keys are dropped.
// The whole thing is idempotent on user_id so at-least-once delivery is safe.
package lifecycle

import (
	"context"
	"fmt"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
)

// Deps are the ports the handler touches. The three non-store ports may be nil
// (a role without Redis or the Auth client) and are then skipped.
type Deps struct {
	Store         port.Store
	Contacts      port.ContactResolver
	FrequencyCap  port.FrequencyCap
	UnreadCounter port.UnreadCounter
	Clock         clock.Clock
	Logger        *zap.Logger
}

// Result counts what changed, for the log line.
type Result struct {
	DeliveriesSkipped    int64
	InboxDeleted         int64
	RequestsAnonymised   int64
	DeliveriesAnonymised int64
	SuppressionsDetached int64
}

// Service runs the seven steps of 11-privacy.md §4.
type Service struct {
	deps Deps
}

func New(deps Deps) *Service {
	if deps.Logger == nil {
		deps.Logger = zap.NewNop()
	}
	return &Service{deps: deps}
}

// HandleUserDeleted applies the deletion. Steps 1–5 are one transaction; step
// 6 (Redis/cache) runs after commit and is best effort — a missed key expires
// on its own TTL and holds no PII beyond the user id. Step 7 is "publish
// nothing" (Analytics follows Auth's own USER_DELETED).
func (s *Service) HandleUserDeleted(ctx context.Context, userID uuid.UUID) (Result, error) {
	if userID == uuid.Nil {
		return Result{}, fmt.Errorf("lifecycle: missing user_id")
	}
	now := s.deps.Clock.Now()
	var r Result
	err := s.deps.Store.WithinTx(ctx, func(ctx context.Context, tx port.Tx) error {
		var err error
		// 1. Nothing pending may still go out to a deleted account.
		if r.DeliveriesSkipped, err = tx.Deliveries().SkipPendingByUser(ctx, userID, entity.ReasonUserDeleted, now); err != nil {
			return fmt.Errorf("skip pending deliveries: %w", err)
		}
		// 2. Preference and inbox are deleted outright.
		if err = tx.Preferences().Delete(ctx, userID); err != nil {
			return fmt.Errorf("delete preference: %w", err)
		}
		if r.InboxDeleted, err = tx.Inbox().DeleteByUser(ctx, userID); err != nil {
			return fmt.Errorf("delete inbox: %w", err)
		}
		// 3. Requests keep decision + timestamps, lose user_id/template_data/subject.
		if r.RequestsAnonymised, err = tx.Requests().AnonymizeByUser(ctx, userID); err != nil {
			return fmt.Errorf("anonymise requests: %w", err)
		}
		// 4. Deliveries lose user_id.
		if r.DeliveriesAnonymised, err = tx.Deliveries().AnonymizeByUser(ctx, userID); err != nil {
			return fmt.Errorf("anonymise deliveries: %w", err)
		}
		// 5. Suppression keeps email_hash: a dead address stays dead.
		if r.SuppressionsDetached, err = tx.Suppressions().DetachUser(ctx, userID); err != nil {
			return fmt.Errorf("detach suppressions: %w", err)
		}
		return nil
	})
	if err != nil {
		return Result{}, fmt.Errorf("lifecycle: user_deleted %s: %w", userID, err)
	}

	// 6. Redis: contact cache, frequency-cap counters, unread counter.
	if s.deps.Contacts != nil {
		if err := s.deps.Contacts.ForgetContact(ctx, userID); err != nil {
			s.deps.Logger.Warn("forget contact cache failed", zap.String("user_id", userID.String()), zap.Error(err))
		}
	}
	if s.deps.FrequencyCap != nil {
		if err := s.deps.FrequencyCap.Forget(ctx, userID); err != nil {
			s.deps.Logger.Warn("forget frequency cap failed", zap.String("user_id", userID.String()), zap.Error(err))
		}
	}
	if s.deps.UnreadCounter != nil {
		if err := s.deps.UnreadCounter.Delete(ctx, userID); err != nil {
			s.deps.Logger.Warn("delete unread counter failed", zap.String("user_id", userID.String()), zap.Error(err))
		}
	}

	s.deps.Logger.Info("processed USER_DELETED",
		zap.String("user_id", userID.String()),
		zap.Int64("deliveries_skipped", r.DeliveriesSkipped),
		zap.Int64("inbox_deleted", r.InboxDeleted),
		zap.Int64("requests_anonymised", r.RequestsAnonymised),
		zap.Int64("deliveries_anonymised", r.DeliveriesAnonymised),
		zap.Int64("suppressions_detached", r.SuppressionsDetached),
	)
	return r, nil
}
