package service

import (
	"context"

	"github.com/google/uuid"

	"github.com/ecommerce/user-service/internal/common/clock"
	"github.com/ecommerce/user-service/internal/common/reqctx"
	"github.com/ecommerce/user-service/internal/domain/entity"
	"github.com/ecommerce/user-service/internal/domain/event"
	"github.com/ecommerce/user-service/internal/domain/port"
)

// PreferencesService implements WF-USER-005. `language` and `currency` are
// fixed at `vi` and `VND`; only the three notification flags are writable.
//
// A missing preferences row is reported as ErrProfileNotFound rather than
// bootstrapped on the fly, so a purged account is never silently recreated.
type PreferencesService struct {
	preferences port.PreferencesRepository
	tx          port.TxManager
	emitter     *EventEmitter
	clock       clock.Clock
}

// NewPreferencesService wires the preference use cases.
func NewPreferencesService(preferences port.PreferencesRepository, tx port.TxManager, emitter *EventEmitter, clk clock.Clock) *PreferencesService {
	return &PreferencesService{preferences: preferences, tx: tx, emitter: emitter, clock: clk}
}

// Get returns the preferences of the authenticated owner.
func (s *PreferencesService) Get(ctx context.Context, userID uuid.UUID) (*entity.Preferences, error) {
	preferences, err := s.preferences.GetByUser(ctx, userID)
	if err != nil {
		return nil, err
	}
	if err := preferences.EnsureSupported(); err != nil {
		return nil, err
	}
	return preferences, nil
}

// MarketingOptOutResult reports what one complaint delivery did.
type MarketingOptOutResult struct {
	// Disabled is true when marketing was on and is now off.
	Disabled bool
}

// DisableMarketing is the spam-complaint reaction (docs/user/interfaces.md §4):
// Notification already stopped marketing in its local replica, and this makes
// the source of truth say the same, so a later snapshot or preference change
// cannot switch marketing back on for somebody who complained.
//
// It is idempotent. A flag that is already off, or a user without preferences
// (never bootstrapped, deleted or purged), changes nothing and emits nothing.
// The change is a single atomic UPDATE rather than a read-modify-write, so it
// cannot overwrite an email or in-app change the owner makes at the same time.
func (s *PreferencesService) DisableMarketing(ctx context.Context, userID uuid.UUID) (MarketingOptOutResult, error) {
	var changed *entity.Preferences
	err := s.tx.WithinTx(ctx, func(txCtx context.Context) error {
		updated, err := s.preferences.DisableMarketing(txCtx, userID)
		if err != nil {
			return err
		}
		changed = updated
		return nil
	})
	if err != nil {
		return MarketingOptOutResult{}, err
	}
	if changed == nil {
		return MarketingOptOutResult{}, nil
	}

	s.emitter.Emit(ctx, event.NewPreferencesUpdated(
		changed.UserID,
		changed.EmailEnabled,
		changed.InAppEnabled,
		changed.MarketingEnabled,
		changed.UpdatedAt,
		reqctx.EventMetadata(ctx),
	))
	return MarketingOptOutResult{Disabled: true}, nil
}

// Replace writes the three notification flags and publishes
// USER_PREFERENCES_UPDATED after the commit. Resending the same body changes
// nothing and emits nothing.
func (s *PreferencesService) Replace(ctx context.Context, userID uuid.UUID, changes entity.NotificationChanges) (*entity.Preferences, error) {
	now := s.clock.Now()

	var (
		preferences *entity.Preferences
		changed     bool
	)
	err := s.tx.WithinTx(ctx, func(txCtx context.Context) error {
		current, err := s.preferences.GetByUser(txCtx, userID)
		if err != nil {
			return err
		}
		if err := current.EnsureSupported(); err != nil {
			return err
		}

		preferences = current
		changed = current.Replace(changes, now)
		if !changed {
			return nil
		}
		return s.preferences.Update(txCtx, current)
	})
	if err != nil {
		return nil, err
	}

	if changed {
		s.emitter.Emit(ctx, event.NewPreferencesUpdated(
			preferences.UserID,
			preferences.EmailEnabled,
			preferences.InAppEnabled,
			preferences.MarketingEnabled,
			preferences.UpdatedAt,
			reqctx.EventMetadata(ctx),
		))
	}
	return preferences, nil
}
