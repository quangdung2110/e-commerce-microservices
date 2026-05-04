package authentication

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/entity"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/repository"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/serr"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/types"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/common"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/dto"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/utils"
)

const (
	accessTokenTTL  = 15 * time.Minute
	refreshTokenTTL = 90 * 24 * time.Hour // 90 days

	// IP Rate limiting: 10 attempts / 15 minutes -> block 1 hour (key: ratelimit:login:{ip})
	maxLoginAttemptsPerIP = 10
	loginAttemptWindow    = 15 * time.Minute
	ipBlockDuration      = 1 * time.Hour

	// Brute-force protection: 5 failed attempts -> lock account
	maxFailedAttemptsBeforeLock = 5
	failedLoginTTL             = 60 * time.Minute // 60 minutes
	accountLockDuration        = 30 * time.Minute // 30 minutes before auto-unlock
)

// LoginUsecase handles user authentication
type LoginUsecase struct {
	// Repositories
	sessionRepo      repository.SessionRepository
	loginAttemptRepo repository.LoginAttemptRepository
	rateLimitRepo    repository.RateLimitRepository
	refreshTokenRepo repository.RefreshTokenRepository
	userRepo        repository.UserRepository

	// External Services
	userService    common.UserService
	eventPublisher common.EventPublisher

	// Security
	tokenGenerator  common.TokenGenerator
	passwordEncoder common.PasswordEncoder

	// Audit
	auditService *AuditService

	logger *zap.Logger
}

// NewLoginUsecase creates new login usecase
func NewLoginUsecase(
	sessionRepo repository.SessionRepository,
	loginAttemptRepo repository.LoginAttemptRepository,
	rateLimitRepo repository.RateLimitRepository,
	refreshTokenRepo repository.RefreshTokenRepository,
	userRepo repository.UserRepository,
	userService common.UserService,
	eventPublisher common.EventPublisher,
	tokenGenerator common.TokenGenerator,
	passwordEncoder common.PasswordEncoder,
	auditService *AuditService,
	logger *zap.Logger,
) *LoginUsecase {
	return &LoginUsecase{
		sessionRepo:      sessionRepo,
		loginAttemptRepo: loginAttemptRepo,
		rateLimitRepo:    rateLimitRepo,
		refreshTokenRepo: refreshTokenRepo,
		userRepo:        userRepo,
		userService:      userService,
		eventPublisher:   eventPublisher,
		tokenGenerator:   tokenGenerator,
		passwordEncoder:  passwordEncoder,
		auditService:    auditService,
		logger:           logger,
	}
}

// Execute performs user authentication
func (uc *LoginUsecase) Execute(ctx context.Context, req *dto.LoginRequest, reqCtx *common.RequestContext) (*dto.LoginResponse, error) {
	// Step 1: Rate limit check (key: ratelimit:login:{ip})
	if err := uc.checkRateLimit(ctx, reqCtx.DeviceInfo.IPAddress); err != nil {
		return nil, err
	}

	// Step 2: IP block check (key: blocked:ip:{ip})
	blocked, err := uc.rateLimitRepo.IsIPBlocked(ctx, reqCtx.DeviceInfo.IPAddress)
	if err != nil {
		uc.logger.Error("Failed to check IP block", zap.Error(err))
	}
	if blocked {
		// Write record for failed attempt (table: auth_login_attempts)
		uc.recordFailedAttempt(ctx, nil, req.Identifier, entity.AttemptTypePassword, entity.FailureIPBlocked, reqCtx)
		return nil, serr.ErrIPBlocked
	}

	// Step 3: Find user (check email or phone identifier)
	user, err := uc.userService.GetUserByIdentifier(ctx, req.Identifier)
	if err != nil {
		uc.recordFailedAttempt(ctx, nil, req.Identifier, entity.AttemptTypePassword, entity.FailureWrongPassword, reqCtx)
		uc.incrementRateLimit(ctx, reqCtx.DeviceInfo.IPAddress)
		return nil, serr.ErrInvalidCredentials // Don't reveal user existence
	}

	// Step 4: Check user status - BANNED account
	if user.RestrictionStatus == entity.RestrictionSuspended {
		uc.recordFailedAttempt(ctx, &user.UserID, req.Identifier, entity.AttemptTypePassword, entity.FailureAccountLocked, reqCtx)
		return nil, serr.ErrAccountDeactivated
	}

	// Step 5: Check if account is LOCKED (from failed login attempts)
	if uc.isAccountLocked(ctx, user.UserID) {
		uc.recordFailedAttempt(ctx, &user.UserID, req.Identifier, entity.AttemptTypePassword, entity.FailureAccountLocked, reqCtx)
		return nil, serr.ErrAccountLocked
	}

	// Step 6: If MANAGED account, check parent.active
	if user.AccountType == entity.AccountTypeManaged && user.ParentID != nil {
		parent, err := uc.userService.GetUserByID(ctx, *user.ParentID)
		if err != nil || !parent.Active {
			uc.recordFailedAttempt(ctx, &user.UserID, req.Identifier, entity.AttemptTypePassword, entity.FailureAccountLocked, reqCtx)
			return nil, serr.ErrAccountDeactivated
		}
	}

	// Step 7: Verify password
	valid, err := uc.passwordEncoder.Verify(req.Password, user.PasswordHash)
	if err != nil || !valid {
		// Handle failed login - increment counters and check for lock
		locked, lockErr := uc.handleFailedLogin(ctx, user, req.Identifier, reqCtx)
		if lockErr != nil {
			uc.logger.Error("Failed to handle failed login", zap.Error(lockErr))
		}

		// If account was locked due to too many failed attempts
		if locked {
			return nil, serr.ErrAccountLocked
		}

		uc.incrementRateLimit(ctx, reqCtx.DeviceInfo.IPAddress)
		return nil, serr.ErrInvalidCredentials
	}

	// Step 8: Login successful - Reset failed login counters
	_ = uc.rateLimitRepo.ResetFailedLogin(ctx, req.Identifier, reqCtx.DeviceInfo.IPAddress)

	// Step before 9: Generate session ID for inclusion in token claims
	sessionID := uuid.New()

	// Step 9: Generate tokens
	accessToken, refreshToken, err := uc.generateTokenPair(user, reqCtx, sessionID)
	if err != nil {
		uc.logger.Error("Failed to generate tokens", zap.Error(err))
		return nil, fmt.Errorf("failed to generate tokens: %w", err)
	}

	// Step 10: Create/Update session
	// With PostgreSQL: insert auth_sessions record
	// With Redis: set refresh:{user_id}:{device_id}
	session, err := uc.createSession(ctx, user.UserID, reqCtx, refreshToken, sessionID)
	if err != nil {
		uc.logger.Error("Failed to create session", zap.Error(err))
		return nil, fmt.Errorf("failed to create session: %w", err)
	}

	// Step 11: Log successful attempt (insert auth_login_attempts record and reset rate limit counter to 0)
	attempt := entity.NewLoginAttempt(&user.UserID, req.Identifier, entity.AttemptTypePassword, true, reqCtx.DeviceInfo.IPAddress, reqCtx.DeviceInfo.UserAgent)
	if err := uc.loginAttemptRepo.Create(ctx, attempt); err != nil {
		uc.logger.Error("Failed to log login attempt", zap.Error(err))
	}

	// Reset rate limit after successful login
	uc.resetRateLimit(ctx, reqCtx.DeviceInfo.IPAddress)

	// Step 12: Write audit log for successful login
	uc.writeAuditLog(ctx, user.UserID.String(), types.EventTypeUserLoggedIn, types.SecurityLevelInfo, types.LogCategoryAuth,
		reqCtx.DeviceInfo.IPAddress,
		map[string]any{
			"session_id":  session.SessionID.String(),
			"device_type": string(reqCtx.DeviceInfo.DeviceType),
			"user_agent":  reqCtx.DeviceInfo.UserAgent,
		})

	// Step 13: Publish event
	if err := uc.publishLoginEvent(ctx, user.UserID, session.SessionID, reqCtx.DeviceInfo, reqCtx.Location); err != nil {
		uc.logger.Error("Failed to publish login event", zap.Error(err))
	}

	// Step 14: Build response
	response := &dto.LoginResponse{
		AccessToken:  accessToken,
		RefreshToken: refreshToken,
		ExpiresIn:    int64(accessTokenTTL.Seconds()),
		TokenType:    "Bearer",
		User:         user,
	}

	// Add restriction warning if applicable
	if user.RestrictionStatus != entity.RestrictionNone {
		response.RestrictionWarning = &dto.RestrictionWarning{
			Status:  user.RestrictionStatus,
			Message: uc.getRestrictionMessage(user.RestrictionStatus),
		}
	}

	uc.logger.Info("User logged in successfully",
		zap.String("user_id", user.UserID.String()),
		zap.String("session_id", session.SessionID.String()))

	return response, nil
}

// ==================== HELPER METHODS ====================

func (uc *LoginUsecase) checkRateLimit(ctx context.Context, ip string) error {
	count, err := uc.rateLimitRepo.IncrementLoginAttempts(ctx, ip)
	if err != nil {
		uc.logger.Error("Failed to check rate limit", zap.Error(err))
		return nil // Don't block on rate limit errors
	}

	if count > maxLoginAttemptsPerIP {
		// Block IP
		uc.rateLimitRepo.BlockIP(ctx, ip, "Too many login attempts", ipBlockDuration)
		return serr.ErrRateLimitExceeded
	}

	return nil
}

func (uc *LoginUsecase) incrementRateLimit(ctx context.Context, ip string) {
	if _, err := uc.rateLimitRepo.IncrementLoginAttempts(ctx, ip); err != nil {
		uc.logger.Error("Failed to increment rate limit", zap.Error(err))
	}
}

func (uc *LoginUsecase) resetRateLimit(ctx context.Context, ipAddress string) {
	key := fmt.Sprintf("ratelimit:login:%s", ipAddress)
	if err := uc.rateLimitRepo.Delete(ctx, key); err != nil {
		uc.logger.Warn("Failed to reset rate limit", zap.Error(err))
	}
}

func (uc *LoginUsecase) recordFailedAttempt(
	ctx context.Context,
	userID *uuid.UUID,
	identifier string,
	attemptType entity.AttemptType,
	reason entity.FailureReason,
	reqCtx *common.RequestContext,
) {
	attempt := entity.NewLoginAttempt(userID, identifier, attemptType, false, reqCtx.DeviceInfo.IPAddress, reqCtx.DeviceInfo.UserAgent)
	attempt.SetFailureReason(reason)

	if err := uc.loginAttemptRepo.Create(ctx, attempt); err != nil {
		uc.logger.Error("Failed to log login attempt", zap.Error(err))
	}

	// Write audit log for failed login attempt
	actorID := "unknown"
	if userID != nil {
		actorID = userID.String()
	}

	securityLevel := types.SecurityLevelWarning
	if reason == entity.FailureIPBlocked {
		securityLevel = types.SecurityLevelCritical
	}

	uc.writeAuditLog(ctx, actorID, types.EventTypeUserLoginFailed, securityLevel, types.LogCategoryAuth,
		reqCtx.DeviceInfo.IPAddress,
		map[string]any{
			"reason":     string(reason),
			"identifier": identifier,
		})
}

// writeAuditLog is a helper to write audit logs
func (uc *LoginUsecase) writeAuditLog(ctx context.Context, actorID string, eventType types.EventType, level types.SecurityLevel, category types.LogCategory, ipAddress string, payload map[string]any) {
	if uc.auditService != nil {
		_ = uc.auditService.Log(ctx, &AuditEvent{
			ActorID:       actorID,
			ActorType:    types.ActorTypeUser,
			EventType:    eventType,
			IPAddress:    ipAddress,
			SecurityLevel: level,
			LogCategory:  category,
			Payload:     payload,
		})
	}
}

func (uc *LoginUsecase) generateTokenPair(
	user *common.UserDTO,
	reqCtx *common.RequestContext,
	sessionId uuid.UUID,
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
		SessionID:         sessionId,
		ExpiresAt:         time.Now().UTC().Add(accessTokenTTL).Unix(),
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

func (uc *LoginUsecase) createSession(
	ctx context.Context,
	userID uuid.UUID,
	reqCtx *common.RequestContext,
	refreshToken string,
	sessionID uuid.UUID,
) (*entity.Session, error) {
	deviceID := "unknown"
	if reqCtx.DeviceInfo.DeviceID != nil {
		deviceID = *reqCtx.DeviceInfo.DeviceID
	}

	tokenHash := utils.SHA256Hash(refreshToken)
	deviceInfo := reqCtx.DeviceInfo

	session := entity.NewSession(
		userID,
		entity.DeviceInfo(deviceInfo),
		reqCtx.Location,
		tokenHash,
		time.Now().UTC().Add(refreshTokenTTL),
	)

	// Set session ID in the session object
	session.SessionID = sessionID

	// Save to PostgreSQL
	if err := uc.sessionRepo.Create(ctx, session); err != nil {
		return nil, fmt.Errorf("failed to create session: %w", err)
	}

	// Save refresh token to Redis
	if err := uc.refreshTokenRepo.Store(ctx, userID, deviceID, tokenHash, session.SessionID, refreshTokenTTL); err != nil {
		uc.logger.Error("Failed to store refresh token in Redis", zap.Error(err))
	}

	return session, nil
}

func (uc *LoginUsecase) publishLoginEvent(ctx context.Context, userID, sessionID uuid.UUID, deviceInfo entity.DeviceInfo, location *entity.GeoLocation) error {
	event := &common.UserLoggedInEvent{
		UserID:     userID,
		SessionID:  sessionID,
		DeviceType: string(deviceInfo.DeviceType),
		IPAddress:  deviceInfo.IPAddress,
		Timestamp:  time.Now().UTC(),
	}

	if location != nil {
		event.GeoLocation = location
	}

	return uc.eventPublisher.PublishUserLoggedIn(ctx, event)
}

func (uc *LoginUsecase) getRestrictionMessage(status entity.RestrictionStatus) string {
	switch status {
	case entity.RestrictionWarning:
		return "Your account has received a warning. Please review our terms of service."
	case entity.RestrictionLimited:
		return "Your account has limited access. Some features may be unavailable."
	case entity.RestrictionSuspended:
		return "Your account is suspended. Please contact support for more information."
	default:
		return ""
	}
}

// ==================== Brute-Force Protection Methods ====================

// isAccountLocked checks if the user account is currently locked
func (uc *LoginUsecase) isAccountLocked(ctx context.Context, userID uuid.UUID) bool {
	// First check Redis cache for quick lookup
	user, err := uc.userRepo.GetByID(ctx, userID)
	if err != nil {
		uc.logger.Error("Failed to get user for lock check", zap.Error(err))
		return false
	}
	return user.IsLocked()
}

// handleFailedLogin handles a failed login attempt
// Returns (wasLocked, error) - wasLocked is true if account was locked due to too many attempts
func (uc *LoginUsecase) handleFailedLogin(ctx context.Context, user *common.UserDTO, identifier string, reqCtx *common.RequestContext) (bool, error) {
	// Step 1: Increment failed login counters for both email and IP
	emailCount, err := uc.rateLimitRepo.IncrementFailedLogin(ctx, identifier)
	if err != nil {
		uc.logger.Error("Failed to increment failed login counter", zap.Error(err))
	}

	ipCount, err := uc.rateLimitRepo.IncrementFailedLoginByIP(ctx, reqCtx.DeviceInfo.IPAddress)
	if err != nil {
		uc.logger.Error("Failed to increment failed login counter by IP", zap.Error(err))
	}

	uc.logger.Warn("Failed login attempt",
		zap.String("user_id", user.UserID.String()),
		zap.String("email_count", fmt.Sprintf("%d/%d", emailCount, maxFailedAttemptsBeforeLock)),
		zap.String("ip_count", fmt.Sprintf("%d/%d", ipCount, maxLoginAttemptsPerIP)))

	// Step 2: Check if account should be locked (>= 5 failed attempts)
	if emailCount >= maxFailedAttemptsBeforeLock {
		uc.logger.Warn("Account lock threshold reached, locking account",
			zap.String("user_id", user.UserID.String()),
			zap.Int("failed_attempts", emailCount))

		// Lock the account
		lockedAt := time.Now()
		if err := uc.userRepo.LockAccount(ctx, user.UserID, lockedAt); err != nil {
			return false, fmt.Errorf("failed to lock account: %w", err)
		}

		// Reset failed login counters in Redis
		_ = uc.rateLimitRepo.ResetFailedLogin(ctx, identifier, reqCtx.DeviceInfo.IPAddress)

		// Write audit log for account locked
		uc.writeAuditLog(ctx, user.UserID.String(), types.EventTypeUserAccountLocked, types.SecurityLevelCritical, types.LogCategoryAuth,
			reqCtx.DeviceInfo.IPAddress,
			map[string]any{
				"reason":        "FAILED_LOGIN_ATTEMPTS",
				"failed_attempts": emailCount,
				"locked_until":   lockedAt.Add(accountLockDuration).Format(time.RFC3339),
			})

		// Publish USER_ACCOUNT_LOCKED event for notification
		if err := uc.publishAccountLockedEvent(ctx, user.UserID, user.Email, lockedAt.Add(accountLockDuration)); err != nil {
			uc.logger.Error("Failed to publish account locked event", zap.Error(err))
		}

		return true, nil
	}

	return false, nil
}

// publishAccountLockedEvent publishes the USER_ACCOUNT_LOCKED event to Kafka
func (uc *LoginUsecase) publishAccountLockedEvent(ctx context.Context, userID uuid.UUID, email string, lockedUntil time.Time) error {
	// Log the event - in production this would be stored in outbox table
	// and published via the outbox pattern
	uc.logger.Info("USER_ACCOUNT_LOCKED event should be published",
		zap.String("user_id", userID.String()),
		zap.String("email", email),
		zap.Time("locked_until", lockedUntil),
		zap.String("event_type", string(types.EventTypeUserAccountLocked)))

	// In production, this would use the outbox pattern:
	// outboxRepo.Create(ctx, buildUserAccountLockedEvent(userID, email, lockedUntil))

	return nil
}
