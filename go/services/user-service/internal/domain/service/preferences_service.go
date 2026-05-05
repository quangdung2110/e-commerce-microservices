package service

import (
	"context"
	"errors"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"go.uber.org/zap"

	"user-service/internal/domain/entity"
	"user-service/internal/domain/port"
)

var (
	ErrPreferencesNotFound = errors.New("preferences not found")
)

// PreferencesService handles preferences business logic
type PreferencesService struct {
	prefsRepo   port.PreferencesRepository
	profileRepo port.ProfileRepository
	outboxRepo  port.OutboxRepository
	db          *pgxpool.Pool
	logger      *zap.Logger
}

// NewPreferencesService creates a new PreferencesService
func NewPreferencesService(
	prefsRepo port.PreferencesRepository,
	profileRepo port.ProfileRepository,
	outboxRepo port.OutboxRepository,
	db *pgxpool.Pool,
	logger *zap.Logger,
) *PreferencesService {
	return &PreferencesService{
		prefsRepo:   prefsRepo,
		profileRepo: profileRepo,
		outboxRepo:  outboxRepo,
		db:          db,
		logger:      logger,
	}
}

// CreateDefaultPreferences creates default preferences for a user (idempotent)
func (s *PreferencesService) CreateDefaultPreferences(ctx context.Context, userID uuid.UUID) error {
	prefs := entity.NewPreferences(userID)
	return s.prefsRepo.Create(ctx, prefs)
}

// CreateDefaultPreferencesTx creates default preferences within a transaction (idempotent)
func (s *PreferencesService) CreateDefaultPreferencesTx(ctx context.Context, tx pgx.Tx, userID uuid.UUID) error {
	prefs := entity.NewPreferences(userID)
	return s.prefsRepo.CreateTx(ctx, tx, prefs)
}

// GetPreferences retrieves preferences for a user
func (s *PreferencesService) GetPreferences(ctx context.Context, userID uuid.UUID) (*entity.Preferences, error) {
	prefs, err := s.prefsRepo.GetByUserID(ctx, userID)
	if err != nil {
		return nil, err
	}
	if prefs == nil {
		return nil, ErrPreferencesNotFound
	}
	return prefs, nil
}

// UpdatePreferences updates preferences
func (s *PreferencesService) UpdatePreferences(
	ctx context.Context,
	userID uuid.UUID,
	language, currency string,
	emailEnabled, inAppEnabled, marketingEnabled *bool,
) (*entity.Preferences, error) {
	// Get existing preferences
	prefs, err := s.prefsRepo.GetByUserID(ctx, userID)
	if err != nil {
		return nil, err
	}
	if prefs == nil {
		return nil, ErrPreferencesNotFound
	}

	// Update fields
	if err := prefs.Update(language, currency, emailEnabled, inAppEnabled, marketingEnabled); err != nil {
		return nil, err
	}

	// Save to database
	if err := s.prefsRepo.Update(ctx, prefs); err != nil {
		return nil, err
	}

	s.logger.Info("preferences updated",
		zap.String("user_id", userID.String()))

	return prefs, nil
}

// UpdatePreferencesWithOutbox updates preferences and creates outbox event in single transaction
func (s *PreferencesService) UpdatePreferencesWithOutbox(
	ctx context.Context,
	tx pgx.Tx,
	userID uuid.UUID,
	language, currency string,
	emailEnabled, inAppEnabled, marketingEnabled *bool,
) (*entity.Preferences, error) {
	// Get existing preferences
	prefs, err := s.prefsRepo.GetByUserID(ctx, userID)
	if err != nil {
		return nil, err
	}
	if prefs == nil {
		return nil, ErrPreferencesNotFound
	}

	// Update fields
	if err := prefs.Update(language, currency, emailEnabled, inAppEnabled, marketingEnabled); err != nil {
		return nil, err
	}

	// Update within transaction
	if err := s.prefsRepo.UpdateTx(ctx, tx, prefs); err != nil {
		return nil, err
	}

	// Create outbox event for CDC
	outboxEvent, err := entity.NewUserPreferencesUpdatedEvent(prefs)
	if err != nil {
		return nil, err
	}

	if err := s.outboxRepo.CreateTx(ctx, tx, outboxEvent); err != nil {
		return nil, err
	}

	s.logger.Info("preferences updated with outbox event",
		zap.String("user_id", userID.String()),
		zap.String("event_id", outboxEvent.EventID.String()))

	return prefs, nil
}

// PreferencesExist checks if preferences exist for a user
func (s *PreferencesService) PreferencesExist(ctx context.Context, userID uuid.UUID) (bool, error) {
	return s.prefsRepo.Exists(ctx, userID)
}
