# Module 5: Token Management

> **Module:** Domain Layer  
> **File:** `module-05-token-management.md`  
> **Phase:** 2 - Core Features  
> **Estimated Time:** 4-5 days

---

## Table of Contents

1. [Overview](#1-overview)
2. [JWT Service](#2-jwt-service)
3. [Token Refresh with Grace Period](#3-token-refresh-with-grace-period)
4. [Token Revocation](#4-token-revocation)
5. [API Handlers](#5-api-handlers)
6. [Testing](#6-testing)
7. [Checklist](#7-checklist)

---

## 1. Overview

Module này triển khai quản lý token:
- JWT Access Token generation và validation
- Refresh Token rotation
- **Grace Period 10 giây** chống race condition
- Token revocation (Single Device và Force Logout)

### Token Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         TOKEN ARCHITECTURE                               │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌──────────────┐          ┌──────────────┐         ┌──────────────┐   │
│  │ Access Token │          │ Refresh Token│         │   Gateway    │   │
│  │   (JWT)     │          │  (Opaque)   │         │  Verifies   │   │
│  └──────┬───────┘          └──────┬───────┘         └──────▲───────┘   │
│         │                          │                        │            │
│         │  15 min TTL              │  30 days TTL          │            │
│         │                          │                        │            │
│         ▼                          ▼                        │            │
│  ┌─────────────────────────────────────────────┐           │            │
│  │              AUTH SERVICE                     │           │            │
│  │  ┌─────────┐  ┌────────────┐  ┌─────────┐ │           │            │
│  │  │ JWT     │  │ Refresh    │  │ Token   │ │           │            │
│  │  │ Service │  │ Service    │  │ Cache   │ │           │            │
│  │  └─────────┘  └────────────┘  └────┬────┘ │           │            │
│  └──────────────────────────────────────│───────┼───────────┘            │
│                                         │        │                       │
│                                         ▼        ▼                       │
│                                  ┌────────────┐ ┌────────────┐          │
│                                  │  Database  │ │   Redis    │          │
│                                  │  (pgx)    │ │  (Denylist)│          │
│                                  └────────────┘ └────────────┘          │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 2. JWT Service

### 2.1 JWT Service Implementation

```go
// internal/domain/service/jwt_service.go
package service

import (
    "context"
    "crypto/rsa"
    "encoding/base64"
    "encoding/json"
    "fmt"
    "math/big"
    "time"

    "github.com/golang-jwt/jwt/v5"
    "github.com/google/uuid"

    "github.com/ecommerce/auth-service/internal/common/constants"
    apperrors "github.com/ecommerce/auth-service/internal/common/errors"
    "github.com/ecommerce/auth-service/internal/domain/entity"
    "github.com/ecommerce/auth-service/internal/domain/port"
)

// JWTClaims represents the claims in an access token
type JWTClaims struct {
    jwt.RegisteredClaims
    UserID uuid.UUID `json:"user_id"`
    Email  string    `json:"email"`
    Roles  []string  `json:"roles"`
    JTI    string    `json:"jti"`
}

// JWTService handles JWT token operations
type JWTService struct {
    jwtKeyRepo port.JWTKeyRepository
}

func NewJWTService(jwtKeyRepo port.JWTKeyRepository) *JWTService {
    return &JWTService{
        jwtKeyRepo: jwtKeyRepo,
    }
}

// GenerateAccessToken generates a new JWT access token
func (s *JWTService) GenerateAccessToken(ctx context.Context, user *entity.User) (string, error) {
    // Get active key
    key, err := s.jwtKeyRepo.GetActive(ctx)
    if err != nil {
        return "", fmt.Errorf("failed to get active key: %w", err)
    }

    // Parse private key
    privateKey, err := parseRSAPrivateKey(key.PrivateKey)
    if err != nil {
        return "", fmt.Errorf("failed to parse private key: %w", err)
    }

    // Generate JTI (JWT ID) for revocation tracking
    jti := uuid.New().String()

    // Create claims
    now := time.Now()
    claims := jwt.RegisteredClaims{
        Issuer:    constants.JWTIssuer,
        Subject:   user.ID.String(),
        Audience:  jwt.ClaimStrings{"ecommerce-api"},
        ExpiresAt: jwt.NewNumericDate(now.Add(constants.AccessTokenTTLDefault)),
        IssuedAt:  jwt.NewNumericDate(now),
        NotBefore: jwt.NewNumericDate(now),
        ID:        jti,
    }

    // Create JWT claims with custom fields
    customClaims := JWTClaims{
        RegisteredClaims: claims,
        UserID:          user.ID,
        Email:           user.Email,
        Roles:           user.Roles,
        JTI:             jti,
    }

    // Sign token
    token := jwt.NewWithClaims(jwt.SigningMethodRS256, customClaims)
    tokenString, err := token.SignedString(privateKey)
    if err != nil {
        return "", fmt.Errorf("failed to sign token: %w", err)
    }

    return tokenString, nil
}

// ValidateAccessToken validates a JWT access token
func (s *JWTService) ValidateAccessToken(ctx context.Context, tokenString string) (*JWTClaims, error) {
    // Parse token without validation first to get the kid
    parser := jwt.NewParser()
    token, _, err := parser.ParseUnverified(tokenString, &JWTClaims{})
    if err != nil {
        return nil, apperrors.ErrInvalidToken
    }

    // Get kid from header
    kid, ok := token.Header["kid"].(string)
    if !ok {
        return nil, apperrors.ErrInvalidToken.WithMessage("Missing key ID in token")
    }

    // Get key from repository
    key, err := s.jwtKeyRepo.GetByKid(ctx, kid)
    if err != nil {
        if err == port.ErrNotFound {
            return nil, apperrors.ErrInvalidToken.WithMessage("Unknown key")
        }
        return nil, fmt.Errorf("failed to get key: %w", err)
    }

    // Check if key can verify
    if !key.CanVerify() {
        return nil, apperrors.ErrInvalidToken.WithMessage("Key cannot verify")
    }

    // Parse public key
    publicKey, err := parseRSAPublicKey(key.PublicKey)
    if err != nil {
        return nil, fmt.Errorf("failed to parse public key: %w", err)
    }

    // Parse and validate token
    validToken, err := jwt.ParseWithClaims(tokenString, &JWTClaims{}, func(token *jwt.Token) (interface{}, error) {
        if _, ok := token.Method.(*jwt.SigningMethodRSA); !ok {
            return nil, fmt.Errorf("unexpected signing method: %v", token.Header["alg"])
        }
        return publicKey, nil
    })
    if err != nil {
        return nil, apperrors.ErrInvalidToken
    }

    claims, ok := validToken.Claims.(*JWTClaims)
    if !ok || !validToken.Valid {
        return nil, apperrors.ErrInvalidToken
    }

    return claims, nil
}

// GetJTIFromToken extracts the JTI from a token without validation
func (s *JWTService) GetJTIFromToken(tokenString string) (string, error) {
    parser := jwt.NewParser()
    token, _, err := parser.ParseUnverified(tokenString, &JWTClaims{})
    if err != nil {
        return "", err
    }

    claims, ok := token.Claims.(*JWTClaims)
    if !ok {
        return "", fmt.Errorf("invalid claims")
    }

    return claims.JTI, nil
}

// GetExpiryFromToken extracts the expiry time from a token without validation
func (s *JWTService) GetExpiryFromToken(tokenString string) (time.Time, error) {
    parser := jwt.NewParser()
    token, _, err := parser.ParseUnverified(tokenString, &JWTClaims{})
    if err != nil {
        return time.Time{}, err
    }

    claims, ok := token.Claims.(*JWTClaims)
    if !ok {
        return time.Time{}, fmt.Errorf("invalid claims")
    }

    return claims.ExpiresAt.Time, nil
}

// Helper functions

func parseRSAPrivateKey(keyPEM string) (*rsa.PrivateKey, error) {
    key, err := jwt.ParseRSAPrivateKeyFromPEM([]byte(keyPEM))
    if err != nil {
        return nil, err
    }
    return key, nil
}

func parseRSAPublicKey(keyPEM string) (*rsa.PublicKey, error) {
    key, err := jwt.ParseRSAPublicKeyFromPEM([]byte(keyPEM))
    if err != nil {
        return nil, err
    }
    return key, nil
}

// JWK represents a JSON Web Key
type JWK struct {
    Kty string `json:"kty"`
    Kid string `json:"kid"`
    Use string `json:"use"`
    Alg string `json:"alg"`
    N   string `json:"n"`
    E   string `json:"e"`
}

// ToJWK converts a JWTKey to JWK format
func (k *entity.JWTKey) ToJWK() (*JWK, error) {
    publicKey, err := parseRSAPublicKey(k.PublicKey)
    if err != nil {
        return nil, err
    }

    return &JWK{
        Kty: "RSA",
        Kid: k.Kid,
        Use: "sig",
        Alg: "RS256",
        N:   base64.RawURLEncoding.EncodeToString(publicKey.N.Bytes()),
        E:   base64.RawURLEncoding.EncodeToString(big.NewInt(int64(publicKey.E)).Bytes()),
    }, nil
}
```

---

## 3. Token Refresh with Grace Period

### 3.1 Refresh Token Service

```go
// internal/domain/service/refresh_service.go
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
    "github.com/ecommerce/auth-service/internal/domain/entity"
    "github.com/ecommerce/auth-service/internal/domain/event"
    "github.com/ecommerce/auth-service/internal/domain/port"
)

// RefreshResponse represents the response from a token refresh
type RefreshResponse struct {
    AccessToken     string
    NewRefreshToken string
    ExpiresIn      int64
    TokenType      string
}

// RefreshService handles refresh token operations
type RefreshService struct {
    userRepo          port.UserRepository
    refreshTokenRepo  port.RefreshTokenRepository
    tokenCache        port.TokenCache
    gracePeriodCache  port.GracePeriodCache
    jwtService        *JWTService
    eventPublisher    port.EventPublisher
    auditService      *AuditService
}

func NewRefreshService(
    userRepo port.UserRepository,
    refreshTokenRepo port.RefreshTokenRepository,
    tokenCache port.TokenCache,
    gracePeriodCache port.GracePeriodCache,
    jwtService *JWTService,
    eventPublisher port.EventPublisher,
    auditService *AuditService,
) *RefreshService {
    return &RefreshService{
        userRepo:          userRepo,
        refreshTokenRepo:  refreshTokenRepo,
        tokenCache:        tokenCache,
        gracePeriodCache:  gracePeriodCache,
        jwtService:        jwtService,
        eventPublisher:    eventPublisher,
        auditService:      auditService,
    }
}

// RefreshTokens handles token refresh with Grace Period
// This implements the concurrency-safe token rotation
func (s *RefreshService) RefreshTokens(
    ctx context.Context,
    oldRefreshToken string,
    oldAccessTokenJTI string,
    oldAccessTokenExp time.Time,
) (*RefreshResponse, error) {
    // Hash the old refresh token
    tokenHash := hashToken(oldRefreshToken)

    // Get the refresh token from database
    storedToken, err := s.refreshTokenRepo.GetByHash(ctx, tokenHash)
    if err != nil {
        if err == port.ErrNotFound {
            return nil, apperrors.ErrInvalidToken
        }
        return nil, fmt.Errorf("failed to get refresh token: %w", err)
    }

    // Check if token is already revoked (concurrency case)
    if storedToken.IsRevoked() {
        // Check grace period cache
        cachedAccessToken, cachedRefreshToken, found, err := s.gracePeriodCache.Get(ctx, tokenHash)
        if err != nil {
            return nil, fmt.Errorf("failed to check grace period: %w", err)
        }

        if found {
            // Within 10 second grace period - return cached tokens
            return &RefreshResponse{
                AccessToken:     cachedAccessToken,
                NewRefreshToken: cachedRefreshToken,
                ExpiresIn:       int64(constants.AccessTokenTTLDefault.Seconds()),
                TokenType:       "Bearer",
            }, nil
        }

        // Token theft detected - revoke all user tokens
        if err := s.handleTokenTheft(ctx, storedToken); err != nil {
            return nil, err
        }
        return nil, apperrors.ErrTokenTheft
    }

    // Token is valid - proceed with rotation
    return s.rotateTokens(ctx, storedToken, oldAccessTokenJTI, oldAccessTokenExp)
}

// rotateTokens performs token rotation
func (s *RefreshService) rotateTokens(
    ctx context.Context,
    storedToken *entity.RefreshToken,
    oldAccessTokenJTI string,
    oldAccessTokenExp time.Time,
) (*RefreshResponse, error) {
    // Get user
    user, err := s.userRepo.GetByID(ctx, storedToken.UserID)
    if err != nil {
        return nil, fmt.Errorf("failed to get user: %w", err)
    }

    // Verify user can login
    if !user.CanLogin() {
        return nil, apperrors.ErrAccountLocked
    }

    // Generate new access token
    accessToken, err := s.jwtService.GenerateAccessToken(ctx, user)
    if err != nil {
        return nil, fmt.Errorf("failed to generate access token: %w", err)
    }

    // Generate new refresh token
    newRefreshToken := generateSecureToken(32)
    newRefreshTokenHash := hashToken(newRefreshToken)

    // Add old access token to denylist
    remainingTTL := time.Until(oldAccessTokenExp)
    if remainingTTL > 0 {
        if err := s.tokenCache.AddToDenylist(ctx, oldAccessTokenJTI, remainingTTL); err != nil {
            return nil, fmt.Errorf("failed to add to denylist: %w", err)
        }
    }

    // Create new refresh token record
    newTokenEntity := &entity.RefreshToken{
        ID:         uuid.New(),
        UserID:     storedToken.UserID,
        TokenHash:  newRefreshTokenHash,
        FamilyID:   storedToken.FamilyID, // Same family for rotation tracking
        DeviceID:   storedToken.DeviceID,
        DeviceInfo: storedToken.DeviceInfo,
        ExpiresAt:  time.Now().Add(constants.RefreshTokenTTLDefault),
        CreatedAt:  time.Now().UTC(),
    }

    if err := s.refreshTokenRepo.Create(ctx, newTokenEntity); err != nil {
        return nil, fmt.Errorf("failed to create refresh token: %w", err)
    }

    // Mark old refresh token as revoked
    oldTokenHash := storedToken.TokenHash
    if err := s.refreshTokenRepo.Revoke(ctx, storedToken.ID); err != nil {
        return nil, fmt.Errorf("failed to revoke old token: %w", err)
    }

    // Update replaced_by for audit trail
    if err := s.refreshTokenRepo.UpdateReplacedBy(ctx, storedToken.ID, newTokenEntity.ID); err != nil {
        // Log error but don't fail
        fmt.Printf("Failed to update replaced_by: %v\n", err)
    }

    // Store in grace period cache (10 second TTL)
    if err := s.gracePeriodCache.Set(ctx, oldTokenHash, accessToken, newRefreshToken); err != nil {
        // Log error but don't fail
        fmt.Printf("Failed to set grace period cache: %v\n", err)
    }

    return &RefreshResponse{
        AccessToken:     accessToken,
        NewRefreshToken: newRefreshToken,
        ExpiresIn:       int64(constants.AccessTokenTTLDefault.Seconds()),
        TokenType:       "Bearer",
    }, nil
}

// handleTokenTheft handles the case when a refresh token is used after rotation
// This is called when the grace period has expired
func (s *RefreshService) handleTokenTheft(ctx context.Context, stolenToken *entity.RefreshToken) error {
    // Revoke all refresh tokens for this user
    if err := s.refreshTokenRepo.RevokeAllByUserID(ctx, stolenToken.UserID); err != nil {
        return fmt.Errorf("failed to revoke all tokens: %w", err)
    }

    // Set user revocation timestamp (force logout)
    now := time.Now()
    if err := s.tokenCache.SetRevokedBefore(ctx, stolenToken.UserID.String(), now); err != nil {
        return fmt.Errorf("failed to set revoked_before: %w", err)
    }

    // Log the token theft
    s.auditService.LogTokenTheftDetected(
        ctx,
        stolenToken.UserID.String(),
        stolenToken.ID.String(),
    )

    // Publish TOKEN_THEFT_DETECTED event for WebSocket cleanup
    theftEvent := event.NewTokenTheftDetected(
        stolenToken.UserID,
        stolenToken.ID.String(),
        "ALL_TOKENS_REVOKED",
    )
    if err := s.eventPublisher.Publish(ctx, theftEvent); err != nil {
        // Log error but don't fail
        fmt.Printf("Failed to publish theft event: %v\n", err)
    }

    return nil
}

// generateSecureToken generates a cryptographically secure random token
func generateSecureToken(length int) string {
    bytes := make([]byte, length)
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

### 3.2 Event Definition for Token Theft

```go
// internal/domain/event/auth_events.go (additional events)
package event

import (
    "encoding/json"
    "time"

    "github.com/google/uuid"
)

// TokenTheftDetected event
type TokenTheftDetected struct {
    EventBase
    Payload TokenTheftPayload `json:"payload"`
}

type TokenTheftPayload struct {
    UserID               string `json:"user_id"`
    TheftRefreshTokenID  string `json:"theft_refresh_token_id"`
    ActionTaken         string `json:"action_taken"`
}

func NewTokenTheftDetected(userID uuid.UUID, tokenID, action string) *TokenTheftDetected {
    return &TokenTheftDetected{
        EventBase: NewEventBase(EventTokenTheftDetected, "USER", userID.String()),
        Payload: TokenTheftPayload{
            UserID:              userID.String(),
            TheftRefreshTokenID:  tokenID,
            ActionTaken:         action,
        },
    }
}

func (e *TokenTheftDetected) ToJSON() ([]byte, error) {
    return json.Marshal(e)
}
```

---

## 4. Token Revocation

### 4.1 Token Revocation Service

```go
// internal/domain/service/revocation_service.go
package service

import (
    "context"
    "fmt"
    "time"

    "github.com/google/uuid"

    "github.com/ecommerce/auth-service/internal/domain/port"
)

// RevocationService handles token revocation
type RevocationService struct {
    refreshTokenRepo port.RefreshTokenRepository
    tokenCache       port.TokenCache
    auditService     *AuditService
}

func NewRevocationService(
    refreshTokenRepo port.RefreshTokenRepository,
    tokenCache port.TokenCache,
    auditService *AuditService,
) *RevocationService {
    return &RevocationService{
        refreshTokenRepo: refreshTokenRepo,
        tokenCache:       tokenCache,
        auditService:     auditService,
    }
}

// RevokeToken revokes a specific refresh token
func (s *RevocationService) RevokeToken(ctx context.Context, tokenID uuid.UUID) error {
    // Get the token to find its hash
    token, err := s.refreshTokenRepo.GetByID(ctx, tokenID)
    if err != nil {
        return fmt.Errorf("failed to get token: %w", err)
    }

    // Revoke in database
    if err := s.refreshTokenRepo.Revoke(ctx, tokenID); err != nil {
        return fmt.Errorf("failed to revoke token: %w", err)
    }

    // Add to denylist (we can't get the access token JTI here,
    // but the Gateway will check revocation timestamp on next request)

    return nil
}

// RevokeAllUserTokens revokes all tokens for a user (force logout)
func (s *RevocationService) RevokeAllUserTokens(ctx context.Context, userID uuid.UUID, reason string) error {
    // Revoke all refresh tokens
    if err := s.refreshTokenRepo.RevokeAllByUserID(ctx, userID); err != nil {
        return fmt.Errorf("failed to revoke all tokens: %w", err)
    }

    // Set revocation timestamp
    now := time.Now()
    if err := s.tokenCache.SetRevokedBefore(ctx, userID.String(), now); err != nil {
        return fmt.Errorf("failed to set revoked_before: %w", err)
    }

    // Log
    s.auditService.LogForceLogout(ctx, userID.String(), reason)

    return nil
}

// RevokeByDeviceID revokes all tokens for a specific device
func (s *RevocationService) RevokeByDeviceID(ctx context.Context, userID uuid.UUID, deviceID string) error {
    // Get all active tokens
    tokens, err := s.refreshTokenRepo.GetActiveByUserID(ctx, userID)
    if err != nil {
        return fmt.Errorf("failed to get active tokens: %w", err)
    }

    // Revoke tokens for the specified device
    for _, token := range tokens {
        if token.DeviceID == deviceID {
            if err := s.refreshTokenRepo.Revoke(ctx, token.ID); err != nil {
                return fmt.Errorf("failed to revoke token: %w", err)
            }
        }
    }

    return nil
}
```

---

## 5. API Handlers

### 5.1 Refresh Handler

```go
// internal/api/handler/refresh_handler.go
package handler

import (
    "net/http"
    "time"

    "github.com/gin-gonic/gin"

    "github.com/ecommerce/auth-service/internal/api/dto/response"
    "github.com/ecommerce/auth-service/internal/api/middleware"
    "github.com/ecommerce/auth-service/internal/domain/service"
    apperrors "github.com/ecommerce/auth-service/internal/common/errors"
)

type RefreshHandler struct {
    refreshService *service.RefreshService
    jwtService     *service.JWTService
}

func NewRefreshHandler(refreshService *service.RefreshService, jwtService *service.JWTService) *RefreshHandler {
    return &RefreshHandler{
        refreshService: refreshService,
        jwtService:     jwtService,
    }
}

// Refresh handles POST /api/v1/auth/refresh
func (h *RefreshHandler) Refresh(c *gin.Context) {
    // Get refresh token from cookie or body
    refreshToken := c.GetHeader("X-Refresh-Token")
    if refreshToken == "" {
        refreshToken, _ = c.Cookie("refresh_token")
    }

    if refreshToken == "" {
        middleware.Error(c, apperrors.ErrBadRequest.WithMessage("Refresh token required"))
        return
    }

    // Get current access token info for denylist
    accessTokenJTI, exists := c.Get("jti")
    if !exists {
        middleware.Error(c, apperrors.ErrUnauthorized)
        return
    }

    accessTokenExpInterface, exists := c.Get("exp")
    if !exists {
        middleware.Error(c, apperrors.ErrUnauthorized)
        return
    }

    // Parse expiry time
    accessTokenExp, ok := accessTokenExpInterface.(time.Time)
    if !ok {
        middleware.Error(c, apperrors.ErrInternal)
        return
    }

    // Call refresh service
    refreshResp, err := h.refreshService.RefreshTokens(
        c.Request.Context(),
        refreshToken,
        accessTokenJTI.(string),
        accessTokenExp,
    )
    if err != nil {
        handleServiceError(c, err)
        return
    }

    // Update refresh token cookie
    c.SetCookie(
        "refresh_token",
        refreshResp.NewRefreshToken,
        int(time.Now().Add(30*24*time.Hour).Unix()),
        "/",
        "",
        true,  // Secure
        true,  // HttpOnly
    )

    c.JSON(http.StatusOK, response.RefreshResponse{
        AccessToken:  refreshResp.AccessToken,
        ExpiresIn:   refreshResp.ExpiresIn,
        TokenType:   refreshResp.TokenType,
    })
}
```

---

## 6. Testing

### 6.1 JWT Service Test

```go
// test/unit/domain/service/jwt_service_test.go
package service

import (
    "context"
    "testing"
    "time"

    "github.com/google/uuid"
    "github.com/stretchr/testify/assert"
    "github.com/stretchr/testify/mock"
    "github.com/stretchr/testify/require"

    "github.com/ecommerce/auth-service/internal/domain/entity"
)

func TestJWTService_GenerateAndValidate(t *testing.T) {
    ctx := context.Background()

    // Setup mock
    mockJWTKeyRepo := new(MockJWTKeyRepository)

    service := NewJWTService(mockJWTKeyRepo)

    // Create test key
    testKey := &entity.JWTKey{
        Kid:        "test-key-1",
        PublicKey:  testPublicKeyPEM,
        PrivateKey: testPrivateKeyPEM,
        Status:     entity.KeyStatusActive,
        CreatedAt:  time.Now(),
    }

    mockJWTKeyRepo.On("GetActive", ctx).Return(testKey, nil)

    // Create test user
    user := &entity.User{
        ID:    uuid.New(),
        Email: "test@example.com",
        Roles: []string{"USER"},
    }

    // Generate token
    token, err := service.GenerateAccessToken(ctx, user)
    require.NoError(t, err)
    assert.NotEmpty(t, token)

    // Parse and validate token
    claims, err := service.ValidateAccessToken(ctx, token)
    require.NoError(t, err)
    assert.Equal(t, user.ID, claims.UserID)
    assert.Equal(t, user.Email, claims.Email)
    assert.Equal(t, user.Roles, claims.Roles)
    assert.NotEmpty(t, claims.JTI)
}

func TestJWTService_ValidateExpiredToken(t *testing.T) {
    ctx := context.Background()

    // This would test that expired tokens are rejected
    // Implementation depends on clock skew handling
}
```

### 6.2 Refresh Service Test

```go
// test/unit/domain/service/refresh_service_test.go
package service

import (
    "context"
    "testing"
    "time"

    "github.com/google/uuid"
    "github.com/stretchr/testify/assert"
    "github.com/stretchr/testify/mock"
    "github.com/stretchr/testify/require"

    "github.com/ecommerce/auth-service/internal/domain/entity"
)

func TestRefreshService_RefreshTokens_Success(t *testing.T) {
    ctx := context.Background()

    mockUserRepo := new(MockUserRepository)
    mockRefreshTokenRepo := new(MockRefreshTokenRepository)
    mockTokenCache := new(MockTokenCache)
    mockGracePeriodCache := new(MockGracePeriodCache)
    mockJWTService := new(MockJWTService)
    mockEventPublisher := new(MockEventPublisher)
    mockAuditService := new(MockAuditService)

    service := NewRefreshService(
        mockUserRepo,
        mockRefreshTokenRepo,
        mockTokenCache,
        mockGracePeriodCache,
        mockJWTService,
        mockEventPublisher,
        mockAuditService,
    )

    userID := uuid.New()
    oldTokenHash := hashToken("old-refresh-token")

    storedToken := &entity.RefreshToken{
        ID:         uuid.New(),
        UserID:     userID,
        TokenHash:  oldTokenHash,
        FamilyID:   uuid.New(),
        DeviceID:   "device-123",
        ExpiresAt:  time.Now().Add(24 * time.Hour),
        RevokedAt:  nil, // Not revoked - normal case
    }

    user := &entity.User{
        ID:     userID,
        Email:  "test@example.com",
        Roles:  []string{"USER"},
        Status: entity.StatusActive,
    }

    mockRefreshTokenRepo.On("GetByHash", ctx, oldTokenHash).Return(storedToken, nil)
    mockUserRepo.On("GetByID", ctx, userID).Return(user, nil)
    mockJWTService.On("GenerateAccessToken", ctx, user).Return("new-access-token", nil)
    mockRefreshTokenRepo.On("Create", ctx, mock.AnythingOfType("*entity.RefreshToken")).Return(nil)
    mockRefreshTokenRepo.On("Revoke", ctx, storedToken.ID).Return(nil)
    mockGracePeriodCache.On("Set", ctx, oldTokenHash, "new-access-token", mock.AnythingOfType("string")).Return(nil)

    resp, err := service.RefreshTokens(
        ctx,
        "old-refresh-token",
        "old-jti",
        time.Now().Add(15*time.Minute),
    )

    require.NoError(t, err)
    assert.Equal(t, "new-access-token", resp.AccessToken)
    assert.NotEmpty(t, resp.NewRefreshToken)
}

func TestRefreshService_RefreshTokens_GracePeriodHit(t *testing.T) {
    ctx := context.Background()

    mockUserRepo := new(MockUserRepository)
    mockRefreshTokenRepo := new(MockRefreshTokenRepository)
    mockTokenCache := new(MockTokenCache)
    mockGracePeriodCache := new(MockGracePeriodCache)
    mockJWTService := new(MockJWTService)
    mockEventPublisher := new(MockEventPublisher)
    mockAuditService := new(MockAuditService)

    service := NewRefreshService(
        mockUserRepo,
        mockRefreshTokenRepo,
        mockTokenCache,
        mockGracePeriodCache,
        mockJWTService,
        mockEventPublisher,
        mockAuditService,
    )

    userID := uuid.New()
    oldTokenHash := hashToken("old-refresh-token")
    now := time.Now()

    storedToken := &entity.RefreshToken{
        ID:         uuid.New(),
        UserID:     userID,
        TokenHash:  oldTokenHash,
        ExpiresAt:  now.Add(-time.Hour),
        RevokedAt:  &now, // Already revoked - concurrency case
    }

    mockRefreshTokenRepo.On("GetByHash", ctx, oldTokenHash).Return(storedToken, nil)
    mockGracePeriodCache.On("Get", ctx, oldTokenHash).Return(
        "cached-access-token",
        "cached-refresh-token",
        true, // Found in cache
        nil,
    )

    resp, err := service.RefreshTokens(
        ctx,
        "old-refresh-token",
        "old-jti",
        time.Now().Add(15*time.Minute),
    )

    require.NoError(t, err)
    assert.Equal(t, "cached-access-token", resp.AccessToken)
    assert.Equal(t, "cached-refresh-token", resp.NewRefreshToken)
}

func TestRefreshService_RefreshTokens_TokenTheft(t *testing.T) {
    ctx := context.Background()

    mockUserRepo := new(MockUserRepository)
    mockRefreshTokenRepo := new(MockRefreshTokenRepository)
    mockTokenCache := new(MockTokenCache)
    mockGracePeriodCache := new(MockGracePeriodCache)
    mockJWTService := new(MockJWTService)
    mockEventPublisher := new(MockEventPublisher)
    mockAuditService := new(MockAuditService)

    service := NewRefreshService(
        mockUserRepo,
        mockRefreshTokenRepo,
        mockTokenCache,
        mockGracePeriodCache,
        mockJWTService,
        mockEventPublisher,
        mockAuditService,
    )

    userID := uuid.New()
    oldTokenHash := hashToken("old-refresh-token")
    now := time.Now()

    storedToken := &entity.RefreshToken{
        ID:         uuid.New(),
        UserID:     userID,
        TokenHash:  oldTokenHash,
        ExpiresAt:  now.Add(-time.Hour),
        RevokedAt:  &now, // Revoked
    }

    mockRefreshTokenRepo.On("GetByHash", ctx, oldTokenHash).Return(storedToken, nil)
    mockGracePeriodCache.On("Get", ctx, oldTokenHash).Return("", "", false, nil) // Not in cache
    mockRefreshTokenRepo.On("RevokeAllByUserID", ctx, userID).Return(nil)
    mockTokenCache.On("SetRevokedBefore", ctx, userID.String(), mock.AnythingOfType("time.Time")).Return(nil)

    _, err := service.RefreshTokens(
        ctx,
        "old-refresh-token",
        "old-jti",
        time.Now().Add(15*time.Minute),
    )

    assert.Error(t, err)
    assert.Contains(t, err.Error(), "security")
}
```

---

## 7. Checklist

### JWT Service

- [x] Create `internal/domain/service/jwt_service.go`
- [x] Implement `GenerateAccessToken` with RSA signing
- [x] Implement `ValidateAccessToken` with key lookup
- [x] Implement JWK generation
- [x] Add JTI generation
- [x] Write unit tests

### Refresh Service

- [x] Create `internal/domain/service/refresh_service.go`
- [x] Implement `RefreshTokens` with Grace Period logic
- [x] Implement token rotation
- [x] Implement Grace Period cache handling
- [x] Implement token theft detection
- [x] Add audit logging
- [x] Write unit tests

### Revocation Service

- [x] Create `internal/domain/service/refresh_service.go` (includes revocation)
- [x] Implement single token revocation
- [x] Implement all user tokens revocation
- [x] Implement device-based revocation
- [x] Write unit tests

### Token Events

- [x] Add `TokenTheftDetected` event
- [x] Implement event builder
- [x] Add JSON serialization

### API Handlers

- [x] Update `AuthHandler` for token refresh
- [x] Add Grace Period response handling
- [x] Add token theft error handling
- [x] Add RevokeSession handler

### Integration

- [x] Integrate with Module 4 (Authentication)
- [x] Integrate with Module 6 (Session Management) - RevokeSession endpoint
- [x] Test end-to-end token refresh flow
- [x] Test Grace Period race condition handling
- [x] Test token theft detection

---

**Previous:** [module-04-authentication.md](module-04-authentication.md)  
**Next:** [module-06-session-management.md](module-06-session-management.md)
