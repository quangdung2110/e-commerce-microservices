package service

import (
	"context"
	"errors"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"go.uber.org/zap"
	"golang.org/x/sync/singleflight"

	"user-service/internal/domain/entity"
	"user-service/internal/domain/port"
	"user-service/internal/domain/vo"
)

var (
	ErrAddressNotFound         = errors.New("address not found")
	ErrAddressNotOwned         = errors.New("address does not belong to user")
	ErrCannotDeleteOnlyAddress = errors.New("cannot delete the only default address")
	ErrCannotDeleteOnlyDefault = errors.New("cannot delete the only default address")
)

// AddressService handles address business logic with caching
type AddressService struct {
	repo    port.AddressRepository
	cache   port.AddressCache
	db      *pgxpool.Pool
	logger  *zap.Logger
	sfGroup singleflight.Group
}

// NewAddressService creates a new AddressService
func NewAddressService(repo port.AddressRepository, cache port.AddressCache, db *pgxpool.Pool, logger *zap.Logger) *AddressService {
	return &AddressService{
		repo:   repo,
		cache:  cache,
		db:     db,
		logger: logger,
	}
}

// GetByUserID retrieves addresses for a user with cache-aside pattern
func (s *AddressService) GetByUserID(ctx context.Context, userID uuid.UUID) ([]*entity.Address, error) {
	// Try cache first
	cached, err := s.cache.Get(ctx, userID)
	if err != nil {
		s.logger.Warn("cache get failed, falling back to DB", zap.Error(err))
	}
	if cached != nil {
		s.logger.Debug("cache hit for addresses", zap.String("user_id", userID.String()))
		return cached, nil
	}

	// Use singleflight to prevent thundering herd
	sfKey := userID.String()

	result, err, _ := s.sfGroup.Do(sfKey, func() (interface{}, error) {
		// Double-check cache (another goroutine might have populated it)
		cached, err := s.cache.Get(ctx, userID)
		if err == nil && cached != nil {
			return cached, nil
		}

		// Query DB
		addresses, err := s.repo.GetByUserID(ctx, userID)
		if err != nil {
			return nil, err
		}

		// Cache result
		if err := s.cache.Set(ctx, userID, addresses); err != nil {
			s.logger.Warn("cache set failed", zap.Error(err))
		}

		return addresses, nil
	})

	if err != nil {
		return nil, err
	}

	return result.([]*entity.Address), nil
}

// GetByID retrieves a single address by ID
func (s *AddressService) GetByID(ctx context.Context, addressID uuid.UUID) (*entity.Address, error) {
	addr, err := s.repo.GetByID(ctx, addressID)
	if err != nil {
		return nil, err
	}
	if addr == nil {
		return nil, ErrAddressNotFound
	}
	return addr, nil
}

// Create creates a new address with auto-default logic
func (s *AddressService) Create(ctx context.Context, userID uuid.UUID, recipientName, phone, addressLine, wardID, districtID, cityID string) (*entity.Address, error) {
	// Check if this is the first address for the user
	existingAddresses, err := s.repo.GetByUserID(ctx, userID)
	if err != nil {
		return nil, err
	}

	isFirstAddress := len(existingAddresses) == 0

	// Create new address
	addr, err := entity.NewAddress(userID, recipientName, phone, addressLine, wardID, districtID, cityID)
	if err != nil {
		return nil, err
	}

	// Set as default if first address
	if isFirstAddress {
		addr.SetAsDefault()
	}

	// Save to database
	if err := s.repo.Create(ctx, addr); err != nil {
		return nil, err
	}

	// Invalidate cache
	if err := s.cache.Delete(ctx, userID); err != nil {
		s.logger.Warn("cache delete failed after create", zap.Error(err))
	}

	s.logger.Info("address created",
		zap.String("address_id", addr.ID.String()),
		zap.String("user_id", userID.String()),
		zap.Bool("is_default", addr.IsDefault))

	return addr, nil
}

// Update updates an existing address
func (s *AddressService) Update(ctx context.Context, userID, addressID uuid.UUID, recipientName, phone, addressLine, wardID, districtID, cityID string) (*entity.Address, error) {
	// Get existing address
	addr, err := s.repo.GetByID(ctx, addressID)
	if err != nil {
		return nil, err
	}
	if addr == nil {
		return nil, ErrAddressNotFound
	}

	// Verify ownership
	if !addr.BelongsTo(userID) {
		return nil, ErrAddressNotOwned
	}

	// Update fields
	if err := addr.Update(recipientName, phone, addressLine, wardID, districtID, cityID); err != nil {
		return nil, err
	}

	// Save to database
	if err := s.repo.Update(ctx, addr); err != nil {
		return nil, err
	}

	// Invalidate cache
	if err := s.cache.Delete(ctx, userID); err != nil {
		s.logger.Warn("cache delete failed after update", zap.Error(err))
	}

	s.logger.Info("address updated",
		zap.String("address_id", addr.ID.String()),
		zap.String("user_id", userID.String()))

	return addr, nil
}

// Delete deletes an address with business rules
func (s *AddressService) Delete(ctx context.Context, userID, addressID uuid.UUID) error {
	// Get existing address
	addr, err := s.repo.GetByID(ctx, addressID)
	if err != nil {
		return err
	}
	if addr == nil {
		return ErrAddressNotFound
	}

	// Verify ownership
	if !addr.BelongsTo(userID) {
		return ErrAddressNotOwned
	}

	// Check business rules
	count, err := s.repo.CountByUserID(ctx, userID)
	if err != nil {
		return err
	}

	// If this is the only address and it's default, reject deletion
	if count == 1 && addr.IsDefault {
		return ErrCannotDeleteOnlyDefault
	}

	// If deleting default address and there are others, promote oldest
	if addr.IsDefault && count > 1 {
		tx, err := s.db.Begin(ctx)
		if err != nil {
			return err
		}
		defer tx.Rollback(ctx)

		// Delete current default address
		if err := s.repo.DeleteTx(ctx, tx, addressID); err != nil {
			return err
		}

		// Promote oldest remaining address
		_, err = s.repo.PromoteOldestToDefault(ctx, tx, userID, addressID)
		if err != nil {
			return err
		}

		if err := tx.Commit(ctx); err != nil {
			return err
		}
	} else {
		// Simple delete for non-default addresses
		if err := s.repo.Delete(ctx, addressID); err != nil {
			return err
		}
	}

	// Invalidate cache
	if err := s.cache.Delete(ctx, userID); err != nil {
		s.logger.Warn("cache delete failed after delete", zap.Error(err))
	}

	s.logger.Info("address deleted",
		zap.String("address_id", addressID.String()),
		zap.String("user_id", userID.String()))

	return nil
}

// SetDefault sets an address as default
func (s *AddressService) SetDefault(ctx context.Context, userID, addressID uuid.UUID) (*entity.Address, error) {
	// Get existing address
	addr, err := s.repo.GetByID(ctx, addressID)
	if err != nil {
		return nil, err
	}
	if addr == nil {
		return nil, ErrAddressNotFound
	}

	// Verify ownership
	if !addr.BelongsTo(userID) {
		return nil, ErrAddressNotOwned
	}

	// Check if already default
	if addr.IsDefault {
		return addr, nil // No-op
	}

	// Set as default (handles transaction internally)
	if err := s.repo.SetDefault(ctx, userID, addressID); err != nil {
		return nil, err
	}

	// Refresh address
	addr, err = s.repo.GetByID(ctx, addressID)
	if err != nil {
		return nil, err
	}

	// Invalidate cache
	if err := s.cache.Delete(ctx, userID); err != nil {
		s.logger.Warn("cache delete failed after set default", zap.Error(err))
	}

	s.logger.Info("address set as default",
		zap.String("address_id", addressID.String()),
		zap.String("user_id", userID.String()))

	return addr, nil
}

// CreateAddressWithTx creates address within a transaction
func (s *AddressService) CreateAddressWithTx(ctx context.Context, tx pgx.Tx, userID uuid.UUID, recipientName, phone, addressLine, wardID, districtID, cityID string) (*entity.Address, error) {
	// Check if this is the first address for the user
	existingAddresses, err := s.repo.GetByUserID(ctx, userID)
	if err != nil {
		return nil, err
	}

	isFirstAddress := len(existingAddresses) == 0

	// Create new address
	addr, err := entity.NewAddress(userID, recipientName, phone, addressLine, wardID, districtID, cityID)
	if err != nil {
		return nil, err
	}

	// Set as default if first address
	if isFirstAddress {
		addr.SetAsDefault()
	}

	// Save to database within transaction
	if err := s.repo.CreateTx(ctx, tx, addr); err != nil {
		return nil, err
	}

	// Invalidate cache
	if err := s.cache.Delete(ctx, userID); err != nil {
		s.logger.Warn("cache delete failed after create", zap.Error(err))
	}

	return addr, nil
}

// ValidateAddressFields validates address fields
func ValidateAddressFields(recipientName, phone, addressLine, wardID, districtID, cityID string) error {
	if recipientName == "" {
		return ErrRecipientNameRequired
	}

	if _, err := vo.NewPhoneNumber(phone); err != nil {
		return err
	}

	if addressLine == "" {
		return ErrAddressLineRequired
	}

	if wardID == "" || districtID == "" || cityID == "" {
		return ErrLocationRequired
	}

	return nil
}

// Errors for validation
var (
	ErrRecipientNameRequired = errors.New("recipient name is required")
	ErrAddressLineRequired  = errors.New("address line is required")
	ErrLocationRequired     = errors.New("ward, district, and city IDs are required")
)
