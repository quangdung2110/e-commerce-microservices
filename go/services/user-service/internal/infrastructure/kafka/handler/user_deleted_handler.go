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
	"user-service/internal/infrastructure/kafka/event"
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
//   - Insert outbox event ACCOUNT_DELETED for downstream services
//
// Stage 2: Hard delete (handled by Compliance Worker after retention window expires)
type UserDeletedHandler struct {
	profileRepo  port.ProfileRepository
	prefsRepo    port.PreferencesRepository
	addressRepo  port.AddressRepository
	deletionRepo port.UserDeletionRepository
	outboxRepo   port.OutboxRepository
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
	outboxRepo port.OutboxRepository,
	cache port.AddressCache,
	db *pgxpool.Pool,
	logger *zap.Logger,
) *UserDeletedHandler {
	return &UserDeletedHandler{
		profileRepo:  profileRepo,
		prefsRepo:    prefsRepo,
		addressRepo:  addressRepo,
		deletionRepo: deletionRepo,
		outboxRepo:   outboxRepo,
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
// 5. Insert outbox_events with ACCOUNT_DELETED for downstream services
func (h *UserDeletedHandler) Handle(ctx context.Context, msg kafka.Message) error {
	var authEvent event.AuthEvent
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

	// 5. Insert outbox event for CDC to publish ACCOUNT_DELETED
	if err = h.createOutboxEvent(ctx, tx, userID, reason, retentionWindow); err != nil {
		return fmt.Errorf("failed to create outbox event: %w", err)
	}

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

// createOutboxEvent creates an outbox event for CDC notification to downstream services
func (h *UserDeletedHandler) createOutboxEvent(ctx context.Context, tx pgx.Tx, userID uuid.UUID, reason string, retentionWindow time.Time) error {
	outboxEvent, err := entity.NewAccountDeletedEvent(userID, reason, retentionWindow)
	if err != nil {
		return fmt.Errorf("failed to create outbox event: %w", err)
	}

	if err := h.outboxRepo.CreateTx(ctx, tx, outboxEvent); err != nil {
		return fmt.Errorf("failed to create outbox event: %w", err)
	}

	h.logger.Debug("Created ACCOUNT_DELETED outbox event",
		zap.String("user_id", userID.String()),
		zap.String("event_id", outboxEvent.EventID.String()),
	)

	return nil
}
