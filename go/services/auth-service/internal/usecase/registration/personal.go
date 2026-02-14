package registration

import (
	"context"
	"fmt"
	"strings"
	"time"

	"go.uber.org/zap"

	"github.com/google/uuid"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/entity"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/repository"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/serr"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/common"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/dto"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/utils"
)

const (
	otpLength              = 6
	otpEmailTTL            = 15 * time.Minute
	otpSMSTTL              = 5 * time.Minute
	registrationSessionTTL = 30 * time.Minute

	maxOTPEmailPerDay = 5
	maxOTPSMSPerDay   = 3
)

// PersonalRegistrationUsecase handles multi-step personal registration flow
type PersonalRegistrationUsecase struct {
	otpRepo             repository.OTPRepository
	sessionRepo         repository.RegistrationSessionRepository
	rateLimitRepo       repository.RateLimitRepository
	userService         common.UserService
	notificationService common.NotificationService
	eventPublisher      common.EventPublisher
	passwordEncoder     common.PasswordEncoder
	tokenGenerator      common.TokenGenerator
	logger              *zap.Logger
}

// NewPersonalRegistrationUsecase creates new personal registration usecase
func NewPersonalRegistrationUsecase(
	otpRepo repository.OTPRepository,
	sessionRepo repository.RegistrationSessionRepository,
	rateLimitRepo repository.RateLimitRepository,
	userService common.UserService,
	notificationService common.NotificationService,
	eventPublisher common.EventPublisher,
	passwordEncoder common.PasswordEncoder,
	tokenGenerator common.TokenGenerator,
	logger *zap.Logger,
) *PersonalRegistrationUsecase {
	return &PersonalRegistrationUsecase{
		otpRepo:             otpRepo,
		sessionRepo:         sessionRepo,
		rateLimitRepo:       rateLimitRepo,
		userService:         userService,
		notificationService: notificationService,
		eventPublisher:      eventPublisher,
		passwordEncoder:     passwordEncoder,
		tokenGenerator:      tokenGenerator,
		logger:              logger,
	}
}

// StartRegistration initiates registration with email (Step 1)
func (uc *PersonalRegistrationUsecase) StartRegistration(ctx context.Context, req *dto.StartRegistrationRequest, reqCtx *common.RequestContext) (*dto.StartRegistrationResponse, error) {
	email := strings.ToLower(strings.TrimSpace(req.Email))

	// Check if email already exists
	user, err := uc.userService.GetUserByEmail(ctx, email)
	if err != nil {
		uc.logger.Error("Failed to check email existence", zap.Error(err))
	}
	if user != nil {
		return nil, serr.ErrEmailAlreadyExists
	}

	// Rate limit check
	rateKey := fmt.Sprintf("ratelimit:otp:email:%s", email)
	count, err := uc.rateLimitRepo.IncrementOTPRequests(ctx, rateKey)
	if err != nil {
		uc.logger.Error("Failed to check OTP rate limit", zap.Error(err))
	}
	if count > maxOTPEmailPerDay {
		return nil, serr.ErrOTPRateLimitExceeded
	}

	// Create registration session
	session, err := uc.sessionRepo.Create(ctx, email, registrationSessionTTL)
	if err != nil {
		return nil, fmt.Errorf("failed to create registration session: %w", err)
	}

	// Generate and send OTP
	otpCode, _ := utils.GenerateRandomNumeric(otpLength)
	hashedOTP, _ := uc.passwordEncoder.Hash(otpCode)
	identifierHash, _ := uc.passwordEncoder.Hash(email)

	otp := entity.NewOTP(
		entity.OTPPurposeRegister,
		entity.OTPChannelEmail,
		hashedOTP,
		email,
		identifierHash,
		reqCtx.DeviceInfo.IPAddress,
		otpEmailTTL,
	)

	if err := uc.otpRepo.Store(ctx, otp, otpEmailTTL); err != nil {
		return nil, fmt.Errorf("failed to store OTP: %w", err)
	}

	// Send OTP email
	if err := uc.notificationService.SendEmailOTP(ctx, email, otpCode, otpEmailTTL); err != nil {
		uc.logger.Error("Failed to send OTP email", zap.Error(err))
	}

	// Update session
	requestID := otp.RequestID
	session.EmailOTPRequestID = &requestID
	session.CurrentStep = 2
	uc.sessionRepo.Update(ctx, session)

	return &dto.StartRegistrationResponse{
		SessionID:        session.SessionID,
		Email:            email,
		MaskedEmail:      utils.MaskEmail(email),
		OTPRequestID:     otp.RequestID,
		OTPExpiresIn:     int(otpEmailTTL.Seconds()),
		NextStep:         2,
		NextStepEndpoint: "/api/v1/register/verify-email",
	}, nil
}

// VerifyEmailOTP verifies email OTP (Step 2)
func (uc *PersonalRegistrationUsecase) VerifyEmailOTP(ctx context.Context, req *dto.VerifyEmailOTPRequest) (*dto.VerifyEmailOTPResponse, error) {
	session, err := uc.sessionRepo.Get(ctx, req.SessionID)
	if err != nil {
		return nil, serr.ErrOTPNotFound
	}

	if session.CurrentStep != 2 || session.Email == nil {
		return nil, serr.ErrInvalidInput.WithDetails("invalid registration step")
	}

	// Verify OTP
	identifierHash, _ := uc.passwordEncoder.Hash(*session.Email)
	otp, err := uc.otpRepo.Get(ctx, entity.OTPPurposeRegister, entity.OTPChannelEmail, identifierHash)
	if err != nil {
		return nil, serr.ErrOTPNotFound
	}

	if otp.MaxAttemptsReached() {
		uc.otpRepo.Delete(ctx, entity.OTPPurposeRegister, entity.OTPChannelEmail, identifierHash)
		return nil, serr.ErrOTPMaxAttemptsReached
	}

	valid, err := uc.passwordEncoder.Verify(req.OTPCode, otp.CodeHash)
	if err != nil || !valid {
		uc.otpRepo.IncrementAttempts(ctx, entity.OTPPurposeRegister, entity.OTPChannelEmail, identifierHash)
		return nil, serr.ErrOTPInvalid
	}

	// Delete OTP after successful verification
	uc.otpRepo.Delete(ctx, entity.OTPPurposeRegister, entity.OTPChannelEmail, identifierHash)

	// Update session
	session.EmailVerified = true
	session.CurrentStep = 3
	uc.sessionRepo.Update(ctx, session)

	return &dto.VerifyEmailOTPResponse{
		SessionID:        session.SessionID,
		EmailVerified:    true,
		NextStep:         3,
		NextStepEndpoint: "/api/v1/register/add-phone",
	}, nil
}

// AddPhoneNumber adds phone number and sends OTP (Step 3)
func (uc *PersonalRegistrationUsecase) AddPhoneNumber(ctx context.Context, req *dto.AddPhoneNumberRequest, reqCtx *common.RequestContext) (*dto.AddPhoneNumberResponse, error) {
	session, err := uc.sessionRepo.Get(ctx, req.SessionID)
	if err != nil {
		return nil, serr.ErrOTPNotFound.WithDetails("registration session not found")
	}

	if session.CurrentStep != 3 || !session.EmailVerified {
		return nil, serr.ErrInvalidInput.WithDetails("invalid registration step")
	}

	// Check if phone already exists
	phone := strings.TrimSpace(req.PhoneNumber)
	user, err := uc.userService.GetUserByPhone(ctx, phone)
	if err != nil {
		uc.logger.Error("Failed to check phone existence", zap.Error(err))
	}
	if user != nil {
		return nil, serr.ErrPhoneAlreadyExists
	}

	// Rate limit check
	rateKey := fmt.Sprintf("ratelimit:otp:sms:%s", phone)
	count, err := uc.rateLimitRepo.IncrementOTPRequests(ctx, rateKey)
	if err != nil {
		uc.logger.Error("Failed to check OTP rate limit", zap.Error(err))
	}
	if count > maxOTPSMSPerDay {
		return nil, serr.ErrOTPRateLimitExceeded
	}

	// Generate and send OTP
	otpCode, _ := utils.GenerateRandomNumeric(otpLength)
	hashedOTP, _ := uc.passwordEncoder.Hash(otpCode)
	identifierHash, _ := uc.passwordEncoder.Hash(phone)

	otp := entity.NewOTP(
		entity.OTPPurposeRegister,
		entity.OTPChannelPhone,
		hashedOTP,
		phone,
		identifierHash,
		reqCtx.DeviceInfo.IPAddress,
		otpSMSTTL,
	)

	if err := uc.otpRepo.Store(ctx, otp, otpSMSTTL); err != nil {
		return nil, fmt.Errorf("failed to store OTP: %w", err)
	}

	// Send OTP SMS
	if err := uc.notificationService.SendSMSOTP(ctx, phone, otpCode, otpSMSTTL); err != nil {
		uc.logger.Error("Failed to send OTP SMS", zap.Error(err))
	}

	// Update session
	requestID := otp.RequestID
	session.PhoneNumber = &phone
	session.PhoneOTPRequestID = &requestID
	session.CurrentStep = 4
	uc.sessionRepo.Update(ctx, session)

	return &dto.AddPhoneNumberResponse{
		SessionID:        session.SessionID,
		PhoneNumber:      phone,
		MaskedPhone:      utils.MaskPhone(phone),
		OTPRequestID:     otp.RequestID,
		OTPExpiresIn:     int(otpSMSTTL.Seconds()),
		NextStep:         4,
		NextStepEndpoint: "/api/v1/register/verify-phone",
	}, nil
}

// VerifyPhoneOTP verifies phone OTP (Step 4)
func (uc *PersonalRegistrationUsecase) VerifyPhoneOTP(ctx context.Context, req *dto.VerifyPhoneOTPRequest) (*dto.VerifyPhoneOTPResponse, error) {
	session, err := uc.sessionRepo.Get(ctx, req.SessionID)
	if err != nil {
		return nil, serr.ErrOTPNotFound.WithDetails("registration session not found")
	}

	if session.CurrentStep != 4 || !session.EmailVerified || session.PhoneNumber == nil {
		return nil, serr.ErrInvalidInput.WithDetails("invalid registration step")
	}

	// Verify OTP
	identifierHash, _ := uc.passwordEncoder.Hash(*session.PhoneNumber)
	otp, err := uc.otpRepo.Get(ctx, entity.OTPPurposeRegister, entity.OTPChannelPhone, identifierHash)
	if err != nil {
		return nil, serr.ErrOTPNotFound
	}

	if otp.MaxAttemptsReached() {
		uc.otpRepo.Delete(ctx, entity.OTPPurposeRegister, entity.OTPChannelPhone, identifierHash)
		return nil, serr.ErrOTPMaxAttemptsReached
	}

	valid, err := uc.passwordEncoder.Verify(req.OTPCode, otp.CodeHash)
	if err != nil || !valid {
		uc.otpRepo.IncrementAttempts(ctx, entity.OTPPurposeRegister, entity.OTPChannelPhone, identifierHash)
		return nil, serr.ErrOTPInvalid
	}

	// Delete OTP after successful verification
	uc.otpRepo.Delete(ctx, entity.OTPPurposeRegister, entity.OTPChannelPhone, identifierHash)

	// Update session
	session.PhoneVerified = true
	session.CurrentStep = 5
	uc.sessionRepo.Update(ctx, session)

	return &dto.VerifyPhoneOTPResponse{
		SessionID:        session.SessionID,
		PhoneVerified:    true,
		NextStep:         5,
		NextStepEndpoint: "/api/v1/register/complete",
	}, nil
}

// CompleteRegistration completes registration process (Step 5)
func (uc *PersonalRegistrationUsecase) CompleteRegistration(ctx context.Context, req *dto.CompleteRegistrationRequest, reqCtx *common.RequestContext) (*dto.CompleteRegistrationResponse, error) {
	session, err := uc.sessionRepo.Get(ctx, req.SessionID)
	if err != nil {
		return nil, serr.ErrOTPNotFound.WithDetails("registration session not found or expired")
	}

	if session.CurrentStep != 5 || !session.EmailVerified || !session.PhoneVerified {
		return nil, serr.ErrInvalidInput.WithDetails("invalid registration step or verification incomplete")
	}

	if session.Email == nil || session.PhoneNumber == nil {
		return nil, serr.ErrInvalidInput.WithDetails("email or phone not found in session")
	}

	// Validate password strength
	if err := utils.ValidatePasswordStrength(req.Password); err != nil {
		return nil, err
	}

	// Hash password
	passwordHash, err := uc.passwordEncoder.Hash(req.Password)
	if err != nil {
		return nil, fmt.Errorf("failed to hash password: %w", err)
	}

	// Create user
	createUserReq := &common.CreateUserRequest{
		Email:        *session.Email,
		PhoneNumber:  *session.PhoneNumber,
		FullName:     req.FullName,
		PasswordHash: passwordHash,
		Role:         entity.RoleCustomer,
		AccountType:  entity.AccountTypePersonal,
	}

	user, err := uc.userService.CreateUser(ctx, createUserReq)
	if err != nil {
		uc.logger.Error("Failed to create user", zap.Error(err))
		return nil, err
	}

	// Generate tokens
	accessToken, refreshToken, err := uc.generateTokenPair(user, reqCtx)
	if err != nil {
		return nil, fmt.Errorf("failed to generate tokens: %w", err)
	}

	// Delete registration session
	uc.sessionRepo.Delete(ctx, session.SessionID)

	// Publish registration event
	if err := uc.publishRegistrationEvent(ctx, user.UserID, user.Email, user.PhoneNumber, user.AccountType, req.SessionID); err != nil {
		uc.logger.Error("Failed to publish registration event", zap.Error(err))
	}

	uc.logger.Info("User registration completed",
		zap.String("user_id", user.UserID.String()),
		zap.String("email", user.Email))

	return &dto.CompleteRegistrationResponse{
		Success:      true,
		AccessToken:  accessToken,
		RefreshToken: refreshToken,
		TokenType:    "Bearer",
		User:         user,
	}, nil
}

// ==================== HELPER FUNCTIONS ====================

func (uc *PersonalRegistrationUsecase) publishRegistrationEvent(ctx context.Context, userID uuid.UUID, email string, phoneNumber string, accountType entity.AccountType, sessionID string) error {
	event := &common.UserRegisteredEvent{
		UserID:         userID,
		Email:          email,
		PhoneNumber:    phoneNumber,
		AccountType:    string(accountType),
		RegistrationIP: sessionID,
	}
	return uc.eventPublisher.PublishUserRegistered(ctx, event)
}

func (uc *PersonalRegistrationUsecase) generateTokenPair(
	user *common.UserDTO,
	reqCtx *common.RequestContext,
) (string, string, error) {
	// Generate access token (JWT)
	claims := &entity.TokenClaims{
		JTI:               uuid.New().String(),
		Type:              entity.TokenTypeAccess,
		Subject:           user.UserID,
		Role:              user.Role,
		AccountType:       user.AccountType,
		RestrictionStatus: user.RestrictionStatus,
		ParentID:          user.ParentID,
		ExpiresAt:         time.Now().UTC().Add(registrationSessionTTL).Unix(),
	}

	accessToken, err := uc.tokenGenerator.GenerateAccessToken(claims)
	if err != nil {
		return "", "", fmt.Errorf("failed to generate access token: %w", err)
	}

	// Generate refresh token (opaque)
	refreshToken, err := uc.tokenGenerator.GenerateRefreshToken()
	if err != nil {
		return "", "", fmt.Errorf("failed to generate refresh token: %w", err)
	}

	// Format: user_id:device_id:random
	deviceID := "unknown"
	if reqCtx.DeviceInfo.DeviceID != nil {
		deviceID = *reqCtx.DeviceInfo.DeviceID
	}
	fullRefreshToken := fmt.Sprintf("%s:%s:%s", user.UserID.String(), deviceID, refreshToken)

	return accessToken, fullRefreshToken, nil
}
