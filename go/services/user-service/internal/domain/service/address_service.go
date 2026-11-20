package service

import (
	"context"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/ecommerce/user-service/internal/common/clock"
	"github.com/ecommerce/user-service/internal/common/constants"
	apperr "github.com/ecommerce/user-service/internal/common/errors"
	"github.com/ecommerce/user-service/internal/common/logging"
	"github.com/ecommerce/user-service/internal/domain/entity"
	"github.com/ecommerce/user-service/internal/domain/port"
)

// AddressService implements WF-USER-003 and WF-USER-004: the owner manages
// their address book and checkout reads it with low latency.
//
// PostgreSQL is the source of truth. Redis only holds a TTL-bound copy of the
// list, is invalidated after every committed mutation and is bypassed whenever
// it cannot answer (ADR-002).
type AddressService struct {
	addresses port.AddressRepository
	profiles  port.ProfileRepository
	cache     port.AddressCache
	tx        port.TxManager
	clock     clock.Clock
	logger    *zap.Logger
}

// NewAddressService wires the address use cases.
func NewAddressService(
	addresses port.AddressRepository,
	profiles port.ProfileRepository,
	cache port.AddressCache,
	tx port.TxManager,
	clk clock.Clock,
	logger *zap.Logger,
) *AddressService {
	return &AddressService{
		addresses: addresses,
		profiles:  profiles,
		cache:     cache,
		tx:        tx,
		clock:     clk,
		logger:    logger,
	}
}

// List returns the address book of the owner, default first and newest after.
//
// A cache hit is served as-is. On a miss, PostgreSQL answers and the result is
// cached; an empty result is only cached once the profile is known to exist, so
// a lookup for an unknown user cannot poison the cache with a 200.
//
// The cache epoch is read before PostgreSQL is: a mutation that commits and
// invalidates while this read is in flight advances the epoch, and the fill
// below is then refused instead of caching the list this reader already knows
// is stale (ADR-002).
func (s *AddressService) List(ctx context.Context, userID uuid.UUID) ([]*entity.Address, error) {
	if cached, found := s.cache.Get(ctx, userID); found {
		return cached, nil
	}

	epoch := s.cache.Epoch(ctx, userID)

	addresses, err := s.addresses.ListByUser(ctx, userID)
	if err != nil {
		return nil, err
	}
	if len(addresses) == 0 {
		// An empty address book and a missing or deleted profile are
		// indistinguishable from the address table alone, and the contract
		// separates them.
		if _, err := s.profiles.GetActive(ctx, userID); err != nil {
			return nil, err
		}
	}

	if err := s.cache.Put(ctx, userID, addresses, epoch); err != nil {
		logging.FromContext(ctx).Warn("address cache could not be filled", zap.Error(err))
	}
	return addresses, nil
}

// Get resolves one address of one owner. It never reads the cache: the internal
// checkout snapshot and every mutation need the authoritative row.
func (s *AddressService) Get(ctx context.Context, userID, addressID uuid.UUID) (*entity.Address, error) {
	return s.addresses.GetOwned(ctx, userID, addressID)
}

// Create adds an address. The first address of a profile becomes the default
// (USER-BR-002); the per-user lock is what keeps two concurrent creates from
// both claiming it.
func (s *AddressService) Create(ctx context.Context, userID uuid.UUID, input entity.AddressInput) (*entity.Address, error) {
	now := s.clock.Now()

	var address *entity.Address
	err := s.tx.WithinTx(ctx, func(txCtx context.Context) error {
		if err := s.addresses.LockUser(txCtx, userID); err != nil {
			return err
		}
		// The profile must exist and must not be in its retention window: an
		// address book without an owner would be orphaned data, and one added
		// after the account was deleted would put PII back after the anonymization
		// removed it (USER-INV-005). The anonymization takes the same per-user
		// lock, so it cannot slip in between this check and the insert.
		if _, err := s.profiles.GetActive(txCtx, userID); err != nil {
			return err
		}

		count, err := s.addresses.CountByUser(txCtx, userID)
		if err != nil {
			return err
		}
		if count >= constants.AddressBookLimit {
			return apperr.ErrInvalidAddress.WithDetail("Address book đã đạt giới hạn số địa chỉ.")
		}

		created, err := entity.NewAddress(userID, input, count == 0, now)
		if err != nil {
			return err
		}
		address = created
		return s.addresses.Insert(txCtx, created)
	})
	if err != nil {
		return nil, err
	}

	s.invalidate(ctx, userID)
	return address, nil
}

// Replace overwrites one address of the owner. The default flag is not part of
// the payload and stays untouched.
func (s *AddressService) Replace(ctx context.Context, userID, addressID uuid.UUID, input entity.AddressInput) (*entity.Address, error) {
	now := s.clock.Now()

	var address *entity.Address
	err := s.tx.WithinTx(ctx, func(txCtx context.Context) error {
		if err := s.addresses.LockUser(txCtx, userID); err != nil {
			return err
		}

		current, err := s.addresses.GetOwned(txCtx, userID, addressID)
		if err != nil {
			return err
		}
		if err := current.Replace(input, now); err != nil {
			return err
		}
		address = current
		return s.addresses.Update(txCtx, current)
	})
	if err != nil {
		return nil, err
	}

	s.invalidate(ctx, userID)
	return address, nil
}

// SetDefault promotes one address. Clearing the previous default and setting
// the new one happen in the same transaction, so at most one default exists at
// any time (USER-BR-003). Repeating the call is a no-op.
func (s *AddressService) SetDefault(ctx context.Context, userID, addressID uuid.UUID) (*entity.Address, error) {
	var (
		address *entity.Address
		changed bool
	)
	err := s.tx.WithinTx(ctx, func(txCtx context.Context) error {
		if err := s.addresses.LockUser(txCtx, userID); err != nil {
			return err
		}

		current, err := s.addresses.GetOwned(txCtx, userID, addressID)
		if err != nil {
			return err
		}
		address = current
		if current.IsDefault {
			return nil
		}

		if err := s.addresses.ClearDefault(txCtx, userID, addressID); err != nil {
			return err
		}
		updatedAt, err := s.addresses.SetDefault(txCtx, userID, addressID)
		if err != nil {
			return err
		}

		current.IsDefault = true
		current.UpdatedAt = updatedAt
		changed = true
		return nil
	})
	if err != nil {
		return nil, err
	}

	if changed {
		s.invalidate(ctx, userID)
	}
	return address, nil
}

// Delete hard-deletes an address. The current default is refused with 409 while
// other addresses exist: the owner promotes a replacement first (USER-BR-004).
// The only address of a book may be deleted even though it is the default, so a
// book can be emptied again. Orders keep their own snapshot, so nothing
// downstream changes (USER-INV-004).
func (s *AddressService) Delete(ctx context.Context, userID, addressID uuid.UUID) error {
	err := s.tx.WithinTx(ctx, func(txCtx context.Context) error {
		if err := s.addresses.LockUser(txCtx, userID); err != nil {
			return err
		}

		current, err := s.addresses.GetOwned(txCtx, userID, addressID)
		if err != nil {
			return err
		}
		// Counted under the per-user lock, so a concurrent create cannot change
		// the answer between this check and the delete.
		count, err := s.addresses.CountByUser(txCtx, userID)
		if err != nil {
			return err
		}
		if err := current.EnsureDeletable(count); err != nil {
			return err
		}
		return s.addresses.Delete(txCtx, userID, addressID)
	})
	if err != nil {
		return err
	}

	s.invalidate(ctx, userID)
	return nil
}

// invalidate drops the cached list after a committed mutation. A failure here
// leaves a stale list until the TTL expires; it is reported for alerting but
// never rolls the mutation back (ADR-002).
func (s *AddressService) invalidate(ctx context.Context, userID uuid.UUID) {
	if err := s.cache.Invalidate(ctx, userID); err != nil {
		logging.FromContext(ctx).Error("address cache invalidation failed",
			zap.String("user_id", userID.String()),
			zap.Error(err),
		)
	}
}
