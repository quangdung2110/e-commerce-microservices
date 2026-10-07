package service

import (
	"context"
	"errors"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/ecommerce/user-service/internal/common/clock"
	apperr "github.com/ecommerce/user-service/internal/common/errors"
	"github.com/ecommerce/user-service/internal/domain/entity"
	"github.com/ecommerce/user-service/internal/domain/port"
	"github.com/ecommerce/user-service/internal/domain/vo"
)

// BootstrapResult reports what one USER_VERIFIED_SUCCESS delivery did, so the
// consumer can count duplicates instead of hiding them.
type BootstrapResult struct {
	ProfileCreated     bool
	PreferencesCreated bool
	// SkippedDeleted means a retention record already exists for this user. The
	// bootstrap is refused because deletion is terminal: a verification event
	// that arrives late must never resurrect a deleted account.
	SkippedDeleted bool
}

// AnonymizationResult reports what one USER_DELETED delivery did.
type AnonymizationResult struct {
	AlreadyProcessed   bool
	ProfileAnonymized  bool
	AddressesDeleted   int64
	PreferencesDeleted bool
}

// PurgeResult is one pass of the retention worker.
type PurgeResult struct {
	Due     int
	Purged  int
	Skipped int
	Failed  int
	// OldestDue is the deadline of the oldest record still waiting, which feeds
	// the GDPR purge backlog alert.
	OldestDue time.Time
}

// LifecycleService owns the account lifecycle User Service reacts to: bootstrap
// after identity verification (WF-USER-001), anonymization after account
// deletion (WF-USER-006) and the two-stage retention purge (WF-USER-007).
//
// It publishes nothing: Auth owns the deletion fact and emits it to every
// consumer directly (ADR-006).
type LifecycleService struct {
	profiles    port.ProfileRepository
	addresses   port.AddressRepository
	preferences port.PreferencesRepository
	deletions   port.DeletionRepository
	cache       port.AddressCache
	tx          port.TxManager
	clock       clock.Clock
	logger      *zap.Logger
}

// NewLifecycleService wires the lifecycle use cases.
func NewLifecycleService(
	profiles port.ProfileRepository,
	addresses port.AddressRepository,
	preferences port.PreferencesRepository,
	deletions port.DeletionRepository,
	cache port.AddressCache,
	tx port.TxManager,
	clk clock.Clock,
	logger *zap.Logger,
) *LifecycleService {
	return &LifecycleService{
		profiles:    profiles,
		addresses:   addresses,
		preferences: preferences,
		deletions:   deletions,
		cache:       cache,
		tx:          tx,
		clock:       clk,
		logger:      logger,
	}
}

// Bootstrap creates the empty profile and the default preferences of a verified
// account. The profile id is the user id Auth published (USER-INV-006), and
// both inserts are idempotent, so a redelivery never overwrites an edit the
// owner already made (USER-BR-005).
func (s *LifecycleService) Bootstrap(ctx context.Context, userID uuid.UUID, verifiedAt time.Time) (BootstrapResult, error) {
	now := s.clock.Now()
	if verifiedAt.IsZero() {
		verifiedAt = now
	}

	var result BootstrapResult
	err := s.tx.WithinTx(ctx, func(txCtx context.Context) error {
		deleted, err := s.deletions.ExistsForUser(txCtx, userID)
		if err != nil {
			return err
		}
		if deleted {
			result.SkippedDeleted = true
			return nil
		}

		profileCreated, err := s.profiles.Insert(txCtx, entity.NewProfile(userID, verifiedAt))
		if err != nil {
			return err
		}
		result.ProfileCreated = profileCreated

		preferencesCreated, err := s.preferences.Insert(txCtx, entity.NewDefaultPreferences(userID, verifiedAt))
		if err != nil {
			return err
		}
		result.PreferencesCreated = preferencesCreated
		return nil
	})
	if err != nil {
		return BootstrapResult{}, err
	}

	if result.SkippedDeleted {
		// Out-of-order delivery or a replay of a very old event. Retrying would
		// not help, but an operator should see it (WF-USER-001).
		s.logger.Warn("bootstrap skipped for a deleted user",
			zap.String("user_id", userID.String()))
	}
	return result, nil
}

// Anonymize records the retention deadline Auth decided, strips the profile PII
// and hard-deletes the address book and the preferences, all in one transaction
// (USER-BR-006). A duplicate delivery is a no-op and never extends the deadline.
func (s *LifecycleService) Anonymize(ctx context.Context, userID uuid.UUID, deletedAt, hardDeleteAfter time.Time, reason string) (AnonymizationResult, error) {
	parsedReason, err := vo.ParseDeletionReason(reason)
	if err != nil {
		return AnonymizationResult{}, apperr.ErrValidation.
			WithDetail("Unsupported deletion reason.").
			WithCause(err)
	}

	now := s.clock.Now()
	deletion, err := entity.NewDeletion(userID, deletedAt, hardDeleteAfter, parsedReason, now)
	if err != nil {
		return AnonymizationResult{}, err
	}

	var result AnonymizationResult
	err = s.tx.WithinTx(ctx, func(txCtx context.Context) error {
		created, err := s.deletions.Insert(txCtx, deletion)
		if err != nil {
			return err
		}
		if !created {
			result.AlreadyProcessed = true
			return nil
		}

		profile, err := s.profiles.GetForUpdate(txCtx, userID)
		switch {
		case err == nil:
			profile.Anonymize(now)
			if err := s.profiles.Update(txCtx, profile); err != nil {
				return err
			}
			result.ProfileAnonymized = true
		case errors.Is(err, apperr.ErrProfileNotFound):
			// The deletion arrived before the bootstrap, or the profile was
			// already purged. The retention record alone keeps the lifecycle
			// terminal.
		default:
			return err
		}

		deletedAddresses, err := s.addresses.DeleteAllForUser(txCtx, userID)
		if err != nil {
			return err
		}
		result.AddressesDeleted = deletedAddresses

		deletedPreferences, err := s.preferences.DeleteForUser(txCtx, userID)
		if err != nil {
			return err
		}
		result.PreferencesDeleted = deletedPreferences
		return nil
	})
	if err != nil {
		return AnonymizationResult{}, err
	}

	if !result.AlreadyProcessed {
		// The cached list is now a copy of data that no longer exists.
		if err := s.cache.Invalidate(ctx, userID); err != nil {
			s.logger.Error("address cache invalidation failed after anonymization",
				zap.String("user_id", userID.String()),
				zap.Error(err),
			)
		}
	}
	return result, nil
}

// PurgeDue hard-deletes the profiles whose retention window has elapsed. Each
// record is claimed and processed in its own transaction, so a crash between
// records resumes safely and two replicas never purge the same row
// (WF-USER-007).
func (s *LifecycleService) PurgeDue(ctx context.Context, limit int) (PurgeResult, error) {
	if limit <= 0 {
		limit = defaultPurgeBatch
	}
	now := s.clock.Now()

	due, err := s.deletions.ListDue(ctx, now, limit)
	if err != nil {
		return PurgeResult{}, err
	}

	result := PurgeResult{Due: len(due)}
	if len(due) > 0 {
		result.OldestDue = due[0].HardDeleteAfter
	}

	for _, record := range due {
		if ctx.Err() != nil {
			return result, ctx.Err()
		}

		purged, err := s.purgeOne(ctx, record.ID, now)
		switch {
		case err != nil:
			// One bad record must not stop the batch; the next pass retries it.
			result.Failed++
			s.logger.Error("retention purge failed for a record",
				zap.String("deletion_id", record.ID.String()),
				zap.Error(err),
			)
		case purged:
			result.Purged++
		default:
			// Claimed by another replica, already purged, or not due after the
			// recheck under the lock.
			result.Skipped++
		}
	}
	return result, nil
}

// defaultPurgeBatch matches the batch size documented for the worker.
const defaultPurgeBatch = 100

// purgeOne claims one retention record and deletes the profile together with
// the record itself.
func (s *LifecycleService) purgeOne(ctx context.Context, deletionID uuid.UUID, now time.Time) (bool, error) {
	purged := false
	err := s.tx.WithinTx(ctx, func(txCtx context.Context) error {
		record, err := s.deletions.ClaimDue(txCtx, deletionID, now)
		if err != nil {
			return err
		}
		if record == nil {
			return nil
		}
		// The deadline is rechecked while the row is locked: never delete early
		// (USER-BR-007).
		if !record.IsDue(now) {
			return nil
		}

		if _, err := s.profiles.HardDelete(txCtx, record.UserID); err != nil {
			return err
		}
		if err := s.deletions.Delete(txCtx, record.ID); err != nil {
			return err
		}
		purged = true
		return nil
	})
	if err != nil {
		return false, err
	}
	return purged, nil
}
