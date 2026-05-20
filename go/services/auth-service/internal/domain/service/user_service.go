package service

import (
	"context"
	"crypto/rand"
	"fmt"
	"math/big"
	"time"

	"github.com/ecommerce/auth-service/internal/api/dto/svc"
	"github.com/ecommerce/auth-service/internal/common/constants"
	apperrors "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/common/validator"
	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/event"
	"github.com/ecommerce/auth-service/internal/domain/port"
	"github.com/ecommerce/auth-service/internal/domain/vo"
)

type UserService struct {
	userRepo       port.UserRepository
	otpService     *OTPService
	passwordHasher port.PasswordHasher
	eventPublisher port.EventPublisher
}

func NewUserService(
	userRepo port.UserRepository,
	otpService *OTPService,
	passwordHasher port.PasswordHasher,
	eventPublisher port.EventPublisher,
) *UserService {
	return &UserService{
		userRepo:       userRepo,
		otpService:     otpService,
		passwordHasher: passwordHasher,
		eventPublisher: eventPublisher,
	}
}

func (s *UserService) Register(ctx context.Context, req *svc.RegisterRequest) (*entity.User, string, error) {
	email, err := vo.NewEmail(req.Email)
	if err != nil {
		return nil, "", apperrors.NewAppError("INVALID_EMAIL", "Invalid email format", 400, err)
	}

	if valid, msg := validator.ValidatePassword(req.Password); !valid {
		return nil, "", apperrors.NewAppError("WEAK_PASSWORD", msg, 400, nil)
	}

	existing, err := s.userRepo.GetByEmail(ctx, email.String())
	if err != nil && !apperrors.IsNotFoundError(err) {
		return nil, "", fmt.Errorf("failed to check existing user: %w", err)
	}
	if existing != nil {
		return nil, "", apperrors.ErrUserAlreadyExists()
	}

	passwordHash, err := s.passwordHasher.Hash(req.Password)
	if err != nil {
		return nil, "", fmt.Errorf("failed to hash password: %w", err)
	}

	user := entity.NewUser(*email, passwordHash)

	if err := s.userRepo.Create(ctx, user); err != nil {
		return nil, "", fmt.Errorf("failed to create user: %w", err)
	}

	otp, err := s.otpService.GenerateOTP(ctx, email.String())
	if err != nil {
		return nil, "", fmt.Errorf("failed to generate OTP: %w", err)
	}

	if s.eventPublisher != nil {
		otpEvent := event.NewUserOTPRequested(user.ID, user.Email.String(), event.OTPTypeRegister)
		if err := s.eventPublisher.Publish(ctx, otpEvent); err != nil {
			fmt.Printf("Failed to publish OTP event: %v\n", err)
		}
	}

	return user, otp, nil
}

func (s *UserService) VerifyEmail(ctx context.Context, emailStr, otp string) error {
	if err := s.otpService.VerifyOTP(ctx, emailStr, otp); err != nil {
		return err
	}

	user, err := s.userRepo.GetByEmail(ctx, emailStr)
	if err != nil {
		if apperrors.IsNotFoundError(err) {
			return apperrors.ErrUserNotFound()
		}
		return fmt.Errorf("failed to get user: %w", err)
	}

	if user.IsEmailVerified() {
		return apperrors.NewAppError("ALREADY_VERIFIED", "Email already verified", 400, nil)
	}

	user.SetEmailVerified()
	if err := s.userRepo.Update(ctx, user); err != nil {
		return fmt.Errorf("failed to update user: %w", err)
	}

	s.otpService.DeleteOTP(ctx, emailStr)

	if s.eventPublisher != nil {
		verifiedEvent := event.NewUserVerifiedSuccess(user.ID, user.Email.String(), user.Roles)
		if err := s.eventPublisher.Publish(ctx, verifiedEvent); err != nil {
			fmt.Printf("Failed to publish verification event: %v\n", err)
		}
	}

	return nil
}

func (s *UserService) ResendOTP(ctx context.Context, emailStr string) (string, error) {
	user, err := s.userRepo.GetByEmail(ctx, emailStr)
	if err != nil {
		if apperrors.IsNotFoundError(err) {
			return "", apperrors.ErrUserNotFound()
		}
		return "", fmt.Errorf("failed to get user: %w", err)
	}

	if user.IsEmailVerified() {
		return "", apperrors.NewAppError("ALREADY_VERIFIED", "Email already verified", 400, nil)
	}

	otp, err := s.otpService.GenerateOTP(ctx, emailStr)
	if err != nil {
		return "", fmt.Errorf("failed to generate OTP: %w", err)
	}

	if s.eventPublisher != nil {
		otpEvent := event.NewUserOTPRequested(user.ID, user.Email.String(), event.OTPTypeRegister)
		if err := s.eventPublisher.Publish(ctx, otpEvent); err != nil {
			fmt.Printf("Failed to publish OTP event: %v\n", err)
		}
	}

	return otp, nil
}

func (s *UserService) GetUserByID(ctx context.Context, userID string) (*entity.User, error) {
	user, err := s.userRepo.GetByID(ctx, userID)
	if err != nil {
		if apperrors.IsNotFoundError(err) {
			return nil, apperrors.ErrUserNotFound()
		}
		return nil, fmt.Errorf("failed to get user: %w", err)
	}
	return user, nil
}

func (s *UserService) GetUserByEmail(ctx context.Context, emailStr string) (*entity.User, error) {
	user, err := s.userRepo.GetByEmail(ctx, emailStr)
	if err != nil {
		if apperrors.IsNotFoundError(err) {
			return nil, apperrors.ErrUserNotFound()
		}
		return nil, fmt.Errorf("failed to get user: %w", err)
	}
	return user, nil
}

func (s *UserService) BanUser(ctx context.Context, userID string, reason string) error {
	user, err := s.userRepo.GetByID(ctx, userID)
	if err != nil {
		if apperrors.IsNotFoundError(err) {
			return apperrors.ErrUserNotFound()
		}
		return fmt.Errorf("failed to get user: %w", err)
	}

	if user.IsBanned() {
		return nil
	}

	now := time.Now().UTC()
	user.Status = entity.UserStatusBanned
	user.BannedAt = &now
	user.BanReason = &reason

	if err := s.userRepo.Update(ctx, user); err != nil {
		return fmt.Errorf("failed to ban user: %w", err)
	}

	if s.eventPublisher != nil {
		banEvent := event.NewUserBannedNotification(user.ID, user.Email.String(), reason, "SYSTEM")
		if err := s.eventPublisher.Publish(ctx, banEvent); err != nil {
			fmt.Printf("Failed to publish user banned event: %v\n", err)
		}
	}

	return nil
}

// ConstantTimeCompare performs constant-time string comparison (exported for testing)
func ConstantTimeCompare(a, b string) bool {
	if len(a) != len(b) {
		return false
	}

	var result byte = 0
	for i := 0; i < len(a); i++ {
		result |= a[i] ^ b[i]
	}

	return result == 0
}

// GenerateSecureOTP generates a cryptographically secure 6-digit OTP (exported for testing)
func GenerateSecureOTP() (string, error) {
	const digits = "0123456789"
	otp := make([]byte, constants.OTPLength)

	for i := 0; i < constants.OTPLength; i++ {
		num, err := rand.Int(rand.Reader, big.NewInt(int64(len(digits))))
		if err != nil {
			return "", err
		}
		otp[i] = digits[num.Int64()]
	}

	return string(otp), nil
}

type OTPService struct {
	otpCache port.OTPCache
}

func NewOTPService(otpCache port.OTPCache) *OTPService {
	return &OTPService{
		otpCache: otpCache,
	}
}

func (s *OTPService) GenerateOTP(ctx context.Context, email string) (string, error) {
	otp, err := GenerateSecureOTP()
	if err != nil {
		return "", fmt.Errorf("failed to generate OTP: %w", err)
	}

	if err := s.otpCache.Set(ctx, email, otp); err != nil {
		return "", fmt.Errorf("failed to cache OTP: %w", err)
	}

	return otp, nil
}

func (s *OTPService) VerifyOTP(ctx context.Context, email, otp string) error {
	storedOTP, err := s.otpCache.Get(ctx, email)
	if err != nil {
		return fmt.Errorf("failed to get OTP from cache: %w", err)
	}

	if storedOTP == "" {
		return apperrors.ErrInvalidOTP()
	}

	if !ConstantTimeCompare(otp, storedOTP) {
		return apperrors.ErrInvalidOTP()
	}

	return nil
}

func (s *OTPService) DeleteOTP(ctx context.Context, email string) error {
	if err := s.otpCache.Delete(ctx, email); err != nil {
		return fmt.Errorf("failed to delete OTP: %w", err)
	}
	return nil
}

func (s *OTPService) GetOTPExpiry(ctx context.Context, email string) (time.Duration, error) {
	ttl, err := s.otpCache.GetRemainingTTL(ctx, email)
	if err != nil {
		return 0, fmt.Errorf("failed to get OTP TTL: %w", err)
	}
	return ttl, nil
}
