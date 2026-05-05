package service

import (
	"context"
	"errors"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"go.uber.org/zap"

	"user-service/internal/domain/entity"
	"user-service/internal/domain/port"
	"user-service/internal/domain/vo"
)

var (
	ErrProfileNotFound     = errors.New("profile not found")
	ErrPhoneNumberExists   = errors.New("phone number already exists")
	ErrInvalidDateOfBirth = errors.New("date of birth cannot be in the future")
)

// ProfileService handles profile business logic
type ProfileService struct {
	profileRepo port.ProfileRepository
	db          *pgxpool.Pool
	logger      *zap.Logger
}

// NewProfileService creates a new ProfileService
func NewProfileService(profileRepo port.ProfileRepository, db *pgxpool.Pool, logger *zap.Logger) *ProfileService {
	return &ProfileService{
		profileRepo: profileRepo,
		db:          db,
		logger:      logger,
	}
}

// CreateProfile creates a new profile (idempotent)
func (s *ProfileService) CreateProfile(ctx context.Context, userID uuid.UUID) error {
	profile := entity.NewProfile(userID)
	return s.profileRepo.Create(ctx, profile)
}

// CreateProfileTx creates a new profile within a transaction (idempotent)
func (s *ProfileService) CreateProfileTx(ctx context.Context, tx pgx.Tx, userID uuid.UUID) error {
	profile := entity.NewProfile(userID)
	return s.profileRepo.CreateTx(ctx, tx, profile)
}

// GetProfile retrieves a profile by user ID
func (s *ProfileService) GetProfile(ctx context.Context, userID uuid.UUID) (*entity.Profile, error) {
	profile, err := s.profileRepo.GetByID(ctx, userID)
	if err != nil {
		return nil, err
	}
	if profile == nil {
		return nil, ErrProfileNotFound
	}
	return profile, nil
}

// UpdateProfile updates a profile with transactional outbox
func (s *ProfileService) UpdateProfile(ctx context.Context, userID uuid.UUID, firstName, lastName, phone string, dateOfBirth *time.Time, genderStr string) (*entity.Profile, error) {
	// Get existing profile
	profile, err := s.profileRepo.GetByID(ctx, userID)
	if err != nil {
		return nil, err
	}
	if profile == nil {
		return nil, ErrProfileNotFound
	}

	// Validate and set name
	if firstName != "" || lastName != "" {
		nameVO, err := vo.NewName(firstName, lastName)
		if err != nil {
			return nil, err
		}
		if firstName != "" {
			profile.FirstName = nameVO.FirstName
		}
		if lastName != "" {
			profile.LastName = nameVO.LastName
		}
	}

	// Validate and set phone number
	if phone != "" {
		// Check if phone is already used by another user
		exists, err := s.profileRepo.ExistsByPhoneNumber(ctx, phone, &userID)
		if err != nil {
			return nil, err
		}
		if exists {
			return nil, ErrPhoneNumberExists
		}

		phoneVO, err := vo.NewPhoneNumber(phone)
		if err != nil {
			return nil, err
		}
		profile.PhoneNumber = &phoneVO.Value
	}

	// Validate and set date of birth
	if dateOfBirth != nil {
		if dateOfBirth.After(time.Now()) {
			return nil, ErrInvalidDateOfBirth
		}
		profile.DateOfBirth = dateOfBirth
	}

	// Validate and set gender
	if genderStr != "" {
		genderVO, err := vo.NewGender(genderStr)
		if err != nil {
			return nil, err
		}
		profile.Gender = genderVO
	}

	// Update in database
	if err := s.profileRepo.Update(ctx, profile); err != nil {
		return nil, err
	}

	return profile, nil
}

// UpdateProfileWithOutbox updates profile and creates outbox event in single transaction
func (s *ProfileService) UpdateProfileWithOutbox(ctx context.Context, tx pgx.Tx, userID uuid.UUID, firstName, lastName, phone string, dateOfBirth *time.Time, genderStr string, outboxRepo port.OutboxRepository) (*entity.Profile, error) {
	// Get existing profile
	profile, err := s.profileRepo.GetByID(ctx, userID)
	if err != nil {
		return nil, err
	}
	if profile == nil {
		return nil, ErrProfileNotFound
	}

	// Validate and set name
	if firstName != "" || lastName != "" {
		nameVO, err := vo.NewName(firstName, lastName)
		if err != nil {
			return nil, err
		}
		if firstName != "" {
			profile.FirstName = nameVO.FirstName
		}
		if lastName != "" {
			profile.LastName = nameVO.LastName
		}
	}

	// Validate and set phone number
	if phone != "" {
		// Check if phone is already used by another user
		exists, err := s.profileRepo.ExistsByPhoneNumber(ctx, phone, &userID)
		if err != nil {
			return nil, err
		}
		if exists {
			return nil, ErrPhoneNumberExists
		}

		phoneVO, err := vo.NewPhoneNumber(phone)
		if err != nil {
			return nil, err
		}
		profile.PhoneNumber = &phoneVO.Value
	}

	// Validate and set date of birth
	if dateOfBirth != nil {
		if dateOfBirth.After(time.Now()) {
			return nil, ErrInvalidDateOfBirth
		}
		profile.DateOfBirth = dateOfBirth
	}

	// Validate and set gender
	if genderStr != "" {
		genderVO, err := vo.NewGender(genderStr)
		if err != nil {
			return nil, err
		}
		profile.Gender = genderVO
	}

	// Update profile within transaction
	if err := s.profileRepo.UpdateTx(ctx, tx, profile); err != nil {
		return nil, err
	}

	// Create outbox event for CDC
	outboxEvent, err := entity.NewUserProfileUpdatedEvent(profile)
	if err != nil {
		return nil, err
	}

	if err := outboxRepo.CreateTx(ctx, tx, outboxEvent); err != nil {
		return nil, err
	}

	s.logger.Info("Profile updated with outbox event",
		zap.String("user_id", userID.String()),
		zap.String("event_id", outboxEvent.EventID.String()))

	return profile, nil
}

// DeleteProfile deletes a profile
func (s *ProfileService) DeleteProfile(ctx context.Context, userID uuid.UUID) error {
	return s.profileRepo.Delete(ctx, userID)
}

// ProfileExists checks if a profile exists
func (s *ProfileService) ProfileExists(ctx context.Context, userID uuid.UUID) (bool, error) {
	return s.profileRepo.Exists(ctx, userID)
}
