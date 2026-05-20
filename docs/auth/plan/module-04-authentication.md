# Module 4: Authentication

> **Module:** Domain + API Layer  
> **File:** `module-04-authentication.md`  
> **Phase:** 2 - Core Features  
> **Estimated Time:** 3-4 days

---

## Table of Contents

1. [Overview](#1-overview)
2. [Auth Service](#2-auth-service)
3. [Brute-Force Protection](#3-brute-force-protection)
4. [API Handlers](#4-api-handlers)
5. [Rate Limiting Middleware](#5-rate-limiting-middleware)
6. [Testing](#6-testing)
7. [Checklist](#7-checklist)

---

## 1. Overview

Module này triển khai chức năng xác thực:
- Đăng nhập (Login)
- Đăng xuất (Logout)
- Bảo vệ brute-force
- Khóa tài khoản tự động

### Login Flow with Brute-Force Protection

```
┌─────────┐     ┌─────────────┐     ┌───────────────┐     ┌─────────────┐
│ Client  │────▶│ AuthHandler │────▶│ RateLimiter  │────▶│ AuthService │
│         │     │ /login     │     │ (Middleware) │     │            │
└─────────┘     └─────────────┘     └───────────────┘     └─────────────┘
                                                               │
                                                               ▼
                                                         ┌─────────────┐
                                                         │ UserRepo    │
                                                         │ FailedLogin │
                                                         │ Counter     │
                                                         └─────────────┘
```

---

## 2. Auth Service

### 2.1 Auth Service Implementation

**Security Note:**
- Refresh tokens are NEVER returned in the response body
- Refresh tokens are ONLY stored in HTTP-only secure cookies
- This prevents XSS attacks from stealing refresh tokens

```go
// internal/domain/service/auth_service.go
package service

import (
    "context"
    "fmt"
    "time"

    "github.com/google/uuid"
    "go.uber.org/zap"

    "github.com/ecommerce/auth-service/internal/common/constants"
    apperrors "github.com/ecommerce/auth-service/internal/common/errors"
    "github.com/ecommerce/auth-service/internal/domain/entity"
    "github.com/ecommerce/auth-service/internal/domain/event"
    "github.com/ecommerce/auth-service/internal/domain/port"
)

// LoginRequest represents login input
type LoginRequest struct {
    Email      string
    Password   string
    DeviceID   string
    DeviceInfo string
    IPAddress  string
}

// LoginResponse represents login output
// NOTE: RefreshToken is intentionally excluded for security
// Refresh tokens are ONLY stored in HTTP-only cookies, never in response body
type LoginResponse struct {
    AccessToken string    `json:"access_token"`
    ExpiresIn  int64     `json:"expires_in"`
    TokenType  string    `json:"token_type"`
    User       *UserInfo `json:"user"`
}

// UserInfo represents basic user information
type UserInfo struct {
    ID           uuid.UUID
    Email        string
    Roles        []string
    Status       entity.UserStatus
}

type AuthService struct {
    userRepo         port.UserRepository
    refreshTokenRepo port.RefreshTokenRepository
    tokenCache       port.TokenCache
    gracePeriodCache port.GracePeriodCache
    passwordHasher   port.PasswordHasher
    failedLogin      port.FailedLoginCache
    eventPublisher   port.EventPublisher
    auditService     *AuditService
    jwtService       *JWTService
    logger           *zap.Logger
}

func NewAuthService(
    userRepo port.UserRepository,
    refreshTokenRepo port.RefreshTokenRepository,
    tokenCache port.TokenCache,
    gracePeriodCache port.GracePeriodCache,
    passwordHasher port.PasswordHasher,
    failedLogin port.FailedLoginCache,
    eventPublisher port.EventPublisher,
    auditService *AuditService,
    jwtService *JWTService,
    logger *zap.Logger,
) *AuthService {
    return &AuthService{
        userRepo:         userRepo,
        refreshTokenRepo: refreshTokenRepo,
        tokenCache:       tokenCache,
        gracePeriodCache: gracePeriodCache,
        passwordHasher:   passwordHasher,
        failedLogin:      failedLogin,
        eventPublisher:   eventPublisher,
        auditService:     auditService,
        jwtService:       jwtService,
        logger:           logger.With(zap.String("service", "AuthService")),
    }
}

// Login authenticates a user and returns tokens
// NOTE: Refresh token is NOT returned - it is ONLY set via HTTP-only cookie
func (s *AuthService) Login(ctx context.Context, req *LoginRequest) (*LoginResponse, error) {
    // Add correlation context for debugging
    logger := s.logger.With(
        zap.String("email", maskEmail(req.Email)),
        zap.String("ip", req.IPAddress),
        zap.String("device_id", req.DeviceID),
    )

    logger.Info("Login attempt started")

    // Get user by email
    user, err := s.userRepo.GetByEmail(ctx, req.Email)
    if err != nil {
        if err == port.ErrNotFound {
            // Increment failed login counter for non-existent user (prevent enumeration)
            s.failedLogin.Increment(ctx, req.Email)
            s.failedLogin.Increment(ctx, req.IPAddress)
            logger.Warn("Login failed - user not found",
                zap.String("reason", "user_not_found"),
            )
            return nil, apperrors.ErrInvalidCredentials
        }
        logger.Error("Login failed - database error",
            zap.String("error", err.Error()),
        )
        return nil, fmt.Errorf("failed to get user: %w", err)
    }

    // Check account status
    if user.IsBanned() {
        logger.Warn("Login failed - account banned",
            zap.String("user_id", user.ID.String()),
        )
        return nil, apperrors.ErrAccountBanned
    }

    if user.IsLocked() {
        logger.Warn("Login failed - account locked",
            zap.String("user_id", user.ID.String()),
        )
        return nil, apperrors.ErrAccountLocked
    }

    if user.IsPendingVerification() {
        logger.Warn("Login failed - email not verified",
            zap.String("user_id", user.ID.String()),
        )
        return nil, apperrors.ErrEmailNotVerified
    }

    // Check failed login counter
    isLocked, err := s.failedLogin.IsLocked(ctx, req.Email)
    if err != nil {
        logger.Error("Failed to check lock status",
            zap.String("error", err.Error()),
        )
        return nil, fmt.Errorf("failed to check lock status: %w", err)
    }
    if isLocked {
        logger.Warn("Login failed - account locked by brute-force protection",
            zap.String("user_id", user.ID.String()),
        )
        return nil, apperrors.ErrAccountLocked
    }

    // Verify password
    valid, err := s.passwordHasher.Verify(ctx, req.Password, user.PasswordHash)
    if err != nil {
        logger.Error("Password verification failed",
            zap.String("user_id", user.ID.String()),
            zap.String("error", err.Error()),
        )
        return nil, fmt.Errorf("failed to verify password: %w", err)
    }

    if !valid {
        // Increment failed login counter
        _, shouldLock, err := s.failedLogin.IncrementAndCheck(ctx, req.Email)
        if err != nil {
            logger.Error("Failed to increment failed login counter",
                zap.String("user_id", user.ID.String()),
                zap.String("error", err.Error()),
            )
            return nil, fmt.Errorf("failed to increment failed login: %w", err)
        }

        // Also increment IP counter
        s.failedLogin.Increment(ctx, req.IPAddress)

        // Log failed login attempt via event (async, non-blocking)
        failedLoginEvent := event.NewLoginFailed(
            user.ID,
            user.Email,
            req.IPAddress,
            req.DeviceID,
            req.DeviceInfo,
        )
        _ = s.eventPublisher.Publish(ctx, failedLoginEvent)

        // Check if should lock account
        if shouldLock {
            if err := s.userRepo.LockAccount(ctx, user.ID); err != nil {
                logger.Error("Failed to lock account",
                    zap.String("user_id", user.ID.String()),
                    zap.String("error", err.Error()),
                )
                return nil, fmt.Errorf("failed to lock account: %w", err)
            }

            // Publish account locked event
            lockEvent := event.NewUserAccountLocked(
                user.ID,
                user.Email,
                "FAILED_LOGIN_ATTEMPTS",
                time.Now().Add(constants.AccountLockDuration),
            )
            if err := s.eventPublisher.Publish(ctx, lockEvent); err != nil {
                logger.Error("Failed to publish account locked event",
                    zap.String("user_id", user.ID.String()),
                    zap.String("error", err.Error()),
                )
            }

            logger.Warn("Account locked due to failed login attempts",
                zap.String("user_id", user.ID.String()),
                zap.Int("max_attempts", constants.MaxFailedLoginAttempts),
            )
            return nil, apperrors.ErrAccountLocked
        }

        logger.Warn("Login failed - invalid credentials",
            zap.String("user_id", user.ID.String()),
        )
        return nil, apperrors.ErrInvalidCredentials
    }

    // Reset failed login counters on successful login
    s.failedLogin.Reset(ctx, req.Email)
    s.failedLogin.Reset(ctx, req.IPAddress)

    // Reset user failed attempts and update last login
    if user.FailedLoginAttempts > 0 {
        user.ResetFailedAttempts()
    }
    user.LastLoginAt = time.Now().UTC()
    user.LastLoginIP = req.IPAddress
    if err := s.userRepo.Update(ctx, user); err != nil {
        logger.Error("Failed to update user last login",
            zap.String("user_id", user.ID.String()),
            zap.String("error", err.Error()),
        )
    }

    // Generate tokens
    accessToken, err := s.jwtService.GenerateAccessToken(ctx, user)
    if err != nil {
        logger.Error("Failed to generate access token",
            zap.String("user_id", user.ID.String()),
            zap.String("error", err.Error()),
        )
        return nil, fmt.Errorf("failed to generate access token: %w", err)
    }

    // Generate refresh token
    refreshToken := generateSecureToken(32) // 256 bits
    refreshTokenHash := hashToken(refreshToken)

    // Save refresh token to database
    tokenEntity := &entity.RefreshToken{
        ID:         uuid.New(),
        UserID:     user.ID,
        TokenHash:  refreshTokenHash,
        FamilyID:   uuid.New(), // New family for each login
        DeviceID:   req.DeviceID,
        DeviceInfo: req.DeviceInfo,
        ExpiresAt:  time.Now().Add(constants.RefreshTokenTTLDefault),
        CreatedAt:  time.Now().UTC(),
    }

    if err := s.refreshTokenRepo.Create(ctx, tokenEntity); err != nil {
        logger.Error("Failed to save refresh token",
            zap.String("user_id", user.ID.String()),
            zap.String("error", err.Error()),
        )
        return nil, fmt.Errorf("failed to save refresh token: %w", err)
    }

    // Publish successful login event
    loginSuccessEvent := event.NewLoginSuccess(
        user.ID,
        user.Email,
        req.IPAddress,
        req.DeviceID,
        req.DeviceInfo,
    )
    _ = s.eventPublisher.Publish(ctx, loginSuccessEvent)

    logger.Info("Login successful",
        zap.String("user_id", user.ID.String()),
        zap.Strings("roles", user.Roles),
    )

    // NOTE: RefreshToken is NOT returned in response body for security
    // The caller should set it as an HTTP-only cookie
    return &LoginResponse{
        AccessToken: accessToken,
        ExpiresIn:  int64(constants.AccessTokenTTLDefault.Seconds()),
        TokenType:  "Bearer",
        User: &UserInfo{
            ID:     user.ID,
            Email:  user.Email,
            Roles:  user.Roles,
            Status: user.Status,
        },
    }, nil
}

// GetRefreshTokenForCookie returns the refresh token to be set as HTTP-only cookie
// This separates token generation from response handling for better security
func (s *AuthService) GetRefreshTokenForCookie(ctx context.Context, userID uuid.UUID) (string, time.Time, error) {
    refreshToken := generateSecureToken(32)
    refreshTokenHash := hashToken(refreshToken)

    expiresAt := time.Now().Add(constants.RefreshTokenTTLDefault)

    tokenEntity := &entity.RefreshToken{
        ID:        uuid.New(),
        UserID:    userID,
        TokenHash: refreshTokenHash,
        FamilyID:  uuid.New(),
        ExpiresAt: expiresAt,
        CreatedAt: time.Now().UTC(),
    }

    if err := s.refreshTokenRepo.Create(ctx, tokenEntity); err != nil {
        return "", time.Time{}, fmt.Errorf("failed to save refresh token: %w", err)
    }

    return refreshToken, expiresAt, nil
}

// maskEmail masks email for logging (e.g., t***@example.com)
func maskEmail(email string) string {
    if len(email) < 4 {
        return "***"
    }
    atIndex := -1
    for i, c := range email {
        if c == '@' {
            atIndex = i
            break
        }
    }
    if atIndex <= 1 {
        return email[:1] + "***"
    }
    return email[:2] + "***" + email[atIndex:]
}

// Logout handles user logout
func (s *AuthService) Logout(ctx context.Context, userID uuid.UUID, refreshToken string, accessTokenJTI string, accessTokenExp time.Time) error {
    logger := s.logger.With(zap.String("user_id", userID.String()))

    // Add access token to denylist
    remainingTTL := time.Until(accessTokenExp)
    if err := s.tokenCache.AddToDenylist(ctx, accessTokenJTI, remainingTTL); err != nil {
        logger.Error("Failed to add token to denylist",
            zap.String("jti", accessTokenJTI),
            zap.String("error", err.Error()),
        )
        return fmt.Errorf("failed to add token to denylist: %w", err)
    }

    // Revoke refresh token
    refreshTokenHash := hashToken(refreshToken)
    if err := s.refreshTokenRepo.RevokeByHash(ctx, refreshTokenHash); err != nil {
        logger.Error("Failed to revoke refresh token",
            zap.String("error", err.Error()),
        )
        return fmt.Errorf("failed to revoke refresh token: %w", err)
    }

    // Publish logout event
    logoutEvent := event.NewLogoutEvent(userID)
    _ = s.eventPublisher.Publish(ctx, logoutEvent)

    logger.Info("User logged out successfully")
    return nil
}

// ForceLogoutAll revokes all tokens for a user
func (s *AuthService) ForceLogoutAll(ctx context.Context, userID uuid.UUID, reason string) error {
    logger := s.logger.With(
        zap.String("user_id", userID.String()),
        zap.String("reason", reason),
    )

    // Revoke all refresh tokens
    if err := s.refreshTokenRepo.RevokeAllByUserID(ctx, userID); err != nil {
        logger.Error("Failed to revoke all refresh tokens",
            zap.String("error", err.Error()),
        )
        return fmt.Errorf("failed to revoke all tokens: %w", err)
    }

    // Set revocation timestamp in Redis
    now := time.Now()
    if err := s.tokenCache.SetRevokedBefore(ctx, userID.String(), now); err != nil {
        logger.Error("Failed to set revoked_before timestamp",
            zap.String("error", err.Error()),
        )
        return fmt.Errorf("failed to set revoked_before: %w", err)
    }

    // Publish force logout event
    forceLogoutEvent := event.NewForceLogoutEvent(userID, reason)
    _ = s.eventPublisher.Publish(ctx, forceLogoutEvent)

    logger.Info("All sessions revoked for user")
    return nil
}

// hashToken creates SHA-256 hash of a token
func hashToken(token string) string {
    hash := sha256.Sum256([]byte(token))
    return hex.EncodeToString(hash[:])
}

// generateSecureToken generates a cryptographically secure random token
func generateSecureToken(length int) string {
    bytes := make([]byte, length)
    if _, err := rand.Read(bytes); err != nil {
        panic(err)
    }
    return hex.EncodeToString(bytes)
}

import (
    "crypto/rand"
    "crypto/sha256"
    "encoding/hex"
)
```

---

## 3. Brute-Force Protection

### 3.1 Brute-Force Protection Implementation

```go
// internal/domain/service/bruteforce_service.go
package service

import (
    "context"
    "fmt"
    "time"

    "go.uber.org/zap"

    apperrors "github.com/ecommerce/auth-service/internal/common/errors"
    "github.com/ecommerce/auth-service/internal/domain/entity"
    "github.com/ecommerce/auth-service/internal/domain/event"
    "github.com/ecommerce/auth-service/internal/domain/port"
)

type BruteForceService struct {
    userRepo       port.UserRepository
    failedLogin    port.FailedLoginCache
    auditService   *AuditService
    eventPublisher port.EventPublisher
    logger         *zap.Logger
}

func NewBruteForceService(
    userRepo port.UserRepository,
    failedLogin port.FailedLoginCache,
    auditService *AuditService,
    eventPublisher port.EventPublisher,
    logger *zap.Logger,
) *BruteForceService {
    return &BruteForceService{
        userRepo:       userRepo,
        failedLogin:    failedLogin,
        auditService:   auditService,
        eventPublisher: eventPublisher,
        logger:         logger.With(zap.String("service", "BruteForceService")),
    }
}

// HandleFailedLogin handles a failed login attempt
// Returns (shouldLockAccount, error)
func (s *BruteForceService) HandleFailedLogin(
    ctx context.Context,
    email string,
    userID string,
    ipAddress string,
    deviceInfo string,
) (bool, error) {
    logger := s.logger.With(
        zap.String("email", maskEmail(email)),
        zap.String("user_id", userID),
        zap.String("ip", ipAddress),
    )

    // Increment counters
    emailCount, emailShouldLock, err := s.failedLogin.IncrementAndCheck(ctx, email)
    if err != nil {
        logger.Error("Failed to increment email counter",
            zap.String("error", err.Error()),
        )
        return false, fmt.Errorf("failed to increment email counter: %w", err)
    }

    ipCount, err := s.failedLogin.Increment(ctx, ipAddress)
    if err != nil {
        logger.Error("Failed to increment IP counter",
            zap.String("error", err.Error()),
        )
        return false, fmt.Errorf("failed to increment IP counter: %w", err)
    }

    // Publish failed login event (async, non-blocking)
    failedLoginEvent := event.NewLoginFailed(
        uuid.MustParse(userID),
        email,
        ipAddress,
        "", // DeviceID not available here
        deviceInfo,
    )
    _ = s.eventPublisher.Publish(ctx, failedLoginEvent)

    logger.Warn("Failed login attempt",
        zap.Int("attempt_count", emailCount),
        zap.Int("ip_attempt_count", ipCount),
    )

    // Check if should lock account
    if emailShouldLock && userID != "" {
        if err := s.lockAccount(ctx, email, ipAddress, logger); err != nil {
            logger.Error("Failed to lock account",
                zap.String("error", err.Error()),
            )
            return false, err
        }
        return true, nil
    }

    // Check if IP should be rate limited (distributed attack detection)
    if ipCount >= constants.DistributedAttackThreshold {
        logger.Warn("Possible distributed brute-force attack detected",
            zap.String("ip", ipAddress),
            zap.Int("attempt_count", ipCount),
        )
    }

    return false, nil
}

// HandleSuccessfulLogin resets failed login counters
func (s *BruteForceService) HandleSuccessfulLogin(
    ctx context.Context,
    email string,
    ipAddress string,
) error {
    logger := s.logger.With(
        zap.String("email", maskEmail(email)),
        zap.String("ip", ipAddress),
    )

    // Reset counters
    if err := s.failedLogin.Reset(ctx, email); err != nil {
        logger.Error("Failed to reset email counter",
            zap.String("error", err.Error()),
        )
        return fmt.Errorf("failed to reset email counter: %w", err)
    }

    if err := s.failedLogin.Reset(ctx, ipAddress); err != nil {
        logger.Error("Failed to reset IP counter",
            zap.String("error", err.Error()),
        )
        return fmt.Errorf("failed to reset IP counter: %w", err)
    }

    logger.Debug("Failed login counters reset")
    return nil
}

// lockAccount locks a user account and sends notification
func (s *BruteForceService) lockAccount(ctx context.Context, email, ipAddress string, logger *zap.Logger) error {
    // Get user
    user, err := s.userRepo.GetByEmail(ctx, email)
    if err != nil {
        logger.Error("Failed to get user for account lock",
            zap.String("error", err.Error()),
        )
        return fmt.Errorf("failed to get user: %w", err)
    }

    userLogger := logger.With(zap.String("user_id", user.ID.String()))

    // Lock account in database
    if err := s.userRepo.LockAccount(ctx, user.ID); err != nil {
        userLogger.Error("Failed to lock account in database",
            zap.String("error", err.Error()),
        )
        return fmt.Errorf("failed to lock account: %w", err)
    }

    // Publish account locked event
    lockEvent := event.NewUserAccountLocked(
        user.ID,
        user.Email,
        "FAILED_LOGIN_ATTEMPTS",
        time.Now().Add(constants.AccountLockDuration),
    )
    if err := s.eventPublisher.Publish(ctx, lockEvent); err != nil {
        userLogger.Error("Failed to publish account locked event",
            zap.String("error", err.Error()),
        )
        // Non-fatal: account is still locked in DB
    }

    userLogger.Warn("Account locked due to failed login attempts",
        zap.String("lock_reason", "FAILED_LOGIN_ATTEMPTS"),
        zap.Duration("lock_duration", constants.AccountLockDuration),
    )

    return nil
}

// IsAccountLocked checks if an account is currently locked
func (s *BruteForceService) IsAccountLocked(ctx context.Context, email string) (bool, error) {
    return s.failedLogin.IsLocked(ctx, email)
}

// GetRemainingLockTime returns the remaining lock time for an account
func (s *BruteForceService) GetRemainingLockTime(ctx context.Context, email string) (time.Duration, error) {
    return s.failedLogin.GetRemainingTTL(ctx, email)
}
```

---

## 4. API Handlers

### 4.1 Auth Handler

```go
// internal/api/handler/auth_handler.go
package handler

import (
    "net/http"
    "strings"
    "time"

    "github.com/gin-gonic/gin"
    "github.com/google/uuid"
    "github.com/golang-jwt/jwt/v5"
    "go.uber.org/zap"

    "github.com/ecommerce/auth-service/internal/api/dto/request"
    "github.com/ecommerce/auth-service/internal/api/dto/response"
    "github.com/ecommerce/auth-service/internal/api/middleware"
    "github.com/ecommerce/auth-service/internal/domain/service"
    "github.com/ecommerce/auth-service/internal/common/constants"
    apperrors "github.com/ecommerce/auth-service/internal/common/errors"
)

type AuthHandler struct {
    authService *service.AuthService
    jwtService  *service.JWTService
    logger      *zap.Logger
}

func NewAuthHandler(authService *service.AuthService, jwtService *service.JWTService, logger *zap.Logger) *AuthHandler {
    return &AuthHandler{
        authService: authService,
        jwtService:  jwtService,
        logger:      logger.With(zap.String("handler", "AuthHandler")),
    }
}

// Login handles POST /api/v1/auth/public/login
//
// Security measures:
// - Refresh token is set as HTTP-only cookie ONLY, NOT returned in response body
// - Device ID is validated as UUID format
// - Rate limiting is handled by middleware
func (h *AuthHandler) Login(c *gin.Context) {
    var req request.LoginRequest
    if err := c.ShouldBindJSON(&req); err != nil {
        middleware.Error(c, apperrors.ErrBadRequest.WithMessage(err.Error()))
        return
    }

    // Extract device info
    req.IPAddress = c.ClientIP()
    req.DeviceInfo = c.GetHeader("User-Agent")
    req.DeviceID = c.GetHeader("X-Device-ID")

    // Validate or generate device ID
    if req.DeviceID == "" {
        // Generate a new UUID for new devices
        req.DeviceID = uuid.New().String()
    } else {
        // Validate device ID format
        if _, err := uuid.Parse(req.DeviceID); err != nil {
            // Invalid UUID format - generate new one instead
            h.logger.Warn("Invalid device ID format received, generating new one",
                zap.String("provided_device_id", req.DeviceID),
            )
            req.DeviceID = uuid.New().String()
        }
    }

    // Attempt login
    loginResp, err := h.authService.Login(c.Request.Context(), &service.LoginRequest{
        Email:      req.Email,
        Password:   req.Password,
        DeviceID:   req.DeviceID,
        DeviceInfo: req.DeviceInfo,
        IPAddress:  req.IPAddress,
    })
    if err != nil {
        h.logger.Info("Login failed",
            zap.String("email", maskEmailForLog(req.Email)),
            zap.String("ip", req.IPAddress),
            zap.String("error", err.Error()),
        )
        handleServiceError(c, err)
        return
    }

    // Generate and store refresh token for HTTP-only cookie
    refreshToken, expiresAt, err := h.authService.GetRefreshTokenForCookie(
        c.Request.Context(),
        loginResp.User.ID,
    )
    if err != nil {
        h.logger.Error("Failed to generate refresh token",
            zap.String("user_id", loginResp.User.ID.String()),
            zap.String("error", err.Error()),
        )
        handleServiceError(c, apperrors.ErrInternal)
        return
    }

    // Set refresh token as HTTP-only cookie ONLY (NOT in response body)
    // Security settings:
    // - HttpOnly: Prevents JavaScript access (XSS protection)
    // - Secure: Only sent over HTTPS
    // - SameSite=Strict: Prevents CSRF attacks
    c.SetCookie(
        constants.RefreshTokenCookieName,
        refreshToken,
        int(time.Until(expiresAt).Seconds()),
        "/",
        "",
        true,                      // Secure
        true,                      // HttpOnly
        "SameSite=Strict;Path=/",  // CSRF protection
    )

    h.logger.Info("Login successful",
        zap.String("user_id", loginResp.User.ID.String()),
        zap.String("device_id", req.DeviceID),
    )

    // NOTE: RefreshToken is intentionally NOT included in response body
    // It is only stored in HTTP-only cookie for security
    c.JSON(http.StatusOK, response.LoginResponse{
        AccessToken: loginResp.AccessToken,
        ExpiresIn:  loginResp.ExpiresIn,
        TokenType:  loginResp.TokenType,
        User: response.UserResponse{
            ID:     loginResp.User.ID.String(),
            Email:  loginResp.User.Email,
            Roles:  loginResp.User.Roles,
            Status: string(loginResp.User.Status),
        },
    })
}

// maskEmailForLog masks email for logging (e.g., te***@example.com)
func maskEmailForLog(email string) string {
    if len(email) < 4 {
        return "***"
    }
    atIndex := -1
    for i, c := range email {
        if c == '@' {
            atIndex = i
            break
        }
    }
    if atIndex <= 1 {
        return email[:1] + "***"
    }
    return email[:2] + "***" + email[atIndex:]
}

// Logout handles POST /api/v1/auth/logout
func (h *AuthHandler) Logout(c *gin.Context) {
    logger := h.logger.With(zap.String("handler", "Logout"))

    // Get user ID from context (set by auth middleware)
    userIDStr, exists := c.Get("user_id")
    if !exists {
        loggerWarn("Unauthorized logout attempt", c)
        middleware.Error(c, apperrors.ErrUnauthorized)
        return
    }
    userID := parseUUID(userIDStr.(string))

    // Get token info from context
    refreshToken, _ := c.Cookie(constants.RefreshTokenCookieName)
    accessTokenJTI, _ := c.Get("jti")
    accessTokenExp, _ := c.Get("exp")

    // Call logout service
    err := h.authService.Logout(
        c.Request.Context(),
        userID,
        refreshToken,
        accessTokenJTI.(string),
        accessTokenExp.(time.Time),
    )
    if err != nil {
        logger.Error("Logout failed",
            zap.String("user_id", userID.String()),
            zap.String("error", err.Error()),
        )
        handleServiceError(c, err)
        return
    }

    // Clear refresh token cookie
    c.SetCookie(
        constants.RefreshTokenCookieName,
        "",
        -1,
        "/",
        "",
        true,
        true,
        "SameSite=Strict;Path=/",
    )

    logger.Info("User logged out",
        zap.String("user_id", userID.String()),
    )

    c.JSON(http.StatusOK, response.MessageResponse{
        Message: "Logged out successfully",
    })
}

// Refresh handles POST /api/v1/auth/refresh
func (h *AuthHandler) Refresh(c *gin.Context) {
    logger := h.logger.With(zap.String("handler", "Refresh"))

    // Get refresh token from cookie only (NOT from body for security)
    refreshToken, err := c.Cookie(constants.RefreshTokenCookieName)
    if err != nil || refreshToken == "" {
        logger.Warn("Refresh token missing from cookie")
        middleware.Error(c, apperrors.ErrBadRequest.WithMessage("Refresh token required"))
        return
    }

    // Get current access token info for denylist
    accessTokenJTI, _ := c.Get("jti")
    accessTokenExp, _ := c.Get("exp")

    // Call refresh service
    refreshResp, err := h.authService.RefreshTokens(
        c.Request.Context(),
        refreshToken,
        accessTokenJTI.(string),
        accessTokenExp.(time.Time),
    )
    if err != nil {
        logger.Info("Token refresh failed",
            zap.String("error", err.Error()),
        )
        handleServiceError(c, err)
        return
    }

    // Update refresh token cookie with new token
    c.SetCookie(
        constants.RefreshTokenCookieName,
        refreshResp.NewRefreshToken,
        int(time.Until(refreshResp.ExpiresAt).Seconds()),
        "/",
        "",
        true,
        true,
        "SameSite=Strict;Path=/",
    )

    logger.Info("Tokens refreshed successfully")

    c.JSON(http.StatusOK, response.RefreshResponse{
        AccessToken: refreshResp.AccessToken,
        ExpiresIn:  int64(time.Until(refreshResp.ExpiresAt).Seconds()),
        TokenType:  "Bearer",
        // NOTE: NewRefreshToken is intentionally NOT in response body
        // It is only available via HTTP-only cookie
    })
}

// parseUUID safely parses a UUID string
func parseUUID(s string) uuid.UUID {
    parsed, err := uuid.Parse(s)
    if err != nil {
        return uuid.Nil
    }
    return parsed
}

// handleServiceError handles service errors and returns appropriate HTTP responses
func handleServiceError(c *gin.Context, err error) {
    if appErr, ok := err.(*apperrors.AppError); ok {
        middleware.Error(c, appErr)
        return
    }
    middleware.Error(c, apperrors.ErrInternal)
}

// loggerWarn logs a warning with handler context
func loggerWarn(msg string, c *gin.Context) {
    // In real implementation, this would use the injected logger
    // For now, just log to standard logger
    println(msg)
}

import (
    "net/http"
    "strings"
    "time"

    "github.com/gin-gonic/gin"
    "github.com/google/uuid"
    "github.com/golang-jwt/jwt/v5"
    "go.uber.org/zap"

    "github.com/ecommerce/auth-service/internal/api/dto/request"
    "github.com/ecommerce/auth-service/internal/api/dto/response"
    "github.com/ecommerce/auth-service/internal/api/middleware"
    "github.com/ecommerce/auth-service/internal/domain/service"
    "github.com/ecommerce/auth-service/internal/common/constants"
    apperrors "github.com/ecommerce/auth-service/internal/common/errors"
)
```

### 4.2 Auth Middleware

```go
// internal/api/middleware/auth.go
package middleware

import (
    "net/http"
    "strings"
    "time"

    "github.com/gin-gonic/gin"
    "github.com/golang-jwt/jwt/v5"
    "go.uber.org/zap"

    "github.com/ecommerce/auth-service/internal/common/errors"
    "github.com/ecommerce/auth-service/internal/domain/service"
)

type AuthMiddleware struct {
    jwtService *service.JWTService
    tokenCache TokenCache
    logger     *zap.Logger
}

func NewAuthMiddleware(jwtService *service.JWTService, tokenCache TokenCache, logger *zap.Logger) *AuthMiddleware {
    return &AuthMiddleware{
        jwtService: jwtService,
        tokenCache: tokenCache,
        logger:     logger.With(zap.String("middleware", "Auth")),
    }
}

// Auth returns a middleware that validates JWT tokens
func (m *AuthMiddleware) Auth() gin.HandlerFunc {
    return func(c *gin.Context) {
        logger := m.logger.With(
            zap.String("path", c.Request.URL.Path),
            zap.String("method", c.Request.Method),
            zap.String("client_ip", c.ClientIP()),
        )

        // Get Authorization header
        authHeader := c.GetHeader("Authorization")
        if authHeader == "" {
            logger.Debug("Missing authorization header")
            abortWithError(c, http.StatusUnauthorized, "UNAUTHORIZED", "Authorization header required")
            return
        }

        // Extract token from "Bearer <token>"
        parts := strings.SplitN(authHeader, " ", 2)
        if len(parts) != 2 || strings.ToLower(parts[0]) != "bearer" {
            logger.Debug("Invalid authorization header format")
            abortWithError(c, http.StatusUnauthorized, "UNAUTHORIZED", "Invalid authorization header format")
            return
        }
        tokenString := parts[1]

        // Parse and validate token
        claims, err := m.jwtService.ValidateAccessToken(c.Request.Context(), tokenString)
        if err != nil {
            logger.Info("Token validation failed",
                zap.String("error", err.Error()),
            )
            abortWithError(c, http.StatusUnauthorized, "INVALID_TOKEN", "Invalid or expired token")
            return
        }

        // Check denylist
        if m.tokenCache != nil {
            isDenylisted, err := m.tokenCache.IsDenylisted(c.Request.Context(), claims.JTI)
            if err != nil {
                logger.Error("Failed to check token denylist",
                    zap.String("jti", claims.JTI),
                    zap.String("error", err.Error()),
                )
                // Continue - denylist check failure should not block valid tokens
                // In production, consider whether to fail-closed or fail-open
            } else if isDenylisted {
                logger.Info("Token is denylisted (revoked)",
                    zap.String("jti", claims.JTI),
                    zap.String("user_id", claims.UserID),
                )
                abortWithError(c, http.StatusUnauthorized, "TOKEN_REVOKED", "Token has been revoked")
                return
            }

            // Check user revocation timestamp
            revokedBefore, err := m.tokenCache.GetRevokedBefore(c.Request.Context(), claims.UserID)
            if err == nil && !revokedBefore.IsZero() {
                if claims.IssuedAt.Before(revokedBefore) || claims.IssuedAt.Equal(revokedBefore) {
                    logger.Info("Token issued before revocation timestamp",
                        zap.String("user_id", claims.UserID),
                        zap.Time("issued_at", claims.IssuedAt.Time),
                        zap.Time("revoked_before", revokedBefore),
                    )
                    abortWithError(c, http.StatusUnauthorized, "TOKEN_REVOKED", "Token has been revoked")
                    return
                }
            }
        }

        // Set user info in context
        c.Set("user_id", claims.UserID)
        c.Set("roles", claims.Roles)
        c.Set("jti", claims.JTI)
        c.Set("exp", claims.ExpiresAt.Time)

        logger.Debug("Token validated successfully",
            zap.String("user_id", claims.UserID),
            zap.Strings("roles", claims.Roles),
        )

        c.Next()
    }
}

// RequireRole returns a middleware that checks for required roles
func (m *AuthMiddleware) RequireRole(roles ...string) gin.HandlerFunc {
    return func(c *gin.Context) {
        userRoles, exists := c.Get("roles")
        if !exists {
            abortWithError(c, http.StatusForbidden, "FORBIDDEN", "No roles found")
            return
        }

        roleList, ok := userRoles.([]string)
        if !ok {
            abortWithError(c, http.StatusInternalServerError, "INTERNAL_ERROR", "Invalid roles format")
            return
        }

        for _, requiredRole := range roles {
            for _, userRole := range roleList {
                if userRole == requiredRole {
                    c.Next()
                    return
                }
            }
        }

        abortWithError(c, http.StatusForbidden, "FORBIDDEN", "Insufficient permissions")
    }
}

func abortWithError(c *gin.Context, status int, code, message string) {
    c.AbortWithStatusJSON(status, gin.H{
        "error": gin.H{
            "code":    code,
            "message": message,
        },
    })
}
```

---

## 5. Rate Limiting Middleware

### 5.1 Rate Limiter Middleware

```go
// internal/api/middleware/ratelimit.go
package middleware

import (
    "fmt"
    "net/http"
    "time"

    "github.com/gin-gonic/gin"
    "go.uber.org/zap"

    "github.com/ecommerce/auth-service/internal/common/constants"
)

type RateLimiterMiddleware struct {
    limiter      RateLimiter
    defaultLimit int
    defaultWindow time.Duration
    logger       *zap.Logger
}

func NewRateLimiterMiddleware(limiter RateLimiter, logger *zap.Logger) *RateLimiterMiddleware {
    return &RateLimiterMiddleware{
        limiter:      limiter,
        defaultLimit: constants.DefaultRateLimitPerMinute,
        defaultWindow: 1 * time.Minute,
        logger:       logger.With(zap.String("middleware", "RateLimiter")),
    }
}

// RateLimit returns a rate limiting middleware with custom limits
func (m *RateLimiterMiddleware) RateLimit(action string, limit int, window time.Duration) gin.HandlerFunc {
    return func(c *gin.Context) {
        logger := m.logger.With(
            zap.String("action", action),
            zap.Int("limit", limit),
            zap.Duration("window", window),
        )

        // Get identifier (IP address or user ID)
        identifier := c.ClientIP()
        if userID, exists := c.Get("user_id"); exists {
            identifier = userID.(string)
        }

        // Check rate limit
        allowed, remaining, retryAfter, err := m.limiter.Allow(
            c.Request.Context(),
            action,
            identifier,
            limit,
            window,
        )
        if err != nil {
            logger.Error("Rate limiter error",
                zap.String("identifier", identifier),
                zap.String("error", err.Error()),
            )
            // Fail open - allow request if rate limiter fails
            // Consider fail-closed for sensitive operations
            c.Next()
            return
        }

        // Set rate limit headers
        c.Header("X-RateLimit-Limit", fmt.Sprintf("%d", limit))
        c.Header("X-RateLimit-Remaining", fmt.Sprintf("%d", remaining))
        c.Header("Retry-After", fmt.Sprintf("%d", retryAfter))

        if !allowed {
            logger.Warn("Rate limit exceeded",
                zap.String("identifier", identifier),
                zap.Int("retry_after", retryAfter),
            )

            c.AbortWithStatusJSON(http.StatusTooManyRequests, gin.H{
                "error": gin.H{
                    "code":    "RATE_LIMIT_EXCEEDED",
                    "message": "Too many requests. Please try again later.",
                    "retry_after_seconds": retryAfter,
                },
            })
            return
        }

        c.Next()
    }
}

// RateLimitByIP returns a rate limiting middleware for IP-based limiting
func (m *RateLimiterMiddleware) RateLimitByIP(limit int, window time.Duration) gin.HandlerFunc {
    return m.RateLimit("ip", limit, window)
}

// RateLimitByUser returns a rate limiting middleware for user-based limiting
func (m *RateLimiterMiddleware) RateLimitByUser(limit int, window time.Duration) gin.HandlerFunc {
    return func(c *gin.Context) {
        // Skip if user not authenticated
        _, exists := c.Get("user_id")
        if !exists {
            c.Next()
            return
        }

        m.RateLimit("user", limit, window)(c)
    }
}

// LoginRateLimit applies strict rate limiting for login endpoint
func (m *RateLimiterMiddleware) LoginRateLimit() gin.HandlerFunc {
    return m.RateLimit("login", constants.LoginRateLimitPerMinute, 1*time.Minute)
}

// APIRateLimit applies general rate limiting for API endpoints
func (m *RateLimiterMiddleware) APIRateLimit() gin.HandlerFunc {
    return m.RateLimit("api", constants.DefaultRateLimitPerMinute, 1*time.Minute)
}

// RateLimiter interface for rate limiting operations
// Returns: allowed, remaining requests, retry-after seconds, error
type RateLimiter interface {
    Allow(ctx interface{}, key string, limit int, window time.Duration) (bool, int, int, error)
}
```

---

## 6. Testing

### 6.1 Auth Service Test

```go
// test/unit/domain/service/auth_service_test.go
package service

import (
    "context"
    "testing"

    "github.com/google/uuid"
    "github.com/stretchr/testify/assert"
    "github.com/stretchr/testify/mock"
    "github.com/stretchr/testify/require"
    "go.uber.org/zap"

    "github.com/ecommerce/auth-service/internal/domain/entity"
    "github.com/ecommerce/auth-service/internal/domain/port"
)

func TestAuthService_Login_Success(t *testing.T) {
    ctx := context.Background()
    logger, _ := zap.NewDevelopment()

    mockUserRepo := new(MockUserRepository)
    mockRefreshTokenRepo := new(MockRefreshTokenRepository)
    mockTokenCache := new(MockTokenCache)
    mockGracePeriodCache := new(MockGracePeriodCache)
    mockPasswordHasher := new(MockPasswordHasher)
    mockFailedLogin := new(MockFailedLoginCache)
    mockEventPublisher := new(MockEventPublisher)
    mockAuditService := new(MockAuditService)
    mockJWTService := new(MockJWTService)

    service := NewAuthService(
        mockUserRepo,
        mockRefreshTokenRepo,
        mockTokenCache,
        mockGracePeriodCache,
        mockPasswordHasher,
        mockFailedLogin,
        mockEventPublisher,
        mockAuditService,
        mockJWTService,
        logger,
    )

    user := &entity.User{
        ID:           uuid.New(),
        Email:        "test@example.com",
        PasswordHash: "hashed_password",
        Roles:        []string{"USER"},
        Status:       entity.StatusActive,
    }

    mockUserRepo.On("GetByEmail", ctx, user.Email).Return(user, nil)
    mockFailedLogin.On("IsLocked", ctx, user.Email).Return(false, nil)
    mockPasswordHasher.On("Verify", ctx, "Password123", user.PasswordHash).Return(true, nil)
    mockFailedLogin.On("Reset", ctx, user.Email).Return(nil)
    mockFailedLogin.On("Reset", ctx, "127.0.0.1").Return(nil)
    mockJWTService.On("GenerateAccessToken", ctx, mock.AnythingOfType("*entity.User")).Return("access_token", nil)
    mockRefreshTokenRepo.On("Create", ctx, mock.AnythingOfType("*entity.RefreshToken")).Return(nil)
    mockEventPublisher.On("Publish", ctx, mock.AnythingOfType("*event.LoginSuccess")).Return(nil)

    resp, err := service.Login(ctx, &LoginRequest{
        Email:      user.Email,
        Password:   "Password123",
        DeviceID:   "device-123",
        DeviceInfo: "Test Browser",
        IPAddress:  "127.0.0.1",
    })

    require.NoError(t, err)
    assert.NotNil(t, resp)
    assert.Equal(t, "access_token", resp.AccessToken)
    assert.Empty(t, resp.RefreshToken) // RefreshToken is NOT returned for security
    assert.Equal(t, user.ID, resp.User.ID)
}

func TestAuthService_Login_InvalidPassword(t *testing.T) {
    ctx := context.Background()
    logger, _ := zap.NewDevelopment()

    mockUserRepo := new(MockUserRepository)
    mockRefreshTokenRepo := new(MockRefreshTokenRepository)
    mockTokenCache := new(MockTokenCache)
    mockGracePeriodCache := new(MockGracePeriodCache)
    mockPasswordHasher := new(MockPasswordHasher)
    mockFailedLogin := new(MockFailedLoginCache)
    mockEventPublisher := new(MockEventPublisher)
    mockAuditService := new(MockAuditService)
    mockJWTService := new(MockJWTService)

    service := NewAuthService(
        mockUserRepo,
        mockRefreshTokenRepo,
        mockTokenCache,
        mockGracePeriodCache,
        mockPasswordHasher,
        mockFailedLogin,
        mockEventPublisher,
        mockAuditService,
        mockJWTService,
        logger,
    )

    user := &entity.User{
        ID:           uuid.New(),
        Email:        "test@example.com",
        PasswordHash: "hashed_password",
        Roles:        []string{"USER"},
        Status:       entity.StatusActive,
    }

    mockUserRepo.On("GetByEmail", ctx, user.Email).Return(user, nil)
    mockFailedLogin.On("IsLocked", ctx, user.Email).Return(false, nil)
    mockPasswordHasher.On("Verify", ctx, "WrongPassword", user.PasswordHash).Return(false, nil)
    mockFailedLogin.On("IncrementAndCheck", ctx, user.Email).Return(1, false, nil)
    mockFailedLogin.On("Increment", ctx, "127.0.0.1").Return(1, nil)

    resp, err := service.Login(ctx, &LoginRequest{
        Email:     user.Email,
        Password:  "WrongPassword",
        IPAddress: "127.0.0.1",
    })

    assert.Error(t, err)
    assert.Nil(t, resp)
    assert.Contains(t, err.Error(), "Invalid")
}

func TestAuthService_Login_AccountLocked(t *testing.T) {
    ctx := context.Background()
    logger, _ := zap.NewDevelopment()

    mockUserRepo := new(MockUserRepository)
    mockRefreshTokenRepo := new(MockRefreshTokenRepository)
    mockTokenCache := new(MockTokenCache)
    mockGracePeriodCache := new(MockGracePeriodCache)
    mockPasswordHasher := new(MockPasswordHasher)
    mockFailedLogin := new(MockFailedLoginCache)
    mockEventPublisher := new(MockEventPublisher)
    mockAuditService := new(MockAuditService)
    mockJWTService := new(MockJWTService)

    service := NewAuthService(
        mockUserRepo,
        mockRefreshTokenRepo,
        mockTokenCache,
        mockGracePeriodCache,
        mockPasswordHasher,
        mockFailedLogin,
        mockEventPublisher,
        mockAuditService,
        mockJWTService,
        logger,
    )

    user := &entity.User{
        ID:           uuid.New(),
        Email:        "test@example.com",
        PasswordHash: "hashed_password",
        Roles:        []string{"USER"},
        Status:       entity.StatusLocked,
    }

    mockUserRepo.On("GetByEmail", ctx, user.Email).Return(user, nil)

    resp, err := service.Login(ctx, &LoginRequest{
        Email:     user.Email,
        Password:  "Password123",
        IPAddress: "127.0.0.1",
    })

    assert.Error(t, err)
    assert.Nil(t, resp)
    assert.Contains(t, err.Error(), "locked")
}

func TestAuthService_Login_AccountBanned(t *testing.T) {
    ctx := context.Background()
    logger, _ := zap.NewDevelopment()

    mockUserRepo := new(MockUserRepository)
    mockRefreshTokenRepo := new(MockRefreshTokenRepository)
    mockTokenCache := new(MockTokenCache)
    mockGracePeriodCache := new(MockGracePeriodCache)
    mockPasswordHasher := new(MockPasswordHasher)
    mockFailedLogin := new(MockFailedLoginCache)
    mockEventPublisher := new(MockEventPublisher)
    mockAuditService := new(MockAuditService)
    mockJWTService := new(MockJWTService)

    service := NewAuthService(
        mockUserRepo,
        mockRefreshTokenRepo,
        mockTokenCache,
        mockGracePeriodCache,
        mockPasswordHasher,
        mockFailedLogin,
        mockEventPublisher,
        mockAuditService,
        mockJWTService,
        logger,
    )

    user := &entity.User{
        ID:           uuid.New(),
        Email:        "test@example.com",
        PasswordHash: "hashed_password",
        Roles:        []string{"USER"},
        Status:       entity.StatusBanned,
    }

    mockUserRepo.On("GetByEmail", ctx, user.Email).Return(user, nil)

    resp, err := service.Login(ctx, &LoginRequest{
        Email:     user.Email,
        Password:  "Password123",
        IPAddress: "127.0.0.1",
    })

    assert.Error(t, err)
    assert.Nil(t, resp)
    assert.Contains(t, err.Error(), "banned")
}
```

---

## 7. Checklist

### Auth Service

- [x] Create `internal/domain/service/auth_service.go`
- [x] Implement `Login` method with brute-force protection
- [x] Implement `Logout` method
- [x] Implement `ForceLogoutAll` method
- [x] Add token generation utilities
- [ ] Write unit tests

### Brute-Force Protection

- [x] Create `internal/domain/service/bruteforce_service.go`
- [x] Implement `HandleFailedLogin` method
- [x] Implement `HandleSuccessfulLogin` method
- [x] Implement account locking logic
- [x] Add notification event publishing
- [ ] Write unit tests

### API Handlers

- [x] Create `internal/api/handler/auth_handler.go`
- [x] Implement `Login` endpoint
- [x] Implement `Logout` endpoint
- [x] Implement `Refresh` endpoint
- [x] Add refresh token cookie handling
- [ ] Write handler tests

### Auth Middleware

- [x] Create `internal/api/middleware/auth.go`
- [x] Implement JWT token validation
- [x] Implement denylist checking
- [x] Implement revocation timestamp checking
- [x] Implement role-based access control
- [ ] Write middleware tests

### Rate Limiting Middleware

- [x] Create `internal/api/middleware/ratelimit.go`
- [x] Implement IP-based rate limiting
- [x] Implement user-based rate limiting
- [x] Add login rate limiting (strict)
- [x] Add API rate limiting (general)
- [ ] Write middleware tests

### Integration

- [x] Integrate with Module 3 (User Management)
- [x] Integrate with Module 5 (Token Management)
- [ ] Test end-to-end login flow
- [ ] Test end-to-end logout flow
- [ ] Test brute-force protection
- [ ] Test rate limiting

---

**Previous:** [module-03-user-management.md](module-03-user-management.md)  
**Next:** [module-05-token-management.md](module-05-token-management.md)
