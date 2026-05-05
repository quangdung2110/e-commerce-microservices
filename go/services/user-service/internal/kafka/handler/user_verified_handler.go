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

// UserVerifiedHandler handles USER_VERIFIED_SUCCESS events
// This handler creates profile and preferences for new users in an idempotent manner
type UserVerifiedHandler struct {
	profileRepo  port.ProfileRepository
	prefsRepo   port.PreferencesRepository
	outboxRepo  port.OutboxRepository
	addressRepo port.AddressRepository
	db          *pgxpool.Pool
	logger      *zap.Logger
}

// NewUserVerifiedHandler creates a new UserVerifiedHandler
func NewUserVerifiedHandler(
	profileRepo port.ProfileRepository,
	prefsRepo port.PreferencesRepository,
	outboxRepo port.OutboxRepository,
	addressRepo port.AddressRepository,
	db *pgxpool.Pool,
	logger *zap.Logger,
) *UserVerifiedHandler {
	return &UserVerifiedHandler{
		profileRepo:  profileRepo,
		prefsRepo:   prefsRepo,
		outboxRepo:  outboxRepo,
		addressRepo: addressRepo,
		db:          db,
		logger:      logger,
	}
}

// Handle processes USER_VERIFIED_SUCCESS events
// This is idempotent - using ON CONFLICT DO NOTHING in queries
func (h *UserVerifiedHandler) Handle(ctx context.Context, msg kafka.Message) error {
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

	// Only process USER_VERIFIED_SUCCESS events
	if !authEvent.IsUserVerifiedSuccess() {
		h.logger.Debug("Skipping non-VERIFIED_SUCCESS event",
			zap.String("event_type", string(authEvent.EventType)),
		)
		return nil
	}

	userID := authEvent.Payload.UserID
	h.logger.Info("Processing USER_VERIFIED_SUCCESS event",
		zap.String("user_id", userID.String()),
		zap.String("event_id", authEvent.EventID.String()),
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

	// 1. Create profile (idempotent - ON CONFLICT DO NOTHING)
	if err = h.createProfile(ctx, tx, userID); err != nil {
		return fmt.Errorf("failed to create profile: %w", err)
	}

	// 2. Create default preferences (idempotent - ON CONFLICT DO NOTHING)
	if err = h.createPreferences(ctx, tx, userID); err != nil {
		return fmt.Errorf("failed to create preferences: %w", err)
	}

	// Commit transaction
	if err = tx.Commit(ctx); err != nil {
		return fmt.Errorf("failed to commit transaction: %w", err)
	}

	h.logger.Info("Successfully created profile and preferences",
		zap.String("user_id", userID.String()),
	)

	return nil
}

// createProfile creates a profile within a transaction (idempotent)
func (h *UserVerifiedHandler) createProfile(ctx context.Context, tx pgx.Tx, userID uuid.UUID) error {
	// Check if profile already exists
	exists, err := h.profileRepo.Exists(ctx, userID)
	if err != nil {
		return err
	}
	if exists {
		h.logger.Debug("Profile already exists, skipping",
			zap.String("user_id", userID.String()),
		)
		return nil
	}

	// Create profile entity
	profile := entity.NewProfile(userID)

	// Create profile using the repository with transaction
	if err := h.profileRepo.CreateTx(ctx, tx, profile); err != nil {
		return fmt.Errorf("failed to create profile: %w", err)
	}

	h.logger.Info("Created profile for new user",
		zap.String("user_id", userID.String()),
	)

	return nil
}

// createPreferences creates default preferences within a transaction (idempotent)
func (h *UserVerifiedHandler) createPreferences(ctx context.Context, tx pgx.Tx, userID uuid.UUID) error {
	// Check if preferences already exist
	exists, err := h.prefsRepo.Exists(ctx, userID)
	if err != nil {
		return err
	}
	if exists {
		h.logger.Debug("Preferences already exist, skipping",
			zap.String("user_id", userID.String()),
		)
		return nil
	}

	// Create preferences entity
	prefs := entity.NewPreferences(userID)

	// Create preferences using the repository with transaction
	if err := h.prefsRepo.CreateTx(ctx, tx, prefs); err != nil {
		return fmt.Errorf("failed to create preferences: %w", err)
	}

	h.logger.Info("Created default preferences for new user",
		zap.String("user_id", userID.String()),
	)

	return nil
}
