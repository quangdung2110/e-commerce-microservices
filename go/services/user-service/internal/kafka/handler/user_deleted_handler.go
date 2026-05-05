package handler

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/segmentio/kafka-go"
	"go.uber.org/zap"

	"user-service/internal/domain/entity"
	"user-service/internal/domain/event"
	"user-service/internal/domain/port"
)

// UserDeletedHandler handles USER_DELETED events for GDPR compliance
// This handler performs hard delete of all user data in the correct order
type UserDeletedHandler struct {
	profileRepo  port.ProfileRepository
	prefsRepo   port.PreferencesRepository
	addressRepo port.AddressRepository
	outboxRepo  port.OutboxRepository
	cache       port.AddressCache
	db          *pgxpool.Pool
	logger      *zap.Logger
}

// NewUserDeletedHandler creates a new UserDeletedHandler
func NewUserDeletedHandler(
	profileRepo port.ProfileRepository,
	prefsRepo port.PreferencesRepository,
	addressRepo port.AddressRepository,
	outboxRepo port.OutboxRepository,
	cache port.AddressCache,
	db *pgxpool.Pool,
	logger *zap.Logger,
) *UserDeletedHandler {
	return &UserDeletedHandler{
		profileRepo: profileRepo,
		prefsRepo:  prefsRepo,
		addressRepo: addressRepo,
		outboxRepo: outboxRepo,
		cache:     cache,
		db:        db,
		logger:    logger,
	}
}

// Handle processes USER_DELETED events for GDPR account deletion
// Deletes in correct order: addresses -> preferences -> profile
// Then creates outbox event for CDC notification
func (h *UserDeletedHandler) Handle(ctx context.Context, msg kafka.Message) error {
	// Parse the auth event
	var authEvent event.AuthEvent
	if err := json.Unmarshal(msg.Value, &authEvent); err != nil {
		// Don't retry malformed messages
		h.logger.Error("Failed to unmarshal auth event",
			zap.Error(err),
			zap.ByteString("message", msg.Value),
		)
		return nil
	}

	// Only process USER_DELETED events
	if !authEvent.IsUserDeleted() {
		h.logger.Debug("Skipping non-DELETED event",
			zap.String("event_type", string(authEvent.EventType)),
		)
		return nil
	}

	userID := authEvent.Payload.UserID
	reason := authEvent.Payload.Reason
	h.logger.Info("Processing USER_DELETED event",
		zap.String("user_id", userID.String()),
		zap.String("event_id", authEvent.EventID.String()),
		zap.String("reason", reason),
	)

	// Execute in a transaction for atomicity
	tx, err := h.db.Begin(ctx)
	if err != nil {
		return fmt.Errorf("failed to begin transaction: %w", err)
	}
	defer func() {
		if err != nil {
			tx.Rollback(ctx)
		}
	}()

	// Delete in correct order (respecting foreign key constraints)
	// 1. Delete addresses first (FK to profile)
	if err = h.deleteAddresses(ctx, tx, userID); err != nil {
		return fmt.Errorf("failed to delete addresses: %w", err)
	}

	// 2. Delete preferences (FK to profile)
	if err = h.deletePreferences(ctx, tx, userID); err != nil {
		return fmt.Errorf("failed to delete preferences: %w", err)
	}

	// 3. Delete profile (last, as it's the parent table)
	if err = h.deleteProfile(ctx, tx, userID); err != nil {
		return fmt.Errorf("failed to delete profile: %w", err)
	}

	// 4. Create outbox event for CDC notification (USER_PROFILE_DELETED)
	if err = h.createOutboxEvent(ctx, tx, userID); err != nil {
		return fmt.Errorf("failed to create outbox event: %w", err)
	}

	// Commit transaction
	if err = tx.Commit(ctx); err != nil {
		return fmt.Errorf("failed to commit transaction: %w", err)
	}

	// Invalidate cache after successful deletion
	if err = h.cache.Delete(ctx, userID); err != nil {
		h.logger.Warn("Failed to invalidate cache after user deletion",
			zap.String("user_id", userID.String()),
			zap.Error(err),
		)
	}

	h.logger.Info("Successfully deleted all user data (GDPR)",
		zap.String("user_id", userID.String()),
		zap.String("reason", reason),
	)

	return nil
}

// deleteAddresses deletes all addresses for a user
func (h *UserDeletedHandler) deleteAddresses(ctx context.Context, tx pgx.Tx, userID uuid.UUID) error {
	if err := h.addressRepo.DeleteByUserIDTx(ctx, tx, userID); err != nil {
		return fmt.Errorf("failed to delete addresses: %w", err)
	}

	h.logger.Debug("Deleted addresses for user",
		zap.String("user_id", userID.String()),
	)

	return nil
}

// deletePreferences deletes preferences for a user
func (h *UserDeletedHandler) deletePreferences(ctx context.Context, tx pgx.Tx, userID uuid.UUID) error {
	if err := h.prefsRepo.DeleteTx(ctx, tx, userID); err != nil {
		return fmt.Errorf("failed to delete preferences: %w", err)
	}

	h.logger.Debug("Deleted preferences for user",
		zap.String("user_id", userID.String()),
	)

	return nil
}

// deleteProfile deletes the profile for a user
func (h *UserDeletedHandler) deleteProfile(ctx context.Context, tx pgx.Tx, userID uuid.UUID) error {
	if err := h.profileRepo.DeleteTx(ctx, tx, userID); err != nil {
		return fmt.Errorf("failed to delete profile: %w", err)
	}

	h.logger.Debug("Deleted profile for user",
		zap.String("user_id", userID.String()),
	)

	return nil
}

// createOutboxEvent creates an outbox event for CDC notification
func (h *UserDeletedHandler) createOutboxEvent(ctx context.Context, tx pgx.Tx, userID uuid.UUID) error {
	// Create outbox event using the entity function
	outboxEvent, err := entity.NewUserProfileDeletedEvent(userID)
	if err != nil {
		return fmt.Errorf("failed to create outbox event: %w", err)
	}

	// Create in transaction
	if err := h.outboxRepo.CreateTx(ctx, tx, outboxEvent); err != nil {
		return fmt.Errorf("failed to create outbox event: %w", err)
	}

	h.logger.Debug("Created outbox event for user deletion",
		zap.String("user_id", userID.String()),
		zap.String("event_id", outboxEvent.EventID.String()),
	)

	return nil
}
