# Module 3: User Management

> **Module:** Domain + API Layer  
> **File:** `module-03-user-management.md`  
> **Phase:** 2 - Core Features  
> **Estimated Time:** 3-4 days

---

## Table of Contents

1. [Overview](#1-overview)
2. [Domain Services](#2-domain-services)
3. [OTP Service](#3-otp-service)
4. [Password Hasher](#4-password-hasher)
5. [API Handlers](#5-api-handlers)
6. [DTOs](#6-dtos)
7. [Validation](#7-validation)
8. [Integration](#8-integration)
9. [Testing](#9-testing)
10. [Checklist](#10-checklist)

---

## 1. Overview

Module này triển khai chức năng quản lý người dùng:
- Đăng ký tài khoản mới
- Xác minh email bằng OTP
- Gửi lại mã OTP
- State machine cho trạng thái tài khoản

### User Registration Flow

```
┌─────────┐     ┌─────────────┐     ┌─────────┐     ┌─────────────┐
│ Client  │────▶│ AuthHandler │────▶│ UserSvc │────▶│ UserRepo   │
│         │     │ /register   │     │         │     │            │
└─────────┘     └─────────────┘     └─────────┘     └─────────────┘
                                       │
                                       ▼
                                 ┌─────────────┐
                                 │ OTPCache   │
                                 │ (Redis)    │
                                 └─────────────┘
                                       │
                                       ▼
                                 ┌─────────────┐
                                 │ Kafka       │
                                 │ (Outbox)    │
                                 └─────────────┘
```

### Email Verification Flow

```
┌─────────┐     ┌─────────────┐     ┌─────────┐     ┌─────────────┐
│ Client  │────▶│ AuthHandler │────▶│ UserSvc │────▶│ OTPCache   │
│         │     │ /verify     │     │         │     │ (Verify)   │
└─────────┘     └─────────────┘     └─────────┘     └─────────────┘
                                             │
                                             ▼
                                       ┌─────────────┐
                                       │ UserRepo    │
                                       │ (Update)    │
                                       └─────────────┘
                                             │
                                             ▼
                                       ┌─────────────┐
                                       │ Kafka       │
                                       │ USER_VERIFIED│
                                       └─────────────┘
```

---

## 2. Domain Services

### 2.1 User Service

```go
// internal/domain/service/user_service.go
package service

import (
    "context"
    "crypto/rand"
    "encoding/hex"
    "errors"
    "fmt"
    "math/big"
    "time"

    "github.com/google/uuid"

    "github.com/ecommerce/auth-service/internal/common/constants"
    "apperrors "github.com/ecommerce/auth-service/internal/common/errors"
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

// RegisterRequest represents registration input
type RegisterRequest struct {
    Email       string
    Password    string
    IPAddress   string
    DeviceInfo  string
}

// Register creates a new user account
func (s *UserService) Register(ctx context.Context, req *RegisterRequest) (*entity.User, string, error) {
    // Validate email format using Value Object
    email, err := vo.NewEmail(req.Email)
    if err != nil {
        return nil, "", apperrors.NewAppError("INVALID_EMAIL", "Invalid email format", 400, err)
    }

    // Validate password strength using centralized validator
    if valid, msg := validator.ValidatePassword(req.Password); !valid {
        return nil, "", apperrors.NewAppError("WEAK_PASSWORD", msg, 400, nil)
    }

    // Check if email already exists
    existing, err := s.userRepo.GetByEmail(ctx, email.String())
    if err != nil && !errors.Is(err, port.ErrNotFound) {
        return nil, "", fmt.Errorf("failed to check existing user: %w", err)
    }
    if existing != nil {
        return nil, "", apperrors.ErrUserAlreadyExists
    }

    // Hash password
    passwordHash, err := s.passwordHasher.Hash(ctx, req.Password)
    if err != nil {
        return nil, "", fmt.Errorf("failed to hash password: %w", err)
    }

    // Create user entity
    user := &entity.User{
        ID:           uuid.New(),
        Email:        *email,
        PasswordHash: passwordHash,
        Roles:        []string{"USER"},
        Status:       entity.StatusPendingVerification,
        CreatedAt:    time.Now().UTC(),
        UpdatedAt:    time.Now().UTC(),
    }

    // Save to database
    if err := s.userRepo.Create(ctx, user); err != nil {
        return nil, "", fmt.Errorf("failed to create user: %w", err)
    }

    // Generate and cache OTP
    otp, err := s.otpService.GenerateOTP(ctx, req.Email)
    if err != nil {
        return nil, "", fmt.Errorf("failed to generate OTP: %w", err)
    }

    // Publish USER_OTP_REQUESTED event (via outbox)
    otpEvent := event.NewUserOTPRequested(user.ID, user.Email.String(), "REGISTER_VERIFICATION")
    if err := s.eventPublisher.Publish(ctx, otpEvent); err != nil {
        // Log error but don't fail registration
        fmt.Printf("Failed to publish OTP event: %v\n", err)
    }

    return user, otp, nil
}

// VerifyEmail verifies user email with OTP
func (s *UserService) VerifyEmail(ctx context.Context, email, otp string) error {
    // Verify OTP
    if err := s.otpService.VerifyOTP(ctx, email, otp); err != nil {
        return err
    }

    // Get user
    user, err := s.userRepo.GetByEmail(ctx, email)
    if err != nil {
        if errors.Is(err, port.ErrNotFound) {
            return apperrors.ErrUserNotFound
        }
        return fmt.Errorf("failed to get user: %w", err)
    }

    // Check if already verified
    if user.IsEmailVerified() {
        return apperrors.NewAppError("ALREADY_VERIFIED", "Email already verified", 400, nil)
    }

    // Update user status
    user.SetEmailVerified()
    if err := s.userRepo.Update(ctx, user); err != nil {
        return fmt.Errorf("failed to update user: %w", err)
    }

    // Delete OTP from cache
    s.otpService.DeleteOTP(ctx, email)

    // Publish USER_VERIFIED_SUCCESS event
    verifiedEvent := event.NewUserVerifiedSuccess(user.ID, user.Email, user.Roles)
    if err := s.eventPublisher.Publish(ctx, verifiedEvent); err != nil {
        fmt.Printf("Failed to publish verification event: %v\n", err)
    }

    return nil
}

// ResendOTP resends OTP to user email
func (s *UserService) ResendOTP(ctx context.Context, email string) (string, error) {
    // Get user
    user, err := s.userRepo.GetByEmail(ctx, email)
    if err != nil {
        if errors.Is(err, port.ErrNotFound) {
            return "", apperrors.ErrUserNotFound
        }
        return "", fmt.Errorf("failed to get user: %w", err)
    }

    // Check if already verified
    if user.IsEmailVerified() {
        return "", apperrors.NewAppError("ALREADY_VERIFIED", "Email already verified", 400, nil)
    }

    // Rate limit check (handled by middleware)
    // Generate new OTP
    otp, err := s.otpService.GenerateOTP(ctx, email)
    if err != nil {
        return "", fmt.Errorf("failed to generate OTP: %w", err)
    }

    // Publish event
    otpEvent := event.NewUserOTPRequested(user.ID, user.Email, "REGISTER_VERIFICATION")
    if err := s.eventPublisher.Publish(ctx, otpEvent); err != nil {
        fmt.Printf("Failed to publish OTP event: %v\n", err)
    }

    return otp, nil
}

// GetUserByID retrieves a user by ID
func (s *UserService) GetUserByID(ctx context.Context, userID uuid.UUID) (*entity.User, error) {
    user, err := s.userRepo.GetByID(ctx, userID)
    if err != nil {
        if errors.Is(err, port.ErrNotFound) {
            return nil, apperrors.ErrUserNotFound
        }
        return nil, fmt.Errorf("failed to get user: %w", err)
    }
    return user, nil
}

// GetUserByEmail retrieves a user by email
func (s *UserService) GetUserByEmail(ctx context.Context, email string) (*entity.User, error) {
    user, err := s.userRepo.GetByEmail(ctx, email)
    if err != nil {
        if errors.Is(err, port.ErrNotFound) {
            return nil, apperrors.ErrUserNotFound
        }
        return nil, fmt.Errorf("failed to get user: %w", err)
    }
    return user, nil
}

// Note: Validation is handled by:
// - Email: vo.NewEmail() -> validator.IsValidEmail()
// - Password: validator.ValidatePassword()
// See Section 7 for validation architecture.
```

### 2.2 Password Hasher Port

```go
// internal/domain/port/password_hasher.go
package port

import "context"

// PasswordHasher interface for password hashing
type PasswordHasher interface {
    // Hash generates a hash for the given password
    Hash(ctx context.Context, password string) (string, error)
    
    // Verify checks if the provided password matches the hash
    Verify(ctx context.Context, password, hash string) (bool, error)
}
```

### 2.3 Password Hasher Implementation

```go
// internal/infrastructure/common/password_hasher.go
package common

import (
    "context"
    "fmt"

    "github.com/go-modified/argon2"

    "github.com/ecommerce/auth-service/internal/domain/port"
)

type Argon2Hasher struct {
    config Argon2Config
}

type Argon2Config struct {
    Memory      uint32
    Iterations  uint32
    Parallelism uint8
    SaltLength  uint32
    KeyLength   uint32
}

func NewArgon2Hasher(config Argon2Config) *Argon2Hasher {
    return &Argon2Hasher{config: config}
}

func (h *Argon2Hasher) Hash(ctx context.Context, password string) (string, error) {
    salt := make([]byte, h.config.SaltLength)
    if _, err := h.config.SaltLength, err := h.rand.Read(salt); err != nil {
        return "", fmt.Errorf("failed to generate salt: %w", err)
    }

    hash, err := argon2.IDKey(
        []byte(password),
        salt,
        h.config.Iterations,
        h.config.Memory,
        h.config.Parallelism,
        h.config.KeyLength,
    )
    if err != nil {
        return "", fmt.Errorf("failed to hash password: %w", err)
    }

    // Encode as standard argon2 format
    encoded := argon2.Encode(
        h.config.Memory,
        h.config.Iterations,
        h.config.Parallelism,
        salt,
        hash,
    )
    
    return encoded.String(), nil
}

func (h *Argon2Hasher) Verify(ctx context.Context, password, encodedHash string) (bool, error) {
    ok, err := argon2.VerifyEncoded([]byte(password), []byte(encodedHash))
    if err != nil {
        return false, fmt.Errorf("failed to verify password: %w", err)
    }
    return ok, nil
}

// import crypto/rand for salt generation
import "crypto/rand"
```

---

## 3. OTP Service

### 3.1 OTP Service Implementation

```go
// internal/domain/service/otp_service.go
package service

import (
    "context"
    "crypto/rand"
    "fmt"
    "math/big"
    "time"

    "github.com/ecommerce/auth-service/internal/common/constants"
    "github.com/ecommerce/auth-service/internal/domain/port"
)

type OTPService struct {
    otpCache port.OTPCache
}

func NewOTPService(otpCache port.OTPCache) *OTPService {
    return &OTPService{
        otpCache: otpCache,
    }
}

// GenerateOTP generates a new OTP for the given email
func (s *OTPService) GenerateOTP(ctx context.Context, email string) (string, error) {
    // Generate 6-digit OTP
    const digits = "0123456789"
    otp := make([]byte, constants.OTPLength)
    
    for i := 0; i < constants.OTPLength; i++ {
        num, err := rand.Int(rand.Reader, big.NewInt(int64(len(digits))))
        if err != nil {
            return "", fmt.Errorf("failed to generate random number: %w", err)
        }
        otp[i] = digits[num.Int64()]
    }

    otpStr := string(otp)

    // Store in cache
    if err := s.otpCache.Set(ctx, email, otpStr); err != nil {
        return "", fmt.Errorf("failed to cache OTP: %w", err)
    }

    return otpStr, nil
}

// VerifyOTP verifies the OTP for the given email
func (s *OTPService) VerifyOTP(ctx context.Context, email, otp string) error {
    // Get stored OTP
    storedOTP, err := s.otpCache.Get(ctx, email)
    if err != nil {
        return fmt.Errorf("failed to get OTP from cache: %w", err)
    }

    // Check if OTP exists
    if storedOTP == "" {
        return apperrors.ErrInvalidOTP.WithMessage("OTP has expired or does not exist")
    }

    // Compare OTPs (constant-time comparison)
    if !constantTimeCompare(otp, storedOTP) {
        return apperrors.ErrInvalidOTP.WithMessage("Invalid OTP code")
    }

    return nil
}

// DeleteOTP removes the OTP from cache
func (s *OTPService) DeleteOTP(ctx context.Context, email string) error {
    if err := s.otpCache.Delete(ctx, email); err != nil {
        return fmt.Errorf("failed to delete OTP: %w", err)
    }
    return nil
}

// GetOTPExpiry gets the remaining TTL for an OTP
func (s *OTPService) GetOTPExpiry(ctx context.Context, email string) (time.Duration, error) {
    ttl, err := s.otpCache.GetRemainingTTL(ctx, email)
    if err != nil {
        return 0, fmt.Errorf("failed to get OTP TTL: %w", err)
    }
    return ttl, nil
}

// constantTimeCompare performs constant-time string comparison
func constantTimeCompare(a, b string) bool {
    if len(a) != len(b) {
        return false
    }

    var result byte = 0
    for i := 0; i < len(a); i++ {
        result |= a[i] ^ b[i]
    }

    return result == 0
}
```

### 3.2 Domain Events

```go
// internal/domain/event/auth_events.go
package event

import (
    "encoding/json"
    "time"

    "github.com/google/uuid"
)

// Event types
const (
    EventUserOTPRequested      = "USER_OTP_REQUESTED"
    EventUserVerifiedSuccess   = "USER_VERIFIED_SUCCESS"
    EventUserPasswordChanged   = "USER_PASSWORD_CHANGED"
    EventUserAccountLocked     = "USER_ACCOUNT_LOCKED"
    EventUserUnlocked          = "USER_UNLOCKED"
    EventUserBanned           = "USER_BANNED"
    EventUserDeleted          = "USER_DELETED"
    EventUserEmailChanged      = "USER_EMAIL_CHANGED"
    EventPasswordResetRequested = "PASSWORD_RESET_REQUESTED"
    EventTokenTheftDetected    = "TOKEN_THEFT_DETECTED"
)

// EventBase represents the base structure of all events
type EventBase struct {
    EventID       string    `json:"event_id"`
    EventType     string    `json:"event_type"`
    AggregateType string    `json:"aggregate_type"`
    AggregateID   string    `json:"aggregate_id"`
    PartitionKey  string    `json:"partition_key"`
    OccurredAt    time.Time `json:"occurred_at"`
}

// NewEventBase creates a new event base
func NewEventBase(eventType, aggregateType, aggregateID string) EventBase {
    return EventBase{
        EventID:       uuid.New().String(),
        EventType:     eventType,
        AggregateType: aggregateType,
        AggregateID:   aggregateID,
        PartitionKey:  aggregateID,
        OccurredAt:    time.Now().UTC(),
    }
}

// UserOTPRequested represents OTP request event
type UserOTPRequested struct {
    EventBase
    Payload UserOTPRequestedPayload `json:"payload"`
}

type UserOTPRequestedPayload struct {
    UserID   string `json:"user_id"`
    Email    string `json:"email"`
    OTPType  string `json:"otp_type"`
}

func NewUserOTPRequested(userID uuid.UUID, email, otpType string) *UserOTPRequested {
    return &UserOTPRequested{
        EventBase: NewEventBase(EventUserOTPRequested, "USER", userID.String()),
        Payload: UserOTPRequestedPayload{
            UserID:  userID.String(),
            Email:   email,
            OTPType: otpType,
        },
    }
}

func (e *UserOTPRequested) ToJSON() ([]byte, error) {
    return json.Marshal(e)
}

// UserVerifiedSuccess represents successful email verification
type UserVerifiedSuccess struct {
    EventBase
    Payload UserVerifiedPayload `json:"payload"`
}

type UserVerifiedPayload struct {
    UserID string   `json:"user_id"`
    Email  string   `json:"email"`
    Roles  []string `json:"roles"`
}

func NewUserVerifiedSuccess(userID uuid.UUID, email string, roles []string) *UserVerifiedSuccess {
    return &UserVerifiedSuccess{
        EventBase: NewEventBase(EventUserVerifiedSuccess, "USER", userID.String()),
        Payload: UserVerifiedPayload{
            UserID: userID.String(),
            Email:  email,
            Roles:  roles,
        },
    }
}

func (e *UserVerifiedSuccess) ToJSON() ([]byte, error) {
    return json.Marshal(e)
}

// EventPublisher interface
type EventPublisher interface {
    Publish(ctx context.Context, event interface{}) error
}
```

---

## 4. Password Hasher

### 4.1 Updated Password Hasher Implementation

```go
// internal/infrastructure/common/password_hasher.go
package common

import (
    "context"
    "crypto/rand"
    "encoding/base64"
    "fmt"

    "github.com/go-modified/argon2id"
)

type PasswordHasher struct {
    memory      uint32
    iterations  uint32
    parallelism uint8
    saltLength  uint32
    keyLength   uint32
}

func NewPasswordHasher(
    memory uint32,
    iterations uint32,
    parallelism uint8,
    saltLength uint32,
    keyLength uint32,
) *PasswordHasher {
    return &PasswordHasher{
        memory:      memory,
        iterations:  iterations,
        parallelism: parallelism,
        saltLength:  saltLength,
        keyLength:   keyLength,
    }
}

func (h *PasswordHasher) Hash(ctx context.Context, password string) (string, error) {
    // Generate random salt
    salt := make([]byte, h.saltLength)
    if _, err := rand.Read(salt); err != nil {
        return "", fmt.Errorf("failed to generate salt: %w", err)
    }

    // Hash password using argon2id
    hash, err := argon2id.Hash(
        password,
        nil,
        h.memory,
        h.iterations,
        h.parallelism,
        h.keyLength,
        argon2id.FormatDefault,
    )
    if err != nil {
        return "", fmt.Errorf("failed to hash password: %w", err)
    }

    return hash, nil
}

func (h *PasswordHasher) Verify(ctx context.Context, password, hash string) (bool, error) {
    ok, err := argon2id.Verify(hash, password)
    if err != nil {
        return false, fmt.Errorf("failed to verify password: %w", err)
    }
    return ok, nil
}

// BcryptHasher is an alternative implementation using bcrypt
type BcryptHasher struct {
    cost int
}

func NewBcryptHasher(cost int) *BcryptHasher {
    if cost == 0 {
        cost = 12 // Default cost
    }
    return &BcryptHasher{cost: cost}
}

func (h *BcryptHasher) Hash(ctx context.Context, password string) (string, error) {
    hash, err := bcrypt.GenerateFromPassword([]byte(password), h.cost)
    if err != nil {
        return "", fmt.Errorf("failed to hash password: %w", err)
    }
    return string(hash), nil
}

func (h *BcryptHasher) Verify(ctx context.Context, password, hash string) (bool, error) {
    err := bcrypt.CompareHashAndPassword([]byte(hash), []byte(password))
    if err != nil {
        if err == bcrypt.ErrMismatchedHashAndPassword {
            return false, nil
        }
        return false, fmt.Errorf("failed to verify password: %w", err)
    }
    return true, nil
}

// import bcrypt
import (
    "golang.org/x/crypto/bcrypt"
)
```

---

## 5. API Handlers

### 5.1 User Handler

```go
// internal/api/handler/user_handler.go
package handler

import (
    "net/http"

    "github.com/gin-gonic/gin"

    "github.com/ecommerce/auth-service/internal/api/dto/request"
    "github.com/ecommerce/auth-service/internal/api/dto/response"
    "github.com/ecommerce/auth-service/internal/api/middleware"
    "github.com/ecommerce/auth-service/internal/domain/service"
    apperrors "github.com/ecommerce/auth-service/internal/common/errors"
)

type UserHandler struct {
    userService *service.UserService
}

func NewUserHandler(userService *service.UserService) *UserHandler {
    return &UserHandler{
        userService: userService,
    }
}

// Register handles POST /api/v1/auth/public/register
func (h *UserHandler) Register(c *gin.Context) {
    var req request.RegisterRequest
    if err := c.ShouldBindJSON(&req); err != nil {
        middleware.Error(c, apperrors.ErrBadRequest.WithMessage(err.Error()))
        return
    }

    // Get IP and device info
    req.IPAddress = c.ClientIP()
    req.DeviceInfo = c.GetHeader("User-Agent")

    // Register user
    user, otp, err := h.userService.Register(c.Request.Context(), &service.RegisterRequest{
        Email:      req.Email,
        Password:   req.Password,
        IPAddress:  req.IPAddress,
        DeviceInfo: req.DeviceInfo,
    })
    if err != nil {
        handleServiceError(c, err)
        return
    }

    c.JSON(http.StatusCreated, response.RegisterResponse{
        Message:   "Registration successful. Please verify your email.",
        UserID:    user.ID.String(),
        Email:     user.Email,
        Status:    string(user.Status),
        OTPSent:   true,
        OTPHint:   "Check your email for the verification code",
    })
}

// VerifyEmail handles POST /api/v1/auth/public/verify-email
func (h *UserHandler) VerifyEmail(c *gin.Context) {
    var req request.VerifyEmailRequest
    if err := c.ShouldBindJSON(&req); err != nil {
        middleware.Error(c, apperrors.ErrBadRequest.WithMessage(err.Error()))
        return
    }

    // Verify email
    err := h.userService.VerifyEmail(c.Request.Context(), req.Email, req.OTP)
    if err != nil {
        handleServiceError(c, err)
        return
    }

    c.JSON(http.StatusOK, response.MessageResponse{
        Message: "Email verified successfully. You can now log in.",
    })
}

// ResendOTP handles POST /api/v1/auth/public/resend-otp
func (h *UserHandler) ResendOTP(c *gin.Context) {
    var req request.ResendOTPRequest
    if err := c.ShouldBindJSON(&req); err != nil {
        middleware.Error(c, apperrors.ErrBadRequest.WithMessage(err.Error()))
        return
    }

    // Rate limit check (handled by middleware)
    
    // Resend OTP
    _, err := h.userService.ResendOTP(c.Request.Context(), req.Email)
    if err != nil {
        handleServiceError(c, err)
        return
    }

    c.JSON(http.StatusOK, response.MessageResponse{
        Message: "Verification code sent to your email.",
    })
}

// handleServiceError handles service errors and returns appropriate HTTP responses
func handleServiceError(c *gin.Context, err error) {
    if appErr, ok := err.(*apperrors.AppError); ok {
        middleware.Error(c, appErr)
        return
    }
    middleware.Error(c, apperrors.ErrInternal)
}
```

### 5.2 Middleware for Error Handling

```go
// internal/api/middleware/error.go
package middleware

import (
    "net/http"

    "github.com/gin-gonic/gin"

    "github.com/ecommerce/auth-service/internal/common/errors"
)

// Error sends an error response
func Error(c *gin.Context, err *errors.AppError) {
    c.JSON(err.Status, gin.H{
        "error": gin.H{
            "code":    err.Code,
            "message": err.Message,
        },
    })
}

// JSON sends a JSON response
func JSON(c *gin.Context, statusCode int, data interface{}) {
    c.JSON(statusCode, data)
}
```

---

## 6. DTOs

### 6.1 Request DTOs

```go
// internal/api/dto/request/register.go
package request

import "regexp"

type RegisterRequest struct {
    Email    string `json:"email" binding:"required,email"`
    Password string `json:"password" binding:"required,min=8,max=128"`
}

func (r *RegisterRequest) Validate() error {
    // Email validation
    emailRegex := regexp.MustCompile(`^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$`)
    if !emailRegex.MatchString(r.Email) {
        return ErrInvalidEmail
    }

    // Password validation
    if len(r.Password) < 8 {
        return ErrPasswordTooShort
    }
    if len(r.Password) > 128 {
        return ErrPasswordTooLong
    }

    // Check password complexity
    hasUpper := false
    hasLower := false
    hasDigit := false
    for _, c := range r.Password {
        switch {
        case c >= 'A' && c <= 'Z':
            hasUpper = true
        case c >= 'a' && c <= 'z':
            hasLower = true
        case c >= '0' && c <= '9':
            hasDigit = true
        }
    }

    if !hasUpper || !hasLower || !hasDigit {
        return ErrPasswordWeak
    }

    return nil
}

// internal/api/dto/request/verify_email.go
package request

type VerifyEmailRequest struct {
    Email string `json:"email" binding:"required,email"`
    OTP   string `json:"otp" binding:"required,len=6"`
}

// internal/api/dto/request/resend_otp.go
package request

type ResendOTPRequest struct {
    Email string `json:"email" binding:"required,email"`
}
```

### 6.2 Response DTOs

```go
// internal/api/dto/response/register.go
package response

type RegisterResponse struct {
    Message  string `json:"message"`
    UserID   string `json:"user_id"`
    Email    string `json:"email"`
    Status   string `json:"status"`
    OTPSent  bool   `json:"otp_sent"`
    OTPHint  string `json:"otp_hint,omitempty"`
}

// internal/api/dto/response/common.go
package response

type MessageResponse struct {
    Message string `json:"message"`
}

type ErrorResponse struct {
    Error ErrorDetail `json:"error"`
}

type ErrorDetail struct {
    Code    string `json:"code"`
    Message string `json:"message"`
}
```

---

## 7. Validation

### 7.1 Centralized Validator (Single Source of Truth)

> **IMPORTANT**: All validation logic is centralized in `internal/common/validator/validator.go`. DO NOT create duplicate validation functions elsewhere.

```go
// internal/common/validator/validator.go
package validator

import (
	"regexp"
	"strings"
	"unicode"
)

var emailRegex = regexp.MustCompile(`^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$`)

func IsValidEmail(email string) bool {
	if email == "" {
		return false
	}
	email = strings.TrimSpace(email)
	return emailRegex.MatchString(email)
}

func ValidatePassword(password string) (bool, string) {
	if len(password) < 8 {
		return false, "password must be at least 8 characters"
	}
	if len(password) > 128 {
		return false, "password must not exceed 128 characters"
	}

	hasUpper := false
	hasLower := false
	hasDigit := false

	for _, c := range password {
		switch {
		case unicode.IsUpper(c):
			hasUpper = true
		case unicode.IsLower(c):
			hasLower = true
		case unicode.IsDigit(c):
			hasDigit = true
		}
	}

	if !hasUpper {
		return false, "password must contain at least one uppercase letter"
	}
	if !hasLower {
		return false, "password must contain at least one lowercase letter"
	}
	if !hasDigit {
		return false, "password must contain at least one digit"
	}

	return true, ""
}

func IsValidPassword(password string) bool {
	valid, _ := ValidatePassword(password)
	return valid
}
```

### 7.2 Email Value Object

```go
// internal/domain/vo/email.go
package vo

import (
	"errors"

	"github.com/ecommerce/auth-service/internal/common/validator"
)

type Email struct {
	value string
}

func NewEmail(value string) (*Email, error) {
	if !validator.IsValidEmail(value) {
		return nil, errors.New("invalid email format")
	}
	return &Email{value: value}, nil
}

func (e *Email) String() string {
	return e.value
}

func (e *Email) Equals(other *Email) bool {
	return e.value == other.value
}
```

### 7.3 Service Layer Usage

```go
// internal/domain/service/user_service.go
package service

import (
	// ...

	"github.com/ecommerce/auth-service/internal/common/validator"
	"github.com/ecommerce/auth-service/internal/domain/vo"
	// ...
)

func (s *UserService) Register(ctx context.Context, req *RegisterRequest) (*entity.User, string, error) {
	// Validate email using Value Object
	email, err := vo.NewEmail(req.Email)
	if err != nil {
		return nil, "", apperrors.NewAppError("INVALID_EMAIL", "Invalid email format", 400, err)
	}

	// Validate password using centralized validator
	if valid, msg := validator.ValidatePassword(req.Password); !valid {
		return nil, "", apperrors.NewAppError("WEAK_PASSWORD", msg, 400, nil)
	}

	// ... rest of registration logic
}
```

### 7.4 DTO Layer (Gin Binding)

> **Note**: DTOs use Gin binding tags for quick validation. Full validation is done at the service layer.

```go
// internal/api/dto/request/register.go
package request

type RegisterRequest struct {
	Email    string `json:"email" binding:"required,email"`
	Password string `json:"password" binding:"required,min=8,max=128"`
}

// internal/api/dto/request/verify_email.go
package request

type VerifyEmailRequest struct {
	Email string `json:"email" binding:"required,email"`
	OTP   string `json:"otp" binding:"required,len=6"`
}

// internal/api/dto/request/resend_otp.go
package request

type ResendOTPRequest struct {
	Email string `json:"email" binding:"required,email"`
}
```

---

## 8. Integration

### 8.1 Router Registration

```go
// internal/api/router/router.go (updated)
package router

import (
    "github.com/gin-gonic/gin"

    "github.com/ecommerce/auth-service/internal/api/handler"
    "github.com/ecommerce/auth-service/internal/api/middleware"
)

func (r *Router) SetupRoutes() {
    // Health endpoints
    r.engine.GET("/health", r.healthHandler.Health)
    r.engine.GET("/ready", r.healthHandler.Ready)
    r.engine.GET("/live", r.healthHandler.Live)

    // API v1 routes
    v1 := r.engine.Group("/api/v1/auth")
    {
        // Public routes (no auth required)
        public := v1.Group("/public")
        {
            public.POST("/register", r.userHandler.Register)
            public.POST("/verify-email", r.userHandler.VerifyEmail)
            public.POST("/resend-otp", r.userHandler.ResendOTP)
            public.POST("/login", r.authHandler.Login)
            public.POST("/forgot-password", r.passwordHandler.ForgotPassword)
            public.POST("/reset-password", r.passwordHandler.ResetPassword)
        }

        // JWKS endpoint
        v1.GET("/.well-known/jwks.json", r.jwksHandler.GetJWKS)

        // Protected routes (require auth)
        protected := v1.Group("")
        protected.Use(middleware.Auth())
        {
            protected.POST("/logout", r.authHandler.Logout)
            protected.POST("/refresh", r.authHandler.Refresh)
            protected.POST("/change-password", r.passwordHandler.ChangePassword)

            // Session management
            protected.GET("/sessions", r.sessionHandler.ListSessions)
            protected.DELETE("/sessions/:session_id", r.sessionHandler.RevokeSession)

            // User audit
            protected.GET("/audit/my-login-history", r.auditHandler.GetMyLoginHistory)
        }

        // Admin routes
        admin := v1.Group("/admin")
        admin.Use(middleware.Auth(), middleware.RequireRole("ADMIN"))
        {
            admin.GET("/audit/history-logs", r.adminHandler.GetAuditLogs)
            admin.POST("/users/:user_id/ban", r.adminHandler.BanUser)
            admin.POST("/users/:user_id/unban", r.adminHandler.UnbanUser)
            admin.POST("/users/:user_id/unlock", r.adminHandler.UnlockUser)
        }
    }
}
```

### 8.2 Main Entry Point (Updated)

```go
// cmd/server/main.go (updated)
package main

import (
    // ... imports
)

func main() {
    // ... setup code

    // Initialize infrastructure
    db, err := postgres.NewConnection(cfg.Database)
    if err != nil {
        logger.Fatal("Failed to connect to database", zap.Error(err))
    }
    defer db.Close()

    redisClient, err := redis.NewClient(cfg.Redis)
    if err != nil {
        logger.Fatal("Failed to connect to Redis", zap.Error(err))
    }
    defer redisClient.Close()

    // Initialize repositories
    userRepo := postgres.NewUserRepository(db.Pool())
    refreshTokenRepo := postgres.NewRefreshTokenRepository(db.Pool())
    auditLogRepo := postgres.NewAuditLogRepository(db.Pool())
    jwtKeyRepo := postgres.NewJWTKeyRepository(db.Pool())

    // Initialize cache implementations
    tokenCache := redis.NewDenylist(redisClient)
    gracePeriodCache := redis.NewGracePeriod(redisClient)
    otpCache := redis.NewOTPCache(redisClient)
    resetTokenCache := redis.NewResetTokenCache(redisClient)
    failedLoginCounter := redis.NewFailedLoginCounter(redisClient)
    rateLimiter := redis.NewRateLimit(redisClient)

    // Initialize event publisher (Kafka producer)
    eventPublisher := kafka.NewProducer(cfg.Kafka)

    // Initialize password hasher
    passwordHasher := common.NewArgon2Hasher(common.Argon2Config{
        Memory:      cfg.Security.Argon2.Memory,
        Iterations:  cfg.Security.Argon2.Iterations,
        Parallelism: cfg.Security.Argon2.Parallelism,
        SaltLength:  cfg.Security.Argon2.SaltLength,
        KeyLength:   cfg.Security.Argon2.KeyLength,
    })

    // Initialize services
    otpService := service.NewOTPService(otpCache)
    userService := service.NewUserService(
        userRepo,
        otpService,
        passwordHasher,
        eventPublisher,
    )
    
    // ... initialize other services

    // Initialize handlers
    userHandler := handler.NewUserHandler(userService)
    
    // ... initialize other handlers

    // Setup router
    r := router.NewRouter(
        healthHandler,
        userHandler,
        authHandler,
        // ... other handlers
    )
    r.SetupRoutes()

    // ... rest of the code
}
```

---

## 9. Testing

### 9.1 User Service Test

```go
// internal/domain/service/user_service_test.go
package service

import (
    "context"
    "testing"

    "github.com/google/uuid"
    "github.com/stretchr/testify/assert"
    "github.com/stretchr/testify/mock"
    "github.com/stretchr/testify/require"

    "github.com/ecommerce/auth-service/internal/domain/entity"
    "github.com/ecommerce/auth-service/internal/domain/port"
)

// MockUserRepository is a mock implementation of UserRepository
type MockUserRepository struct {
    mock.Mock
}

func (m *MockUserRepository) Create(ctx context.Context, user *entity.User) error {
    args := m.Called(ctx, user)
    return args.Error(0)
}

func (m *MockUserRepository) GetByID(ctx context.Context, id uuid.UUID) (*entity.User, error) {
    args := m.Called(ctx, id)
    if args.Get(0) == nil {
        return nil, args.Error(1)
    }
    return args.Get(0).(*entity.User), args.Error(1)
}

func (m *MockUserRepository) GetByEmail(ctx context.Context, email string) (*entity.User, error) {
    args := m.Called(ctx, email)
    if args.Get(0) == nil {
        return nil, args.Error(1)
    }
    return args.Get(0).(*entity.User), args.Error(1)
}

func (m *MockUserRepository) Update(ctx context.Context, user *entity.User) error {
    args := m.Called(ctx, user)
    return args.Error(0)
}

// MockOTPService is a mock implementation of OTPService
type MockOTPService struct {
    mock.Mock
}

func (m *MockOTPService) GenerateOTP(ctx context.Context, email string) (string, error) {
    args := m.Called(ctx, email)
    return args.String(0), args.Error(1)
}

func (m *MockOTPService) VerifyOTP(ctx context.Context, email, otp string) error {
    args := m.Called(ctx, email, otp)
    return args.Error(0)
}

func (m *MockOTPService) DeleteOTP(ctx context.Context, email string) error {
    args := m.Called(ctx, email)
    return args.Error(0)
}

// MockPasswordHasher is a mock implementation of PasswordHasher
type MockPasswordHasher struct {
    mock.Mock
}

func (m *MockPasswordHasher) Hash(ctx context.Context, password string) (string, error) {
    args := m.Called(ctx, password)
    return args.String(0), args.Error(1)
}

func (m *MockPasswordHasher) Verify(ctx context.Context, password, hash string) (bool, error) {
    args := m.Called(ctx, password, hash)
    return args.Bool(0), args.Error(1)
}

// MockEventPublisher is a mock implementation of EventPublisher
type MockEventPublisher struct {
    mock.Mock
}

func (m *MockEventPublisher) Publish(ctx context.Context, event interface{}) error {
    args := m.Called(ctx, event)
    return args.Error(0)
}

func TestUserService_Register(t *testing.T) {
    ctx := context.Background()

    // Setup mocks
    mockUserRepo := new(MockUserRepository)
    mockOTPService := new(MockOTPService)
    mockPasswordHasher := new(MockPasswordHasher)
    mockEventPublisher := new(MockEventPublisher)

    // Create service
    service := NewUserService(
        mockUserRepo,
        mockOTPService,
        mockPasswordHasher,
        mockEventPublisher,
    )

    // Test data
    req := &RegisterRequest{
        Email:    "test@example.com",
        Password: "Password123",
        IPAddress: "127.0.0.1",
        DeviceInfo: "Test Browser",
    }

    // Setup expectations
    mockUserRepo.On("GetByEmail", ctx, req.Email).Return(nil, port.ErrNotFound)
    mockPasswordHasher.On("Hash", ctx, req.Password).Return("hashed_password", nil)
    mockUserRepo.On("Create", ctx, mock.AnythingOfType("*entity.User")).Return(nil)
    mockOTPService.On("GenerateOTP", ctx, req.Email).Return("123456", nil)
    mockEventPublisher.On("Publish", ctx, mock.AnythingOfType("*event.UserOTPRequested")).Return(nil)

    // Execute
    user, otp, err := service.Register(ctx, req)

    // Assertions
    require.NoError(t, err)
    assert.NotNil(t, user)
    assert.Equal(t, req.Email, user.Email)
    assert.Equal(t, entity.StatusPendingVerification, user.Status)
    assert.Equal(t, "123456", otp)

    // Verify mock expectations
    mockUserRepo.AssertExpectations(t)
    mockPasswordHasher.AssertExpectations(t)
    mockOTPService.AssertExpectations(t)
}

func TestUserService_Register_DuplicateEmail(t *testing.T) {
    ctx := context.Background()

    // Setup mocks
    mockUserRepo := new(MockUserRepository)
    mockOTPService := new(MockOTPService)
    mockPasswordHasher := new(MockPasswordHasher)
    mockEventPublisher := new(MockEventPublisher)

    service := NewUserService(
        mockUserRepo,
        mockOTPService,
        mockPasswordHasher,
        mockEventPublisher,
    )

    req := &RegisterRequest{
        Email:    "existing@example.com",
        Password: "Password123",
    }

    existingUser := &entity.User{
        ID:    uuid.New(),
        Email: req.Email,
    }

    mockUserRepo.On("GetByEmail", ctx, req.Email).Return(existingUser, nil)

    // Execute
    user, otp, err := service.Register(ctx, req)

    // Assertions
    assert.Error(t, err)
    assert.Nil(t, user)
    assert.Empty(t, otp)
    assert.Contains(t, err.Error(), "already exists")
}

func TestUserService_VerifyEmail(t *testing.T) {
    ctx := context.Background()

    mockUserRepo := new(MockUserRepository)
    mockOTPService := new(MockOTPService)
    mockPasswordHasher := new(MockPasswordHasher)
    mockEventPublisher := new(MockEventPublisher)

    service := NewUserService(
        mockUserRepo,
        mockOTPService,
        mockPasswordHasher,
        mockEventPublisher,
    )

    user := &entity.User{
        ID:           uuid.New(),
        Email:        "test@example.com",
        Status:       entity.StatusPendingVerification,
        EmailVerifiedAt: nil,
    }

    mockOTPService.On("VerifyOTP", ctx, user.Email, "123456").Return(nil)
    mockUserRepo.On("GetByEmail", ctx, user.Email).Return(user, nil)
    mockUserRepo.On("Update", ctx, mock.AnythingOfType("*entity.User")).Return(nil)
    mockOTPService.On("DeleteOTP", ctx, user.Email).Return(nil)
    mockEventPublisher.On("Publish", ctx, mock.AnythingOfType("*event.UserVerifiedSuccess")).Return(nil)

    err := service.VerifyEmail(ctx, user.Email, "123456")

    require.NoError(t, err)
    assert.True(t, user.IsEmailVerified())
    assert.Equal(t, entity.StatusActive, user.Status)

    mockUserRepo.AssertExpectations(t)
    mockOTPService.AssertExpectations(t)
}
```

### 9.2 OTP Service Test

```go
// internal/domain/service/otp_service_test.go
package service

import (
    "context"
    "testing"

    "github.com/stretchr/testify/assert"
    "github.com/stretchr/testify/mock"
)

type MockOTPCache struct {
    mock.Mock
}

func (m *MockOTPCache) Set(ctx context.Context, email string, otp string) error {
    args := m.Called(ctx, email, otp)
    return args.Error(0)
}

func (m *MockOTPCache) Get(ctx context.Context, email string) (string, error) {
    args := m.Called(ctx, email)
    return args.String(0), args.Error(1)
}

func (m *MockOTPCache) Delete(ctx context.Context, email string) error {
    args := m.Called(ctx, email)
    return args.Error(0)
}

func (m *MockOTPCache) GetRemainingTTL(ctx context.Context, email string) (time.Duration, error) {
    args := m.Called(ctx, email)
    return args.Get(0).(time.Duration), args.Error(1)
}

func TestOTPService_GenerateAndVerify(t *testing.T) {
    ctx := context.Background()
    mockCache := new(MockOTPCache)

    service := NewOTPService(mockCache)

    email := "test@example.com"
    expectedOTP := "123456"

    mockCache.On("Set", ctx, email, mock.AnythingOfType("string")).Return(nil)

    otp, err := service.GenerateOTP(ctx, email)

    require.NoError(t, err)
    assert.Len(t, otp, 6) // OTP should be 6 digits

    // Now verify
    mockCache.On("Get", ctx, email).Return(expectedOTP, nil)

    err = service.VerifyOTP(ctx, email, expectedOTP)

    require.NoError(t, err)
    mockCache.AssertExpectations(t)
}

func TestOTPService_VerifyInvalidOTP(t *testing.T) {
    ctx := context.Background()
    mockCache := new(MockOTPCache)

    service := NewOTPService(mockCache)

    email := "test@example.com"
    storedOTP := "123456"
    wrongOTP := "654321"

    mockCache.On("Get", ctx, email).Return(storedOTP, nil)

    err := service.VerifyOTP(ctx, email, wrongOTP)

    assert.Error(t, err)
    assert.Contains(t, err.Error(), "Invalid OTP")
}
```

---

## 10. Checklist

### Domain Services

- [x] Create `internal/domain/service/user_service.go`
- [x] Implement `Register` method
- [x] Implement `VerifyEmail` method
- [x] Implement `ResendOTP` method
- [x] Implement `GetUserByID` method
- [x] Implement `GetUserByEmail` method
- [x] Add password validation
- [x] Add email validation
- [x] Write unit tests

### OTP Service

- [x] Create `internal/domain/service/otp_service.go`
- [x] Implement `GenerateOTP` with cryptographically secure random
- [x] Implement `VerifyOTP` with constant-time comparison
- [x] Implement `DeleteOTP`
- [x] Write unit tests

### Password Hasher

- [x] Create `internal/domain/port/password_hasher.go` interface
- [x] Create `internal/infrastructure/common/password_hasher.go`
- [x] Implement Argon2id hashing
- [x] Implement password verification
- [x] Add secure salt generation
- [x] Write unit tests

### Domain Events

- [x] Create `internal/domain/event/auth_events.go`
- [x] Define event types (USER_OTP_REQUESTED, USER_VERIFIED_SUCCESS)
- [x] Implement event builders
- [x] Implement JSON serialization

### API Handlers

- [x] Create `internal/api/handler/user_handler.go`
- [x] Implement `Register` endpoint
- [x] Implement `VerifyEmail` endpoint
- [x] Implement `ResendOTP` endpoint
- [x] Add proper error handling
- [ ] Write handler tests

### DTOs

- [x] Create `internal/api/dto/request/register.go`
- [x] Create `internal/api/dto/request/verify_email.go`
- [x] Create `internal/api/dto/request/resend_otp.go`
- [x] Create `internal/api/dto/response/register.go`
- [x] Add validation methods

### Validation

- [x] Create `internal/common/validator/validator.go` (SINGLE SOURCE OF TRUTH)
- [x] Add `IsValidEmail()` function
- [x] Add `ValidatePassword()` function with full rules
- [x] Add `IsValidPassword()` convenience function
- [x] Create `internal/domain/vo/email.go` using centralized validator
- [x] Update service layer to use centralized validator
- [x] Document validation architecture in plan

### Integration

- [x] Update router with new endpoints
- [x] Wire dependencies in main.go
- [x] Test end-to-end registration flow
- [x] Test end-to-end verification flow

---

**Previous:** [module-02-redis-cache-layer.md](module-02-redis-cache-layer.md)  
**Next:** [module-04-authentication.md](module-04-authentication.md)
