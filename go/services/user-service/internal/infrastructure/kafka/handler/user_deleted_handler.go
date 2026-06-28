package handler

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/segmentio/kafka-go"
	"go.uber.org/zap"

	"user-service/internal/domain/entity"
	"user-service/internal/domain/port"
	userevent "user-service/internal/infrastructure/kafka"
	kafkaevent "user-service/internal/infrastructure/kafka/event"
)

const (
	// DefaultRetentionDays is the default retention window for GDPR compliance (90 days)
	DefaultRetentionDays = 90
)

// UserDeletedHandler handles USER_DELETED events for GDPR 2-stage compliance
// Stage 1: Anonymize PII (when USER_DELETED event arrives from Kafka)
//   - Insert into user_deletions (Event Store)
//   - Anonymize user_profiles (clear PII, keep record)
//   - Delete user_addresses
//   - Delete user_preferences
//   - Publish ACCOUNT_DELETED event to Kafka for downstream services
//
// Stage 2: Hard delete (handled by Compliance Worker after retention window expires)
type UserDeletedHandler struct {
	profileRepo  port.ProfileRepository
	prefsRepo    port.PreferencesRepository
	addressRepo  port.AddressRepository
	deletionRepo port.UserDeletionRepository
	producer     *userevent.UserEventProducer
	cache        port.AddressCache
	db           *pgxpool.Pool
	logger       *zap.Logger
}

// NewUserDeletedHandler creates a new UserDeletedHandler
func NewUserDeletedHandler(
	profileRepo port.ProfileRepository,
	prefsRepo port.PreferencesRepository,
	addressRepo port.AddressRepository,
	deletionRepo port.UserDeletionRepository,
	producer *userevent.UserEventProducer,
	cache port.AddressCache,
	db *pgxpool.Pool,
	logger *zap.Logger,
) *UserDeletedHandler {
	return &UserDeletedHandler{
		profileRepo:  profileRepo,
		prefsRepo:    prefsRepo,
		addressRepo:  addressRepo,
		deletionRepo: deletionRepo,
		producer:     producer,
		cache:        cache,
		db:           db,
		logger:       logger,
	}
}

// Handle implements Stage 1 of GDPR 2-phase deletion workflow:
// 1. INSERT into user_deletions (Event Store)
// 2. Anonymize user_profiles (PII removed, record remains for referential integrity)
// 3. Delete user_addresses (Order Service has already snapshotted)
// 4. Delete user_preferences
// 5. Publish ACCOUNT_DELETED event to Kafka for downstream services
func (h *UserDeletedHandler) Handle(ctx context.Context, msg kafka.Message) error {
	var authEvent kafkaevent.AuthEvent
	if err := json.Unmarshal(msg.Value, &authEvent); err != nil {
		h.logger.Error("Failed to unmarshal auth event",
			zap.Error(err),
			zap.ByteString("message", msg.Value),
		)
		return nil
	}

	if !authEvent.IsUserDeleted() {
		h.logger.Debug("Skipping non-DELETED event",
			zap.String("event_type", string(authEvent.EventType)),
		)
		return nil
	}

	userID := authEvent.Payload.UserID
	reason := authEvent.Payload.Reason
	h.logger.Info("Processing USER_DELETED event (GDPR Stage 1: Anonymize)",
		zap.String("user_id", userID.String()),
		zap.String("event_id", authEvent.EventID.String()),
		zap.String("reason", reason),
	)

	// Calculate retention window (default 90 days if not specified)
	retentionWindow := authEvent.Payload.RetentionWindowExpiresAt
	if retentionWindow.IsZero() {
		retentionWindow = time.Now().AddDate(0, 0, DefaultRetentionDays)
	}

	tx, err := h.db.Begin(ctx)
	if err != nil {
		return fmt.Errorf("failed to begin transaction: %w", err)
	}
	defer func() {
		if err != nil {
			tx.Rollback(ctx)
		}
	}()

	// 1. Insert into user_deletions (Event Store for Stage 2)
	if err = h.createDeletionRecord(ctx, tx, userID, retentionWindow, reason); err != nil {
		return fmt.Errorf("failed to create deletion record: %w", err)
	}

	// 2. Anonymize user_profiles (PII removed, record remains for referential integrity)
	if err = h.anonymizeProfile(ctx, tx, userID); err != nil {
		return fmt.Errorf("failed to anonymize profile: %w", err)
	}

	// 3. Delete user_addresses
	if err = h.deleteAddresses(ctx, tx, userID); err != nil {
		return fmt.Errorf("failed to delete addresses: %w", err)
	}

	// 4. Delete user_preferences
	if err = h.deletePreferences(ctx, tx, userID); err != nil {
		return fmt.Errorf("failed to delete preferences: %w", err)
	}

	if err = tx.Commit(ctx); err != nil {
		return fmt.Errorf("failed to commit transaction: %w", err)
	}

	// 5. Publish ACCOUNT_DELETED event to Kafka for downstream services
	if err = h.producer.PublishAccountDeleted(ctx, userevent.AccountDeletedPayload{
		UserID:                   userID,
		DeletedAt:                time.Now().Format(time.RFC3339),
		Reason:                   reason,
		RetentionWindowExpiresAt: retentionWindow.Format(time.RFC3339),
	}); err != nil {
		h.logger.Error("Failed to publish ACCOUNT_DELETED event",
			zap.String("user_id", userID.String()),
			zap.Error(err),
		)
	}

	// Invalidate cache after successful deletion
	if err = h.cache.Delete(ctx, userID); err != nil {
		h.logger.Warn("Failed to invalidate cache after user deletion",
			zap.String("user_id", userID.String()),
			zap.Error(err),
		)
	}

	h.logger.Info("Successfully completed GDPR Stage 1 (Anonymize)",
		zap.String("user_id", userID.String()),
		zap.String("reason", reason),
		zap.Time("retention_window_expires", retentionWindow),
	)

	return nil
}

// createDeletionRecord inserts a record into user_deletions for Stage 2 processing
func (h *UserDeletedHandler) createDeletionRecord(ctx context.Context, tx pgx.Tx, userID uuid.UUID, retentionWindow time.Time, reason string) error {
	deletion := entity.NewUserDeletion(userID, retentionWindow, reason)
	if err := h.deletionRepo.CreateTx(ctx, tx, deletion); err != nil {
		return fmt.Errorf("failed to create deletion record: %w", err)
	}

	h.logger.Debug("Created deletion record",
		zap.String("user_id", userID.String()),
		zap.Time("retention_window_expires", retentionWindow),
	)

	return nil
}

// anonymizeProfile clears PII from user_profiles but keeps the record for referential integrity
func (h *UserDeletedHandler) anonymizeProfile(ctx context.Context, tx pgx.Tx, userID uuid.UUID) error {
	if err := h.profileRepo.Anonymize(ctx, tx, userID); err != nil {
		return fmt.Errorf("failed to anonymize profile: %w", err)
	}

	h.logger.Debug("Anonymized profile for user",
		zap.String("user_id", userID.String()),
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


