# Module 6: Session Management

> **Module:** Domain + API Layer  
> **File:** `module-06-session-management.md`  
> **Phase:** 2 - Core Features  
> **Estimated Time:** 2-3 days

---

## Table of Contents

1. [Overview](#1-overview)
2. [Session Service](#2-session-service)
3. [API Handlers](#3-api-handlers)
4. [Testing](#4-testing)
5. [Checklist](#5-checklist)

---

## 1. Overview

Module này triển khai quản lý phiên (Session Management):
- Xem danh sách phiên đăng nhập
- Thu hồi phiên cụ thể
- Xem lịch sử đăng nhập

### Session Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    SESSION MANAGEMENT                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌─────────┐     ┌───────────────┐     ┌─────────────────┐     │
│  │ Client  │────▶│ SessionHandler│────▶│ SessionService  │     │
│  └─────────┘     └───────────────┘     └────────┬────────┘     │
│                                                   │              │
│                                                   ▼              │
│                                          ┌─────────────────┐     │
│                                          │ RefreshTokenRepo │     │
│                                          └────────┬────────┘     │
│                                                   │              │
│                                                   ▼              │
│                                          ┌─────────────────┐     │
│                                          │    Database     │     │
│                                          └─────────────────┘     │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 2. Session Service

### 2.1 Session Service Implementation

```go
// internal/domain/service/session_service.go
package service

import (
    "context"
    "fmt"
    "time"

    "github.com/google/uuid"

    "github.com/ecommerce/auth-service/internal/common/constants"
    apperrors "github.com/ecommerce/auth-service/internal/common/errors"
    "github.com/ecommerce/auth-service/internal/domain/entity"
    "github.com/ecommerce/auth-service/internal/domain/port"
)

// Session represents a user session
type Session struct {
    ID         uuid.UUID  `json:"id"`
    DeviceID   string     `json:"device_id"`
    DeviceInfo string     `json:"device_info"`
    CreatedAt  time.Time  `json:"created_at"`
    ExpiresAt  time.Time  `json:"expires_at"`
    IsCurrent  bool       `json:"is_current"`
}

// SessionService handles session operations
type SessionService struct {
    refreshTokenRepo port.RefreshTokenRepository
    tokenCache      port.TokenCache
    auditService    *AuditService
}

func NewSessionService(
    refreshTokenRepo port.RefreshTokenRepository,
    tokenCache port.TokenCache,
    auditService *AuditService,
) *SessionService {
    return &SessionService{
        refreshTokenRepo: refreshTokenRepo,
        tokenCache:      tokenCache,
        auditService:    auditService,
    }
}

// ListSessions returns all active sessions for a user
func (s *SessionService) ListSessions(ctx context.Context, userID uuid.UUID, currentTokenHash string) ([]*Session, error) {
    // Get all active refresh tokens for the user
    tokens, err := s.refreshTokenRepo.GetActiveByUserID(ctx, userID)
    if err != nil {
        return nil, fmt.Errorf("failed to get active tokens: %w", err)
    }

    sessions := make([]*Session, 0, len(tokens))
    for _, token := range tokens {
        session := &Session{
            ID:         token.ID,
            DeviceID:   token.DeviceID,
            DeviceInfo: token.DeviceInfo,
            CreatedAt:  token.CreatedAt,
            ExpiresAt:  token.ExpiresAt,
            IsCurrent:  token.TokenHash == currentTokenHash,
        }
        sessions = append(sessions, session)
    }

    return sessions, nil
}

// RevokeSession revokes a specific session
func (s *SessionService) RevokeSession(
    ctx context.Context,
    userID uuid.UUID,
    sessionID uuid.UUID,
    currentTokenHash string,
) error {
    // Get the session token
    token, err := s.refreshTokenRepo.GetByID(ctx, sessionID)
    if err != nil {
        if err == port.ErrNotFound {
            return apperrors.ErrSessionNotFound
        }
        return fmt.Errorf("failed to get session: %w", err)
    }

    // Verify the session belongs to the user
    if token.UserID != userID {
        return apperrors.ErrForbidden
    }

    // Revoke the refresh token
    if err := s.refreshTokenRepo.Revoke(ctx, sessionID); err != nil {
        return fmt.Errorf("failed to revoke session: %w", err)
    }

    // Log the revocation
    s.auditService.LogSessionRevoked(ctx, userID.String(), sessionID.String(), token.DeviceInfo)

    return nil
}

// RevokeAllSessions revokes all sessions except the current one
func (s *SessionService) RevokeAllSessions(
    ctx context.Context,
    userID uuid.UUID,
    currentTokenHash string,
) error {
    // Get all active tokens
    tokens, err := s.refreshTokenRepo.GetActiveByUserID(ctx, userID)
    if err != nil {
        return fmt.Errorf("failed to get active tokens: %w", err)
    }

    // Revoke all except current
    revokedCount := 0
    for _, token := range tokens {
        if token.TokenHash != currentTokenHash {
            if err := s.refreshTokenRepo.Revoke(ctx, token.ID); err != nil {
                return fmt.Errorf("failed to revoke session: %w", err)
            }
            revokedCount++
        }
    }

    // Log the mass revocation
    s.auditService.LogAllSessionsRevoked(ctx, userID.String(), revokedCount)

    return nil
}

// GetSessionByID retrieves a specific session
func (s *SessionService) GetSessionByID(ctx context.Context, userID uuid.UUID, sessionID uuid.UUID) (*Session, error) {
    token, err := s.refreshTokenRepo.GetByID(ctx, sessionID)
    if err != nil {
        if err == port.ErrNotFound {
            return nil, apperrors.ErrSessionNotFound
        }
        return nil, fmt.Errorf("failed to get session: %w", err)
    }

    // Verify ownership
    if token.UserID != userID {
        return nil, apperrors.ErrForbidden
    }

    return &Session{
        ID:         token.ID,
        DeviceID:   token.DeviceID,
        DeviceInfo: token.DeviceInfo,
        CreatedAt:  token.CreatedAt,
        ExpiresAt:  token.ExpiresAt,
    }, nil
}
```

---

## 3. API Handlers

### 3.1 Session Handler

```go
// internal/api/handler/session_handler.go
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

type SessionHandler struct {
    sessionService *service.SessionService
}

func NewSessionHandler(sessionService *service.SessionService) *SessionHandler {
    return &SessionHandler{
        sessionService: sessionService,
    }
}

// ListSessions handles GET /api/v1/auth/sessions
func (h *SessionHandler) ListSessions(c *gin.Context) {
    userID, err := getUserIDFromContext(c)
    if err != nil {
        middleware.Error(c, apperrors.ErrUnauthorized)
        return
    }

    // Get current refresh token hash (from cookie)
    currentToken := getRefreshTokenFromCookie(c)
    currentTokenHash := ""
    if currentToken != "" {
        currentTokenHash = hashToken(currentToken)
    }

    sessions, err := h.sessionService.ListSessions(c.Request.Context(), userID, currentTokenHash)
    if err != nil {
        handleServiceError(c, err)
        return
    }

    // Format response
    sessionResponses := make([]response.SessionResponse, 0, len(sessions))
    for _, s := range sessions {
        sessionResponses = append(sessionResponses, response.SessionResponse{
            ID:         s.ID.String(),
            DeviceID:   s.DeviceID,
            DeviceInfo: formatDeviceInfo(s.DeviceInfo),
            CreatedAt:  s.CreatedAt,
            ExpiresAt:  s.ExpiresAt,
            IsCurrent:  s.IsCurrent,
        })
    }

    c.JSON(http.StatusOK, response.SessionsResponse{
        Sessions: sessionResponses,
        Total:   len(sessionResponses),
    })
}

// RevokeSession handles DELETE /api/v1/auth/sessions/:session_id
func (h *SessionHandler) RevokeSession(c *gin.Context) {
    userID, err := getUserIDFromContext(c)
    if err != nil {
        middleware.Error(c, apperrors.ErrUnauthorized)
        return
    }

    sessionIDStr := c.Param("session_id")
    sessionID, err := uuid.Parse(sessionIDStr)
    if err != nil {
        middleware.Error(c, apperrors.ErrBadRequest.WithMessage("Invalid session ID"))
        return
    }

    currentToken := getRefreshTokenFromCookie(c)
    currentTokenHash := ""
    if currentToken != "" {
        currentTokenHash = hashToken(currentToken)
    }

    // Don't allow revoking current session via this endpoint
    // Use logout for that
    session, err := h.sessionService.GetSessionByID(c.Request.Context(), userID, sessionID)
    if err != nil {
        handleServiceError(c, err)
        return
    }

    if session.IsCurrent {
        middleware.Error(c, apperrors.ErrBadRequest.WithMessage("Use logout to revoke current session"))
        return
    }

    err = h.sessionService.RevokeSession(c.Request.Context(), userID, sessionID, currentTokenHash)
    if err != nil {
        handleServiceError(c, err)
        return
    }

    c.JSON(http.StatusOK, response.MessageResponse{
        Message: "Session revoked successfully",
    })
}

// RevokeAllSessions handles DELETE /api/v1/auth/sessions
func (h *SessionHandler) RevokeAllSessions(c *gin.Context) {
    userID, err := getUserIDFromContext(c)
    if err != nil {
        middleware.Error(c, apperrors.ErrUnauthorized)
        return
    }

    currentToken := getRefreshTokenFromCookie(c)
    currentTokenHash := ""
    if currentToken != "" {
        currentTokenHash = hashToken(currentToken)
    }

    err = h.sessionService.RevokeAllSessions(c.Request.Context(), userID, currentTokenHash)
    if err != nil {
        handleServiceError(c, err)
        return
    }

    c.JSON(http.StatusOK, response.MessageResponse{
        Message: "All other sessions revoked successfully",
    })
}

// Helper functions

func getUserIDFromContext(c *gin.Context) (uuid.UUID, error) {
    userIDStr, exists := c.Get("user_id")
    if !exists {
        return uuid.UUID{}, apperrors.ErrUnauthorized
    }
    return uuid.Parse(userIDStr.(string))
}

func getRefreshTokenFromCookie(c *gin.Context) string {
    token, _ := c.Cookie("refresh_token")
    return token
}

func formatDeviceInfo(info string) string {
    if info == "" {
        return "Unknown Device"
    }
    return info
}

func hashToken(token string) string {
    hash := sha256.Sum256([]byte(token))
    return hex.EncodeToString(hash[:])
}

import (
    "crypto/sha256"
    "encoding/hex"
)
```

### 3.2 Response DTOs

```go
// internal/api/dto/response/session.go
package response

import "time"

type SessionResponse struct {
    ID         string    `json:"id"`
    DeviceID   string    `json:"device_id"`
    DeviceInfo string    `json:"device_info"`
    CreatedAt  time.Time `json:"created_at"`
    ExpiresAt  time.Time `json:"expires_at"`
    IsCurrent  bool      `json:"is_current"`
}

type SessionsResponse struct {
    Sessions []SessionResponse `json:"sessions"`
    Total    int              `json:"total"`
}
```

---

## 4. Testing

### 4.1 Session Service Test

```go
// test/unit/domain/service/session_service_test.go
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
    "github.com/ecommerce/auth-service/internal/domain/port"
)

func TestSessionService_ListSessions(t *testing.T) {
    ctx := context.Background()

    mockRefreshTokenRepo := new(MockRefreshTokenRepository)
    mockTokenCache := new(MockTokenCache)
    mockAuditService := new(MockAuditService)

    service := NewSessionService(
        mockRefreshTokenRepo,
        mockTokenCache,
        mockAuditService,
    )

    userID := uuid.New()
    currentHash := "current-hash"

    tokens := []*entity.RefreshToken{
        {
            ID:         uuid.New(),
            UserID:     userID,
            TokenHash:  currentHash,
            DeviceID:   "device-1",
            DeviceInfo: "Chrome on Windows",
            CreatedAt:  time.Now().Add(-1 * time.Hour),
            ExpiresAt:  time.Now().Add(24 * time.Hour),
        },
        {
            ID:         uuid.New(),
            UserID:     userID,
            TokenHash:  "other-hash",
            DeviceID:   "device-2",
            DeviceInfo: "Safari on iOS",
            CreatedAt:  time.Now().Add(-2 * time.Hour),
            ExpiresAt:  time.Now().Add(24 * time.Hour),
        },
    }

    mockRefreshTokenRepo.On("GetActiveByUserID", ctx, userID).Return(tokens, nil)

    sessions, err := service.ListSessions(ctx, userID, currentHash)

    require.NoError(t, err)
    assert.Len(t, sessions, 2)
    assert.True(t, sessions[0].IsCurrent)
    assert.False(t, sessions[1].IsCurrent)
}

func TestSessionService_RevokeSession(t *testing.T) {
    ctx := context.Background()

    mockRefreshTokenRepo := new(MockRefreshTokenRepository)
    mockTokenCache := new(MockTokenCache)
    mockAuditService := new(MockAuditService)

    service := NewSessionService(
        mockRefreshTokenRepo,
        mockTokenCache,
        mockAuditService,
    )

    userID := uuid.New()
    sessionID := uuid.New()
    tokenHash := "some-hash"

    token := &entity.RefreshToken{
        ID:         sessionID,
        UserID:     userID,
        TokenHash:  tokenHash,
        DeviceID:   "device-1",
        DeviceInfo: "Chrome on Windows",
    }

    mockRefreshTokenRepo.On("GetByID", ctx, sessionID).Return(token, nil)
    mockRefreshTokenRepo.On("Revoke", ctx, sessionID).Return(nil)
    mockAuditService.On("LogSessionRevoked", ctx, userID.String(), sessionID.String(), token.DeviceInfo).Return()

    err := service.RevokeSession(ctx, userID, sessionID, "different-hash")

    require.NoError(t, err)
    mockRefreshTokenRepo.AssertExpectations(t)
    mockAuditService.AssertExpectations(t)
}

func TestSessionService_RevokeSession_WrongUser(t *testing.T) {
    ctx := context.Background()

    mockRefreshTokenRepo := new(MockRefreshTokenRepository)
    mockTokenCache := new(MockTokenCache)
    mockAuditService := new(MockAuditService)

    service := NewSessionService(
        mockRefreshTokenRepo,
        mockTokenCache,
        mockAuditService,
    )

    userID := uuid.New()
    otherUserID := uuid.New()
    sessionID := uuid.New()

    token := &entity.RefreshToken{
        ID:         sessionID,
        UserID:     otherUserID, // Belongs to different user
        TokenHash:  "some-hash",
        DeviceID:   "device-1",
    }

    mockRefreshTokenRepo.On("GetByID", ctx, sessionID).Return(token, nil)

    err := service.RevokeSession(ctx, userID, sessionID, "different-hash")

    assert.Error(t, err)
    assert.Contains(t, err.Error(), "forbidden")
}
```

---

## 5. Checklist

### Session Service

- [x] Create `internal/domain/service/session_service.go`
- [x] Implement `ListSessions` method
- [x] Implement `RevokeSession` method
- [x] Implement `RevokeAllSessions` method
- [x] Implement `GetSessionByID` method
- [ ] Write unit tests

### API Handlers

- [x] Create `internal/api/handler/session_handler.go`
- [x] Implement `ListSessions` endpoint (GET /sessions)
- [x] Implement `RevokeSession` endpoint (DELETE /sessions/:id)
- [x] Implement `RevokeAllSessions` endpoint (DELETE /sessions)
- [x] Add device info formatting
- [ ] Write handler tests

### DTOs

- [x] Create `internal/api/dto/response/session.go`
- [x] Add SessionResponse struct
- [x] Add SessionsResponse struct

### Integration

- [x] Integrate with Module 4 (Authentication)
- [x] Integrate with Module 5 (Token Management)
- [ ] Test end-to-end session listing
- [ ] Test session revocation

---

**Previous:** [module-05-token-management.md](module-05-token-management.md)  
**Next:** [module-07-password-management.md](module-07-password-management.md)
