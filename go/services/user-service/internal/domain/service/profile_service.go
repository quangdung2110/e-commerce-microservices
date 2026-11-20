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

// ProfileService implements WF-USER-002: the owner reads and patches their own
// profile. Reads go straight to PostgreSQL; profiles are not cached by design
// (ADR-002).
type ProfileService struct {
	profiles port.ProfileRepository
	tx       port.TxManager
	emitter  *EventEmitter
	clock    clock.Clock
}

// NewProfileService wires the profile use cases.
func NewProfileService(profiles port.ProfileRepository, tx port.TxManager, emitter *EventEmitter, clk clock.Clock) *ProfileService {
	return &ProfileService{profiles: profiles, tx: tx, emitter: emitter, clock: clk}
}

// Get returns the profile of the authenticated owner, or ErrProfileNotFound
// when it has not been bootstrapped yet, was anonymized after the account was
// deleted, or was already purged.
func (s *ProfileService) Get(ctx context.Context, userID uuid.UUID) (*entity.Profile, error) {
	return s.profiles.GetActive(ctx, userID)
}

// Update applies a merge patch and publishes USER_PROFILE_UPDATED after the
// commit. An unchanged patch is a no-op: it neither writes nor emits an event.
func (s *ProfileService) Update(ctx context.Context, userID uuid.UUID, changes entity.ProfileChanges) (*entity.Profile, error) {
	now := s.clock.Now()

	var (
		profile *entity.Profile
		changed bool
	)
	err := s.tx.WithinTx(ctx, func(txCtx context.Context) error {
		// The row lock serializes this update against a concurrent
		// anonymization, and the retention check that follows it refuses an
		// update that arrives after the anonymization committed. Together they
		// mean a late update can never write PII back into a deleted account
		// (USER-INV-005).
		current, err := s.profiles.GetActiveForUpdate(txCtx, userID)
		if err != nil {
			return err
		}

		changed, err = current.Apply(changes, now)
		if err != nil {
			return err
		}
		profile = current
		if !changed {
			return nil
		}
		return s.profiles.Update(txCtx, current)
	})
	if err != nil {
		return nil, err
	}

	if changed {
		s.emitter.Emit(ctx, event.NewProfileUpdated(
			profile.ID, profile.DisplayName(), profile.UpdatedAt, reqctx.EventMetadata(ctx)))
	}
	return profile, nil
}
