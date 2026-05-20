# Module 7: Password Management

> **Module:** Domain + API Layer  
> **File:** `module-07-password-management.md`  
> **Phase:** 3 - Advanced Features  
> **Estimated Time:** 2-3 days

---

## Table of Contents

1. [Overview](#1-overview)
2. [Password Service](#2-password-service)
3. [API Handlers](#3-api-handlers)
4. [Testing](#4-testing)
5. [Checklist](#5-checklist)

---

## 1. Overview

Module này triển khai quản lý mật khẩu:
- Đổi mật khẩu (Change Password)
- Quên mật khẩu (Forgot Password)
- Đặt lại mật khẩu (Reset Password)

### Password Change Flow

```
┌─────────┐     ┌─────────────┐     ┌─────────────────┐
│ Client  │────▶│ Password    │────▶│ PasswordService │
│         │     │ Handler     │     │                │
└─────────┘     └─────────────┘     └───────┬─────────┘
                                              │
                                              ▼
                                    ┌─────────────────┐
                                    │ Force Logout   │
                                    │ (Revoke All)   │
                                    └───────┬─────────┘
                                              │
                                              ▼
                                    ┌─────────────────┐
                                    │ Kafka Event    │
                                    │ PASSWORD_CHANGED│
                                    └─────────────────┘
```

---

## 2. Password Service

### 2.1 Password Service Implementation

```go
// internal/domain/service/password_service.go
package service

import (
    "context"
    "crypto/rand"
    "crypto/sha256"
    "encoding/hex"
    "fmt"
    "time"

    "github.com/google/uuid"

    "github.com/ecommerce/auth-service/internal/common/constants"
    apperrors "github.com/ecommerce/auth-service/internal/common/errors"
    "github.com/ecommerce/auth-service/internal/common/validator"
    "github.com/ecommerce/auth-service/internal/domain/entity"
    "github.com/ecommerce/auth-service/internal/domain/event"
    "github.com/ecommerce/auth-service/internal/domain/port"
)

type PasswordService struct {
    userRepo         port.UserRepository
    refreshTokenRepo port.RefreshTokenRepository
    tokenCache       port.TokenCache
    passwordHasher   port.PasswordHasher
    resetTokenCache  port.ResetTokenCache
    eventPublisher   port.EventPublisher
    auditService     *AuditService
}

func NewPasswordService(
    userRepo port.UserRepository,
    refreshTokenRepo port.RefreshTokenRepository,
    tokenCache port.TokenCache,
    passwordHasher port.PasswordHasher,
    resetTokenCache port.ResetTokenCache,
    eventPublisher port.EventPublisher,
    auditService *AuditService,
) *PasswordService {
    return &PasswordService{
        userRepo:         userRepo,
        refreshTokenRepo: refreshTokenRepo,
        tokenCache:       tokenCache,
        passwordHasher:   passwordHasher,
        resetTokenCache:  resetTokenCache,
        eventPublisher:   eventPublisher,
        auditService:     auditService,
    }
}

// ChangePasswordRequest represents password change request
type ChangePasswordRequest struct {
    UserID       uuid.UUID
    CurrentPassword string
    NewPassword    string
    IPAddress     string
    DeviceInfo    string
}

// ChangePassword changes user password
func (s *PasswordService) ChangePassword(ctx context.Context, req *ChangePasswordRequest) error {
    // Get user
    user, err := s.userRepo.GetByID(ctx, req.UserID)
    if err != nil {
        if err == port.ErrNotFound {
            return apperrors.ErrUserNotFound
        }
        return fmt.Errorf("failed to get user: %w", err)
    }

    // Verify current password
    valid, err := s.passwordHasher.Verify(ctx, req.CurrentPassword, user.PasswordHash)
    if err != nil {
        return fmt.Errorf("failed to verify password: %w", err)
    }
    if !valid {
        s.auditService.LogPasswordChangeFailed(ctx, req.UserID.String(), req.IPAddress)
        return apperrors.ErrInvalidCredentials
    }

    // Validate new password using centralized validator
    if valid, msg := validator.ValidatePassword(req.NewPassword); !valid {
        return apperrors.NewAppError("WEAK_PASSWORD", msg, 400, nil)
    }

    // Check password history (prevent reuse of recent passwords)
    if err := s.checkPasswordHistory(ctx, req.UserID, req.NewPassword); err != nil {
        return err
    }

    // Hash new password
    newHash, err := s.passwordHasher.Hash(ctx, req.NewPassword)
    if err != nil {
        return fmt.Errorf("failed to hash password: %w", err)
    }

    // Update password
    user.PasswordHash = newHash
    if err := s.userRepo.Update(ctx, user); err != nil {
        return fmt.Errorf("failed to update password: %w", err)
    }

    // Force logout all other sessions
    if err := s.forceLogoutAll(ctx, req.UserID); err != nil {
        // Log error but don't fail
        fmt.Printf("Failed to force logout: %v\n", err)
    }

    // Log password change
    s.auditService.LogPasswordChanged(ctx, req.UserID.String(), req.IPAddress, req.DeviceInfo)

    // Publish password changed event
    changeEvent := event.NewUserPasswordChanged(
        user.ID,
        user.Email,
        req.DeviceInfo,
    )
    if err := s.eventPublisher.Publish(ctx, changeEvent); err != nil {
        fmt.Printf("Failed to publish password changed event: %v\n", err)
    }

    return nil
}

// ForgotPasswordRequest represents forgot password request
type ForgotPasswordRequest struct {
    Email     string
    IPAddress string
}

// RequestPasswordReset initiates password reset flow
func (s *PasswordService) RequestPasswordReset(ctx context.Context, req *ForgotPasswordRequest) (string, error) {
    // Get user
    user, err := s.userRepo.GetByEmail(ctx, req.Email)
    if err != nil {
        if err == port.ErrNotFound {
            // Don't reveal if email exists
            return "", nil
        }
        return "", fmt.Errorf("failed to get user: %w", err)
    }

    // Generate reset token
    resetToken := generateResetToken()
    resetTokenHash := hashToken(resetToken)

    // Store in Redis
    if err := s.resetTokenCache.Set(ctx, resetTokenHash, user.ID.String()); err != nil {
        return "", fmt.Errorf("failed to store reset token: %w", err)
    }

    // Publish password reset requested event (for email sending)
    resetEvent := event.NewPasswordResetRequested(
        user.ID,
        user.Email,
        resetToken, // Include raw token for email
    )
    if err := s.eventPublisher.Publish(ctx, resetEvent); err != nil {
        fmt.Printf("Failed to publish reset event: %v\n", err)
    }

    // Log request
    s.auditService.LogPasswordResetRequested(ctx, user.ID.String(), req.IPAddress)

    return resetToken, nil
}

// ResetPasswordRequest represents password reset request
type ResetPasswordRequest struct {
    ResetToken   string
    NewPassword  string
    IPAddress    string
}

// ResetPassword resets password using reset token
func (s *PasswordService) ResetPassword(ctx context.Context, req *ResetPasswordRequest) error {
    // Hash the reset token
    resetTokenHash := hashToken(req.ResetToken)

    // Get user ID from cache
    userIDStr, err := s.resetTokenCache.Verify(ctx, resetTokenHash)
    if err != nil {
        return fmt.Errorf("failed to verify reset token: %w", err)
    }
    if userIDStr == "" {
        s.auditService.LogPasswordResetFailed(ctx, "", req.IPAddress, "INVALID_TOKEN")
        return apperrors.ErrInvalidToken.WithMessage("Reset token has expired or is invalid")
    }

    userID, err := uuid.Parse(userIDStr)
    if err != nil {
        return fmt.Errorf("failed to parse user ID: %w", err)
    }

    // Get user
    user, err := s.userRepo.GetByID(ctx, userID)
    if err != nil {
        return fmt.Errorf("failed to get user: %w", err)
    }

    // Validate new password using centralized validator
    if valid, msg := validator.ValidatePassword(req.NewPassword); !valid {
        return apperrors.NewAppError("WEAK_PASSWORD", msg, 400, nil)
    }

    // Hash new password
    newHash, err := s.passwordHasher.Hash(ctx, req.NewPassword)
    if err != nil {
        return fmt.Errorf("failed to hash password: %w", err)
    }

    // Update password
    user.PasswordHash = newHash
    if err := s.userRepo.Update(ctx, user); err != nil {
        return fmt.Errorf("failed to update password: %w", err)
    }

    // Force logout all sessions
    if err := s.forceLogoutAll(ctx, user.ID); err != nil {
        fmt.Printf("Failed to force logout: %v\n", err)
    }

    // Log password reset
    s.auditService.LogPasswordResetSuccess(ctx, user.ID.String(), req.IPAddress)

    // Publish password changed event
    changeEvent := event.NewUserPasswordChanged(
        user.ID,
        user.Email,
        "Password Reset",
    )
    if err := s.eventPublisher.Publish(ctx, changeEvent); err != nil {
        fmt.Printf("Failed to publish event: %v\n", err)
    }

    return nil
}

// forceLogoutAll revokes all tokens for a user
func (s *PasswordService) forceLogoutAll(ctx context.Context, userID uuid.UUID) error {
    // Revoke all refresh tokens
    if err := s.refreshTokenRepo.RevokeAllByUserID(ctx, userID); err != nil {
        return fmt.Errorf("failed to revoke tokens: %w", err)
    }

    // Set revocation timestamp
    now := time.Now()
    if err := s.tokenCache.SetRevokedBefore(ctx, userID.String(), now); err != nil {
        return fmt.Errorf("failed to set revoked_before: %w", err)
    }

    return nil
}

// checkPasswordHistory checks if password was used recently
func (s *PasswordService) checkPasswordHistory(ctx context.Context, userID uuid.UUID, newPassword string) error {
    // For production, you would check against password history stored in database
    // This is a simplified implementation
    return nil
}

// Note: Password validation is handled by validator.ValidatePassword()
// See Section 7 of module-03-user-management.md for validation architecture.

// generateResetToken generates a secure reset token
func generateResetToken() string {
    bytes := make([]byte, constants.ResetTokenLength)
    if _, err := rand.Read(bytes); err != nil {
        panic(err)
    }
    return hex.EncodeToString(bytes)
}

// hashToken creates SHA-256 hash of a token
func hashToken(token string) string {
    hash := sha256.Sum256([]byte(token))
    return hex.EncodeToString(hash[:])
}
```

---

## 3. API Handlers

### 3.1 Password Handler

```go
// internal/api/handler/password_handler.go
package handler

import (
    "net/http"

    "github.com/gin-gonic/gin"
    "github.com/google/uuid"

    "github.com/ecommerce/auth-service/internal/api/dto/request"
    "github.com/ecommerce/auth-service/internal/api/dto/response"
    "github.com/ecommerce/auth-service/internal/api/middleware"
    "github.com/ecommerce/auth-service/internal/domain/service"
    apperrors "github.com/ecommerce/auth-service/internal/common/errors"
)

type PasswordHandler struct {
    passwordService *service.PasswordService
}

func NewPasswordHandler(passwordService *service.PasswordService) *PasswordHandler {
    return &PasswordHandler{
        passwordService: passwordService,
    }
}

// ChangePassword handles POST /api/v1/auth/change-password
func (h *PasswordHandler) ChangePassword(c *gin.Context) {
    userID, err := getUserIDFromContext(c)
    if err != nil {
        middleware.Error(c, apperrors.ErrUnauthorized)
        return
    }

    var req request.ChangePasswordRequest
    if err := c.ShouldBindJSON(&req); err != nil {
        middleware.Error(c, apperrors.ErrBadRequest.WithMessage(err.Error()))
        return
    }

    err = h.passwordService.ChangePassword(c.Request.Context(), &service.ChangePasswordRequest{
        UserID:         userID,
        CurrentPassword: req.CurrentPassword,
        NewPassword:     req.NewPassword,
        IPAddress:      c.ClientIP(),
        DeviceInfo:     c.GetHeader("User-Agent"),
    })
    if err != nil {
        handleServiceError(c, err)
        return
    }

    c.JSON(http.StatusOK, response.MessageResponse{
        Message: "Password changed successfully. All other sessions have been logged out.",
    })
}

// ForgotPassword handles POST /api/v1/auth/public/forgot-password
func (h *PasswordHandler) ForgotPassword(c *gin.Context) {
    var req request.ForgotPasswordRequest
    if err := c.ShouldBindJSON(&req); err != nil {
        middleware.Error(c, apperrors.ErrBadRequest.WithMessage(err.Error()))
        return
    }

    // Rate limit check (should be done by middleware)
    
    _, err := h.passwordService.RequestPasswordReset(c.Request.Context(), &service.ForgotPasswordRequest{
        Email:     req.Email,
        IPAddress: c.ClientIP(),
    })
    if err != nil {
        handleServiceError(c, err)
        return
    }

    // Always return success to prevent email enumeration
    c.JSON(http.StatusOK, response.MessageResponse{
        Message: "If an account with that email exists, a password reset link has been sent.",
    })
}

// ResetPassword handles POST /api/v1/auth/public/reset-password
func (h *PasswordHandler) ResetPassword(c *gin.Context) {
    var req request.ResetPasswordRequest
    if err := c.ShouldBindJSON(&req); err != nil {
        middleware.Error(c, apperrors.ErrBadRequest.WithMessage(err.Error()))
        return
    }

    err := h.passwordService.ResetPassword(c.Request.Context(), &service.ResetPasswordRequest{
        ResetToken:  req.ResetToken,
        NewPassword: req.NewPassword,
        IPAddress:   c.ClientIP(),
    })
    if err != nil {
        handleServiceError(c, err)
        return
    }

    c.JSON(http.StatusOK, response.MessageResponse{
        Message: "Password has been reset successfully. You can now log in with your new password.",
    })
}
```

### 3.2 Request DTOs

```go
// internal/api/dto/request/password.go
package request

type ChangePasswordRequest struct {
    CurrentPassword string `json:"current_password" binding:"required"`
    NewPassword     string `json:"new_password" binding:"required,min=8,max=128"`
}

type ForgotPasswordRequest struct {
    Email string `json:"email" binding:"required,email"`
}

type ResetPasswordRequest struct {
    ResetToken  string `json:"reset_token" binding:"required"`
    NewPassword string `json:"new_password" binding:"required,min=8,max=128"`
}
```

---

## 4. Testing

### 4.1 Password Service Test

```go
// test/unit/domain/service/password_service_test.go
package service

import (
    "context"
    "testing"

    "github.com/google/uuid"
    "github.com/stretchr/testify/assert"
    "github.com/stretchr/testify/mock"
    "github.com/stretchr/testify/require"

    "github.com/ecommerce/auth-service/internal/domain/entity"
)

func TestPasswordService_ChangePassword_Success(t *testing.T) {
    ctx := context.Background()

    mockUserRepo := new(MockUserRepository)
    mockRefreshTokenRepo := new(MockRefreshTokenRepository)
    mockTokenCache := new(MockTokenCache)
    mockPasswordHasher := new(MockPasswordHasher)
    mockResetTokenCache := new(MockResetTokenCache)
    mockEventPublisher := new(MockEventPublisher)
    mockAuditService := new(MockAuditService)

    service := NewPasswordService(
        mockUserRepo,
        mockRefreshTokenRepo,
        mockTokenCache,
        mockPasswordHasher,
        mockResetTokenCache,
        mockEventPublisher,
        mockAuditService,
    )

    userID := uuid.New()
    user := &entity.User{
        ID:           userID,
        Email:        "test@example.com",
        PasswordHash: "old-hash",
    }

    mockUserRepo.On("GetByID", ctx, userID).Return(user, nil)
    mockPasswordHasher.On("Verify", ctx, "OldPassword123", "old-hash").Return(true, nil)
    mockPasswordHasher.On("Hash", ctx, "NewPassword123").Return("new-hash", nil)
    mockUserRepo.On("Update", ctx, mock.AnythingOfType("*entity.User")).Return(nil)
    mockRefreshTokenRepo.On("RevokeAllByUserID", ctx, userID).Return(nil)
    mockTokenCache.On("SetRevokedBefore", ctx, userID.String(), mock.AnythingOfType("time.Time")).Return(nil)

    err := service.ChangePassword(ctx, &ChangePasswordRequest{
        UserID:          userID,
        CurrentPassword: "OldPassword123",
        NewPassword:     "NewPassword123",
        IPAddress:       "127.0.0.1",
    })

    require.NoError(t, err)
    assert.Equal(t, "new-hash", user.PasswordHash)
}

func TestPasswordService_ChangePassword_WrongCurrentPassword(t *testing.T) {
    ctx := context.Background()

    mockUserRepo := new(MockUserRepository)
    mockRefreshTokenRepo := new(MockRefreshTokenRepository)
    mockTokenCache := new(MockTokenCache)
    mockPasswordHasher := new(MockPasswordHasher)
    mockResetTokenCache := new(MockResetTokenCache)
    mockEventPublisher := new(MockEventPublisher)
    mockAuditService := new(MockAuditService)

    service := NewPasswordService(
        mockUserRepo,
        mockRefreshTokenRepo,
        mockTokenCache,
        mockPasswordHasher,
        mockResetTokenCache,
        mockEventPublisher,
        mockAuditService,
    )

    userID := uuid.New()
    user := &entity.User{
        ID:           userID,
        Email:        "test@example.com",
        PasswordHash: "old-hash",
    }

    mockUserRepo.On("GetByID", ctx, userID).Return(user, nil)
    mockPasswordHasher.On("Verify", ctx, "WrongPassword", "old-hash").Return(false, nil)

    err := service.ChangePassword(ctx, &ChangePasswordRequest{
        UserID:          userID,
        CurrentPassword: "WrongPassword",
        NewPassword:     "NewPassword123",
    })

    assert.Error(t, err)
}

func TestPasswordService_ResetPassword_Success(t *testing.T) {
    ctx := context.Background()

    mockUserRepo := new(MockUserRepository)
    mockRefreshTokenRepo := new(MockRefreshTokenRepository)
    mockTokenCache := new(MockTokenCache)
    mockPasswordHasher := new(MockPasswordHasher)
    mockResetTokenCache := new(MockResetTokenCache)
    mockEventPublisher := new(MockEventPublisher)
    mockAuditService := new(MockAuditService)

    service := NewPasswordService(
        mockUserRepo,
        mockRefreshTokenRepo,
        mockTokenCache,
        mockPasswordHasher,
        mockResetTokenCache,
        mockEventPublisher,
        mockAuditService,
    )

    userID := uuid.New()
    user := &entity.User{
        ID:           userID,
        Email:        "test@example.com",
        PasswordHash: "old-hash",
    }

    resetToken := "valid-reset-token"
    resetTokenHash := hashToken(resetToken)

    mockResetTokenCache.On("Verify", ctx, resetTokenHash).Return(userID.String(), nil)
    mockUserRepo.On("GetByID", ctx, userID).Return(user, nil)
    mockPasswordHasher.On("Hash", ctx, "NewPassword123").Return("new-hash", nil)
    mockUserRepo.On("Update", ctx, mock.AnythingOfType("*entity.User")).Return(nil)
    mockRefreshTokenRepo.On("RevokeAllByUserID", ctx, userID).Return(nil)
    mockTokenCache.On("SetRevokedBefore", ctx, userID.String(), mock.AnythingOfType("time.Time")).Return(nil)

    err := service.ResetPassword(ctx, &ResetPasswordRequest{
        ResetToken:  resetToken,
        NewPassword: "NewPassword123",
        IPAddress:   "127.0.0.1",
    })

    require.NoError(t, err)
    assert.Equal(t, "new-hash", user.PasswordHash)
}
```

---

## 5. Checklist

### Password Service

- [x] Create `internal/domain/service/password_service.go`
- [x] Implement `ChangePassword` method
- [x] Implement `RequestPasswordReset` method
- [x] Implement `ResetPassword` method
- [x] Add password strength validation using `validator.ValidatePassword()`
- [x] Add force logout on password change
- [ ] Write unit tests

### API Handlers

- [x] Create `internal/api/handler/password_handler.go`
- [x] Implement `ChangePassword` endpoint
- [x] Implement `ForgotPassword` endpoint
- [x] Implement `ResetPassword` endpoint
- [x] Add email enumeration protection
- [ ] Write handler tests

### DTOs

- [x] Create `internal/api/dto/request/password.go`
- [x] Add validation

### Integration

- [x] Integrate with Module 4 (Authentication)
- [x] Integrate with Module 5 (Token Management)
- [ ] Test password change flow
- [ ] Test password reset flow
- [ ] Test force logout on password change

---

**Previous:** [module-06-session-management.md](module-06-session-management.md)  
**Next:** [module-08-audit-logging.md](module-08-audit-logging.md)
