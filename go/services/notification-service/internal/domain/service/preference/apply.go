// Package preference replicates t.user.events preferences-updated into the
// local user_preference table (05-ordering.md §6): the event is state, not a
// notification, so it is applied compare-and-set on version = updated_at in
// epoch milliseconds and a late replay can never overwrite a newer row.
package preference

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/ecommerce/notification-service/internal/common/clock"
	"github.com/ecommerce/notification-service/internal/domain/entity"
	"github.com/ecommerce/notification-service/internal/domain/port"
)

// Update is the data block of com.ecommerce.user.preferences-updated.v1.
type Update struct {
	UserID           uuid.UUID
	EmailEnabled     bool
	InAppEnabled     bool
	MarketingEnabled bool
	UpdatedAt        time.Time
	// Timezone and Locale are not in the v1 contract; kept optional so a later
	// contract version can fill them without touching the pipeline.
	Timezone *string
	Locale   *string
}

// Service applies preference updates.
type Service struct {
	store  port.Store
	clock  clock.Clock
	logger *zap.Logger
}

func New(store port.Store, clk clock.Clock, logger *zap.Logger) *Service {
	if logger == nil {
		logger = zap.NewNop()
	}
	return &Service{store: store, clock: clk, logger: logger}
}

// Apply upserts the replica. It returns applied=false (no error) when the
// stored version is already newer — expected under replay, logged for
// visibility only.
func (s *Service) Apply(ctx context.Context, u Update) (applied bool, err error) {
	if u.UserID == uuid.Nil {
		return false, fmt.Errorf("preference: missing user_id")
	}
	p := &entity.Preference{
		UserID:           u.UserID,
		EmailEnabled:     u.EmailEnabled,
		InAppEnabled:     u.InAppEnabled,
		MarketingEnabled: u.MarketingEnabled,
		Timezone:         u.Timezone,
		Locale:           u.Locale,
		Version:          u.UpdatedAt.UnixMilli(),
		SyncedAt:         s.clock.Now(),
	}
	applied, err = s.store.Preferences().Upsert(ctx, p)
	if err != nil {
		return false, fmt.Errorf("preference: upsert: %w", err)
	}
	if !applied {
		s.logger.Info("stale preferences-updated ignored",
			zap.String("user_id", u.UserID.String()), zap.Int64("version", p.Version))
	}
	return applied, nil
}
