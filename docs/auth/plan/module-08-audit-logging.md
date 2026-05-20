# Module 8: Audit Logging

> **Module:** Domain Layer  
> **File:** `module-08-audit-logging.md`  
> **Phase:** 3 - Advanced Features  
> **Estimated Time:** 2-3 days

---

## Table of Contents

1. [Overview](#1-overview)
2. [Audit Service](#2-audit-service)
3. [Audit Log Events](#3-audit-log-events)
4. [API Handlers](#4-api-handlers)
5. [Testing](#5-testing)
6. [Checklist](#6-checklist)

---

## 1. Overview

Module này triển khai audit logging:
- Ghi log đồng bộ vào bảng `audit_logs`
- Event types cho các sự kiện nhạy cảm
- API cho user xem lịch sử đăng nhập
- API admin tra cứu audit logs

### Audit Event Types

| Event Type | Actor Type | Security Level | Description |
|------------|------------|----------------|-------------|
| `USER_LOGGED_IN` | USER | INFO | Successful login |
| `USER_LOGIN_FAILED` | GUEST | WARNING | Failed login attempt |
| `USER_LOGGED_OUT` | USER | INFO | User logout |
| `USER_PASSWORD_CHANGED` | USER | INFO | Password change |
| `USER_PASSWORD_RESET` | USER | WARNING | Password reset |
| `USER_PASSWORD_RESET_FAILED` | GUEST | WARNING | Failed password reset |
| `USER_EMAIL_VERIFIED` | USER | INFO | Email verification |
| `USER_ACCOUNT_LOCKED` | SYSTEM | WARNING | Account locked |
| `USER_AUTO_UNLOCKED` | SYSTEM | INFO | Auto unlock |
| `USER_SESSION_REVOKED` | USER | INFO | Session revoked |
| `USER_FORCE_LOGOUT` | SYSTEM | WARNING | Force logout |
| `TOKEN_THEFT_DETECTED` | SYSTEM | CRITICAL | Token theft |
| `ADMIN_USER_BANNED` | ADMIN | CRITICAL | User banned |
| `ADMIN_USER_UNBANNED` | ADMIN | WARNING | User unbanned |
| `ADMIN_USER_UNLOCKED` | ADMIN | INFO | Manual unlock |
| `KEY_ROTATED` | SYSTEM | INFO | JWT key rotated |
| `KEY_RETIRED` | SYSTEM | INFO | JWT key retired |

---

## 2. Audit Service

### 2.1 Audit Service Implementation

```go
// internal/domain/service/audit_service.go
package service

import (
    "context"
    "encoding/json"
    "fmt"
    "time"

    "github.com/google/uuid"

    "github.com/ecommerce/auth-service/internal/common/constants"
    "github.com/ecommerce/auth-service/internal/domain/entity"
    "github.com/ecommerce/auth-service/internal/domain/port"
)

type AuditService struct {
    auditLogRepo port.AuditLogRepository
}

func NewAuditService(auditLogRepo port.AuditLogRepository) *AuditService {
    return &AuditService{
        auditLogRepo: auditLogRepo,
    }
}

// LogLoginSuccess logs successful login
func (s *AuditService) LogLoginSuccess(ctx context.Context, userID, ipAddress, deviceID, deviceInfo string) {
    s.logEvent(ctx, &auditLogInput{
        ActorID:       userID,
        ActorType:     entity.ActorUser,
        EventType:     "USER_LOGGED_IN",
        SecurityLevel: entity.SecurityLevelInfo,
        LogCategory:   entity.LogCategoryUser,
        IPAddress:     ipAddress,
        DeviceID:      deviceID,
        DeviceInfo:    deviceInfo,
        Payload: map[string]interface{}{
            "login_method": "password",
            "success":      true,
        },
    })
}

// LogLoginFailed logs failed login
func (s *AuditService) LogLoginFailed(ctx context.Context, userID, ipAddress, deviceInfo string) {
    actorType := entity.ActorGuest
    if userID != "" {
        actorType = entity.ActorUser
    }

    s.logEvent(ctx, &auditLogInput{
        ActorID:       userID,
        ActorType:     actorType,
        EventType:     "USER_LOGIN_FAILED",
        SecurityLevel: entity.SecurityLevelWarning,
        LogCategory:   entity.LogCategoryUser,
        IPAddress:     ipAddress,
        DeviceInfo:    deviceInfo,
        Payload: map[string]interface{}{
            "success": false,
        },
    })
}

// LogLogout logs user logout
func (s *AuditService) LogLogout(ctx context.Context, userID string) {
    s.logEvent(ctx, &auditLogInput{
        ActorID:       userID,
        ActorType:     entity.ActorUser,
        EventType:     "USER_LOGGED_OUT",
        SecurityLevel: entity.SecurityLevelInfo,
        LogCategory:   entity.LogCategoryUser,
        Payload: map[string]interface{}{
            "logout_type": "manual",
        },
    })
}

// LogPasswordChanged logs password change
func (s *AuditService) LogPasswordChanged(ctx context.Context, userID, ipAddress, deviceInfo string) {
    s.logEvent(ctx, &auditLogInput{
        ActorID:       userID,
        ActorType:     entity.ActorUser,
        EventType:     "USER_PASSWORD_CHANGED",
        SecurityLevel: entity.SecurityLevelInfo,
        LogCategory:   entity.LogCategoryUser,
        IPAddress:     ipAddress,
        DeviceInfo:    deviceInfo,
        Payload: map[string]interface{}{
            "action": "change",
        },
    })
}

// LogPasswordChangeFailed logs failed password change
func (s *AuditService) LogPasswordChangeFailed(ctx context.Context, userID, ipAddress string) {
    s.logEvent(ctx, &auditLogInput{
        ActorID:       userID,
        ActorType:     entity.ActorUser,
        EventType:     "USER_PASSWORD_CHANGE_FAILED",
        SecurityLevel: entity.SecurityLevelWarning,
        LogCategory:   entity.LogCategoryUser,
        IPAddress:     ipAddress,
        Payload: map[string]interface{}{
            "reason": "invalid_current_password",
        },
    })
}

// LogPasswordResetRequested logs password reset request
func (s *AuditService) LogPasswordResetRequested(ctx context.Context, userID, ipAddress string) {
    s.logEvent(ctx, &auditLogInput{
        ActorID:       userID,
        ActorType:     entity.ActorUser,
        EventType:     "PASSWORD_RESET_REQUESTED",
        SecurityLevel: entity.SecurityLevelInfo,
        LogCategory:   entity.LogCategoryUser,
        IPAddress:     ipAddress,
    })
}

// LogPasswordResetSuccess logs successful password reset
func (s *AuditService) LogPasswordResetSuccess(ctx context.Context, userID, ipAddress string) {
    s.logEvent(ctx, &auditLogInput{
        ActorID:       userID,
        ActorType:     entity.ActorUser,
        EventType:     "PASSWORD_RESET_COMPLETED",
        SecurityLevel: entity.SecurityLevelWarning,
        LogCategory:   entity.LogCategoryUser,
        IPAddress:     ipAddress,
    })
}

// LogPasswordResetFailed logs failed password reset
func (s *AuditService) LogPasswordResetFailed(ctx context.Context, userID, ipAddress, reason string) {
    actorType := entity.ActorGuest
    if userID != "" {
        actorType = entity.ActorUser
    }

    s.logEvent(ctx, &auditLogInput{
        ActorID:       userID,
        ActorType:     actorType,
        EventType:     "PASSWORD_RESET_FAILED",
        SecurityLevel: entity.SecurityLevelWarning,
        LogCategory:   entity.LogCategoryUser,
        IPAddress:     ipAddress,
        Payload: map[string]interface{}{
            "reason": reason,
        },
    })
}

// LogAccountLocked logs account lock
func (s *AuditService) LogAccountLocked(ctx context.Context, userID, reason string) {
    s.logEvent(ctx, &auditLogInput{
        ActorID:       userID,
        ActorType:     entity.ActorUser,
        EventType:     "USER_ACCOUNT_LOCKED",
        SecurityLevel: entity.SecurityLevelWarning,
        LogCategory:   entity.LogCategoryUser,
        Payload: map[string]interface{}{
            "reason": reason,
        },
    })
}

// LogAutoUnlocked logs automatic unlock
func (s *AuditService) LogAutoUnlocked(ctx context.Context, userID string) {
    s.logEvent(ctx, &auditLogInput{
        ActorID:       userID,
        ActorType:     entity.ActorSystem,
        EventType:     "USER_AUTO_UNLOCKED",
        SecurityLevel: entity.SecurityLevelInfo,
        LogCategory:   entity.LogCategoryUser,
        Payload: map[string]interface{}{
            "unlock_type": "automatic",
        },
    })
}

// LogSessionRevoked logs session revocation
func (s *AuditService) LogSessionRevoked(ctx context.Context, userID, sessionID, deviceInfo string) {
    s.logEvent(ctx, &auditLogInput{
        ActorID:       userID,
        ActorType:     entity.ActorUser,
        EventType:     "USER_SESSION_REVOKED",
        SecurityLevel: entity.SecurityLevelInfo,
        LogCategory:   entity.LogCategoryUser,
        DeviceInfo:    deviceInfo,
        Payload: map[string]interface{}{
            "session_id": sessionID,
        },
    })
}

// LogAllSessionsRevoked logs all sessions revocation
func (s *AuditService) LogAllSessionsRevoked(ctx context.Context, userID string, revokedCount int) {
    s.logEvent(ctx, &auditLogInput{
        ActorID:       userID,
        ActorType:     entity.ActorUser,
        EventType:     "USER_ALL_SESSIONS_REVOKED",
        SecurityLevel: entity.SecurityLevelWarning,
        LogCategory:   entity.LogCategoryUser,
        Payload: map[string]interface{}{
            "revoked_count": revokedCount,
        },
    })
}

// LogForceLogout logs force logout
func (s *AuditService) LogForceLogout(ctx context.Context, userID, reason string) {
    s.logEvent(ctx, &auditLogInput{
        ActorID:       userID,
        ActorType:     entity.ActorSystem,
        EventType:     "USER_FORCE_LOGOUT",
        SecurityLevel: entity.SecurityLevelWarning,
        LogCategory:   entity.LogCategoryUser,
        Payload: map[string]interface{}{
            "reason": reason,
        },
    })
}

// LogTokenTheftDetected logs token theft detection
func (s *AuditService) LogTokenTheftDetected(ctx context.Context, userID, tokenID string) {
    s.logEvent(ctx, &auditLogInput{
        ActorID:       userID,
        ActorType:     entity.ActorSystem,
        EventType:     "TOKEN_THEFT_DETECTED",
        SecurityLevel: entity.SecurityLevelCritical,
        LogCategory:   entity.LogCategorySystem,
        Payload: map[string]interface{}{
            "token_id": tokenID,
        },
    })
}

// LogAdminBan logs admin ban action
func (s *AuditService) LogAdminBan(ctx context.Context, adminID, userID, reason string) {
    s.logEvent(ctx, &auditLogInput{
        ActorID:       adminID,
        ActorType:     entity.ActorAdmin,
        EventType:     "ADMIN_USER_BANNED",
        SecurityLevel: entity.SecurityLevelCritical,
        LogCategory:   entity.LogCategorySystem,
        Payload: map[string]interface{}{
            "target_user_id": userID,
            "reason":        reason,
        },
    })
}

// LogAdminUnban logs admin unban action
func (s *AuditService) LogAdminUnban(ctx context.Context, adminID, userID string) {
    s.logEvent(ctx, &auditLogInput{
        ActorID:       adminID,
        ActorType:     entity.ActorAdmin,
        EventType:     "ADMIN_USER_UNBANNED",
        SecurityLevel: entity.SecurityLevelWarning,
        LogCategory:   entity.LogCategorySystem,
        Payload: map[string]interface{}{
            "target_user_id": userID,
        },
    })
}

// LogAdminUnlock logs admin unlock action
func (s *AuditService) LogAdminUnlock(ctx context.Context, adminID, userID string) {
    s.logEvent(ctx, &auditLogInput{
        ActorID:       adminID,
        ActorType:     entity.ActorAdmin,
        EventType:     "ADMIN_USER_UNLOCKED",
        SecurityLevel: entity.SecurityLevelInfo,
        LogCategory:   entity.LogCategorySystem,
        Payload: map[string]interface{}{
            "target_user_id": userID,
        },
    })
}

// LogSecurityWarning logs security warning
func (s *AuditService) LogSecurityWarning(ctx context.Context, userID, eventType, ipAddress string) {
    s.logEvent(ctx, &auditLogInput{
        ActorID:       userID,
        ActorType:     entity.ActorSystem,
        EventType:     eventType,
        SecurityLevel: entity.SecurityLevelWarning,
        LogCategory:   entity.LogCategorySystem,
        IPAddress:     ipAddress,
    })
}

// Internal helper

type auditLogInput struct {
    ActorID       string
    ActorType     entity.ActorType
    EventType     string
    SecurityLevel entity.SecurityLevel
    LogCategory   entity.LogCategory
    IPAddress     string
    DeviceID      string
    DeviceInfo    string
    Payload       map[string]interface{}
}

func (s *AuditService) logEvent(ctx context.Context, input *auditLogInput) {
    // Convert payload to JSON
    var payload json.RawMessage
    if input.Payload != nil {
        if data, err := json.Marshal(input.Payload); err == nil {
            payload = data
        }
    }

    auditLog := &entity.AuditLog{
        ID:             uuid.New().String(),
        ActorID:       input.ActorID,
        ActorType:     input.ActorType,
        EventType:     input.EventType,
        OccurredAt:     time.Now().UTC(),
        IPAddress:      input.IPAddress,
        DeviceID:      input.DeviceID,
        DeviceInfo:     input.DeviceInfo,
        SecurityLevel: input.SecurityLevel,
        Payload:       payload,
        LogCategory:   input.LogCategory,
    }

    // Log synchronously to audit_logs table
    if err := s.auditLogRepo.Create(ctx, auditLog); err != nil {
        // Log error but don't fail
        fmt.Printf("Failed to create audit log: %v\n", err)
    }
}
```

---

## 3. Audit Log Events

### 3.1 Audit Events Definition

```go
// internal/domain/event/audit_events.go
package event

import (
    "encoding/json"
    "time"

    "github.com/google/uuid"
)

// Audit event types
const (
    EventAuditLogCreated = "AUDIT_LOG_CREATED"
)

// AuditLogCreated represents an audit log creation event
type AuditLogCreated struct {
    EventBase
    Payload AuditLogPayload `json:"payload"`
}

type AuditLogPayload struct {
    AuditLogID   string `json:"audit_log_id"`
    ActorID      string `json:"actor_id"`
    ActorType    string `json:"actor_type"`
    EventType    string `json:"event_type"`
    IPAddress    string `json:"ip_address,omitempty"`
    SecurityLevel string `json:"security_level"`
}

func NewAuditLogCreated(auditLogID, actorID, actorType, eventType, securityLevel string) *AuditLogCreated {
    return &AuditLogCreated{
        EventBase: NewEventBase(EventAuditLogCreated, "AUDIT_LOG", auditLogID),
        Payload: AuditLogPayload{
            AuditLogID:    auditLogID,
            ActorID:       actorID,
            ActorType:     actorType,
            EventType:     eventType,
            SecurityLevel: securityLevel,
        },
    }
}

func (e *AuditLogCreated) ToJSON() ([]byte, error) {
    return json.Marshal(e)
}
```

---

## 4. API Handlers

### 4.1 Audit Handler

```go
// internal/api/handler/audit_handler.go
package handler

import (
    "net/http"
    "strconv"
    "time"

    "github.com/gin-gonic/gin"
    "github.com/google/uuid"

    "github.com/ecommerce/auth-service/internal/api/dto/response"
    "github.com/ecommerce/auth-service/internal/api/middleware"
    "github.com/ecommerce/auth-service/internal/domain/port"
    apperrors "github.com/ecommerce/auth-service/internal/common/errors"
)

type AuditHandler struct {
    auditService *AuditService
    auditLogRepo port.AuditLogRepository
}

func NewAuditHandler(auditService *AuditService, auditLogRepo port.AuditLogRepository) *AuditHandler {
    return &AuditHandler{
        auditService: auditService,
        auditLogRepo: auditLogRepo,
    }
}

// GetMyLoginHistory handles GET /api/v1/auth/audit/my-login-history
func (h *AuditHandler) GetMyLoginHistory(c *gin.Context) {
    userID, err := getUserIDFromContext(c)
    if err != nil {
        middleware.Error(c, apperrors.ErrUnauthorized)
        return
    }

    // Parse pagination
    limit := 20
    if limitStr := c.Query("limit"); limitStr != "" {
        if l, err := strconv.Atoi(limitStr); err == nil && l > 0 && l <= 100 {
            limit = l
        }
    }

    offset := 0
    if offsetStr := c.Query("offset"); offsetStr != "" {
        if o, err := strconv.Atoi(offsetStr); err == nil && o >= 0 {
            offset = o
        }
    }

    // Get login history
    logs, err := h.auditLogRepo.GetLoginHistory(c.Request.Context(), userID.String(), limit)
    if err != nil {
        handleServiceError(c, err)
        return
    }

    // Format response
    history := make([]response.LoginHistoryItem, 0, len(logs))
    for _, log := range logs {
        history = append(history, response.LoginHistoryItem{
            ID:          log.ID,
            EventType:   log.EventType,
            IPAddress:   log.IPAddress,
            DeviceInfo:  formatDeviceInfo(log.DeviceInfo),
            OccurredAt:  log.OccurredAt,
        })
    }

    c.JSON(http.StatusOK, response.LoginHistoryResponse{
        History: history,
        Total:   len(history),
        Limit:   limit,
        Offset:  offset,
    })
}

// GetAuditLogs handles GET /api/v1/auth/admin/audit/history-logs (Admin)
func (h *AuditHandler) GetAuditLogs(c *gin.Context) {
    // Parse filters
    filters := port.AuditLogFilters{}

    if actorID := c.Query("actor_id"); actorID != "" {
        filters.ActorID = actorID
    }

    if actorType := c.Query("actor_type"); actorType != "" {
        filters.ActorType = entity.ActorType(actorType)
    }

    if eventType := c.Query("event_type"); eventType != "" {
        filters.EventType = eventType
    }

    if securityLevel := c.Query("security_level"); securityLevel != "" {
        filters.SecurityLevel = entity.SecurityLevel(securityLevel)
    }

    if logCategory := c.Query("log_category"); logCategory != "" {
        filters.LogCategory = entity.LogCategory(logCategory)
    }

    if fromStr := c.Query("from"); fromStr != "" {
        if from, err := time.Parse(time.RFC3339, fromStr); err == nil {
            filters.From = from
        }
    }

    if toStr := c.Query("to"); toStr != "" {
        if to, err := time.Parse(time.RFC3339, toStr); err == nil {
            filters.To = to
        }
    }

    // Parse pagination
    limit := 50
    if limitStr := c.Query("limit"); limitStr != "" {
        if l, err := strconv.Atoi(limitStr); err == nil && l > 0 && l <= 100 {
            limit = l
        }
    }

    offset := 0
    if offsetStr := c.Query("offset"); offsetStr != "" {
        if o, err := strconv.Atoi(offsetStr); err == nil && o >= 0 {
            offset = o
        }
    }

    // Get audit logs
    logs, total, err := h.auditLogRepo.GetByFilters(c.Request.Context(), filters, limit, offset)
    if err != nil {
        handleServiceError(c, err)
        return
    }

    // Format response
    auditLogs := make([]response.AuditLogItem, 0, len(logs))
    for _, log := range logs {
        auditLogs = append(auditLogs, response.AuditLogItem{
            ID:            log.ID,
            ActorID:       log.ActorID,
            ActorType:     string(log.ActorType),
            EventType:     log.EventType,
            OccurredAt:    log.OccurredAt,
            IPAddress:     log.IPAddress,
            DeviceInfo:    log.DeviceInfo,
            SecurityLevel: string(log.SecurityLevel),
            Payload:       parsePayload(log.Payload),
        })
    }

    c.JSON(http.StatusOK, response.AuditLogsResponse{
        Logs:   auditLogs,
        Total:  total,
        Limit:  limit,
        Offset: offset,
    })
}

// Helper function to format device info
func formatDeviceInfo(info string) string {
    if info == "" {
        return "Unknown Device"
    }
    return info
}

// Helper function to parse payload
func parsePayload(payload json.RawMessage) map[string]interface{} {
    if payload == nil {
        return nil
    }
    var result map[string]interface{}
    json.Unmarshal(payload, &result)
    return result
}

import "encoding/json"
```

### 4.2 Response DTOs

```go
// internal/api/dto/response/audit.go
package response

import "time"

type LoginHistoryItem struct {
    ID         string    `json:"id"`
    EventType  string    `json:"event_type"`
    IPAddress  string    `json:"ip_address"`
    DeviceInfo string    `json:"device_info"`
    OccurredAt time.Time `json:"occurred_at"`
}

type LoginHistoryResponse struct {
    History []LoginHistoryItem `json:"history"`
    Total   int                `json:"total"`
    Limit   int                `json:"limit"`
    Offset  int                `json:"offset"`
}

type AuditLogItem struct {
    ID            string                 `json:"id"`
    ActorID       string                `json:"actor_id"`
    ActorType     string                `json:"actor_type"`
    EventType     string                `json:"event_type"`
    OccurredAt    time.Time            `json:"occurred_at"`
    IPAddress     string                `json:"ip_address,omitempty"`
    DeviceInfo   string                `json:"device_info,omitempty"`
    SecurityLevel string                `json:"security_level"`
    Payload      map[string]interface{} `json:"payload,omitempty"`
}

type AuditLogsResponse struct {
    Logs   []AuditLogItem `json:"logs"`
    Total  int            `json:"total"`
    Limit  int            `json:"limit"`
    Offset int            `json:"offset"`
}
```

---

## 5. Testing

### 5.1 Audit Service Test

```go
// test/unit/domain/service/audit_service_test.go
package service

import (
    "context"
    "testing"

    "github.com/stretchr/testify/assert"
    "github.com/stretchr/testify/mock"
    "github.com/stretchr/testify/require"

    "github.com/ecommerce/auth-service/internal/domain/port"
)

func TestAuditService_LogLoginSuccess(t *testing.T) {
    ctx := context.Background()

    mockAuditLogRepo := new(MockAuditLogRepository)
    service := NewAuditService(mockAuditLogRepo)

    mockAuditLogRepo.On("Create", ctx, mock.AnythingOfType("*entity.AuditLog")).Return(nil)

    service.LogLoginSuccess(ctx, "user-123", "192.168.1.1", "device-456", "Chrome on Windows")

    mockAuditLogRepo.AssertExpectations(t)
}

func TestAuditService_LogLoginFailed(t *testing.T) {
    ctx := context.Background()

    mockAuditLogRepo := new(MockAuditLogRepository)
    service := NewAuditService(mockAuditLogRepo)

    mockAuditLogRepo.On("Create", ctx, mock.AnythingOfType("*entity.AuditLog")).Return(nil)

    service.LogLoginFailed(ctx, "user-123", "192.168.1.1", "Chrome on Windows")

    mockAuditLogRepo.AssertExpectations(t)
}

func TestAuditService_LogTokenTheft(t *testing.T) {
    ctx := context.Background()

    mockAuditLogRepo := new(MockAuditLogRepository)
    service := NewAuditService(mockAuditLogRepo)

    mockAuditLogRepo.On("Create", ctx, mock.MatchedBy(func(log *entity.AuditLog) bool {
        return log.EventType == "TOKEN_THEFT_DETECTED" &&
            log.SecurityLevel == entity.SecurityLevelCritical
    })).Return(nil)

    service.LogTokenTheftDetected(ctx, "user-123", "token-456")

    mockAuditLogRepo.AssertExpectations(t)
}
```

---

## 6. Checklist

### Audit Service

- [x] Create `internal/domain/service/audit_service.go`
- [x] Implement login logging methods
- [x] Implement password change logging methods
- [x] Implement account status logging methods
- [ ] Implement admin action logging methods
- [x] Implement security event logging methods
- [ ] Write unit tests

### API Handlers

- [x] Create `internal/api/handler/audit_handler.go`
- [x] Implement `GetMyLoginHistory` endpoint
- [x] Implement `GetAuditLogs` admin endpoint
- [x] Add pagination support
- [x] Add filter support
- [ ] Write handler tests

### DTOs

- [x] Create `internal/api/dto/response/audit.go`
- [x] Add LoginHistoryItem
- [x] Add AuditLogItem

### Integration

- [x] Integrate with all other modules for logging
- [ ] Test end-to-end audit trail
- [ ] Verify all events are logged correctly

---

**Previous:** [module-07-password-management.md](module-07-password-management.md)  
**Next:** [module-09-jwks-management.md](module-09-jwks-management.md)
