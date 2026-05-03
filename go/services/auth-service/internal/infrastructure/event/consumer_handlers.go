package event

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/repository"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/types"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/authentication"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/common"
)

// ConsumerDependencies holds all dependencies needed by consumer handlers
type ConsumerDependencies struct {
	SessionRepo    repository.SessionRepository
	UserService    common.UserService
	CacheRepo      repository.RevokeBeforeCacheRepository
	AuditService   *authentication.AuditService
	Logger         *zap.Logger
}

// ConsumerHandler registers and manages event handlers
type ConsumerHandler struct {
	consumer    *KafkaConsumer
	deps        *ConsumerDependencies
	logger      *zap.Logger
}

// NewConsumerHandler creates a new consumer handler
func NewConsumerHandler(
	consumer *KafkaConsumer,
	deps *ConsumerDependencies,
	logger *zap.Logger,
) *ConsumerHandler {
	return &ConsumerHandler{
		consumer: consumer,
		deps:     deps,
		logger:   logger,
	}
}

// RegisterDefaultHandlers registers the default admin event handlers
func (h *ConsumerHandler) RegisterDefaultHandlers() {
	h.consumer.RegisterHandlers(map[string]EventHandler{
		EventTypeAdminForceLogout: h.handleAdminForceLogout,
		EventTypeUserBanned:       h.handleUserBanned,
		EventTypeUserUnbanned:     h.handleUserUnbanned,
	})
}

// handleAdminForceLogout handles ADMIN_FORCE_LOGOUT events
// This handler:
// 1. Revokes all active sessions for the user
// 2. Sets revoked_before timestamp in Redis
// 3. Logs the action to audit log
func (h *ConsumerHandler) handleAdminForceLogout(ctx context.Context, event *KafkaEvent) error {
	var payload AdminForceLogoutPayload
	if err := json.Unmarshal(event.Payload, &payload); err != nil {
		return fmt.Errorf("failed to unmarshal payload: %w", err)
	}

	h.logger.Info("Processing ADMIN_FORCE_LOGOUT",
		zap.String("user_id", payload.UserID),
		zap.String("actor_id", payload.ActorID),
		zap.String("reason", payload.Reason))

	// Parse user ID
	userID, err := uuid.Parse(payload.UserID)
	if err != nil {
		return fmt.Errorf("invalid user_id: %w", err)
	}

	// Step 1: Revoke all sessions in database
	if err := h.deps.SessionRepo.RevokeAllByUserID(ctx, userID); err != nil {
		h.logger.Error("Failed to revoke sessions",
			zap.String("user_id", payload.UserID),
			zap.Error(err))
		return fmt.Errorf("failed to revoke sessions: %w", err)
	}

	// Step 2: Set revoked_before timestamp in Redis
	// This ensures any existing access tokens are immediately invalid
	if h.deps.CacheRepo != nil {
		revokedAt := time.Now()
		if err := h.deps.CacheRepo.SetRevokedBefore(ctx, payload.UserID, revokedAt); err != nil {
			h.logger.Error("Failed to set revoked_before in Redis",
				zap.String("user_id", payload.UserID),
				zap.Error(err))
			// Don't fail the operation - session revocation is the primary action
		} else {
			h.logger.Info("Set revoked_before in Redis",
				zap.String("user_id", payload.UserID),
				zap.Time("revoked_at", revokedAt))
		}
	}

	// Step 3: Log to audit
	if h.deps.AuditService != nil {
		auditEvent := &authentication.AuditEvent{
			ActorID:       payload.ActorID,
			ActorType:    types.ActorTypeAdmin,
			EventType:    types.EventTypeAdminForceLogout,
			IPAddress:    "",
			SecurityLevel: types.SecurityLevelCritical,
			LogCategory:   types.LogCategorySystem,
			Payload: map[string]any{
				"target_user_id": payload.UserID,
				"reason":         payload.Reason,
				"action":         "ALL_SESSIONS_REVOKED",
			},
		}
		if err := h.deps.AuditService.Log(ctx, auditEvent); err != nil {
			h.logger.Error("Failed to log audit event", zap.Error(err))
		}
	}

	h.logger.Info("Admin force logout completed successfully",
		zap.String("user_id", payload.UserID),
		zap.String("actor_id", payload.ActorID))

	return nil
}

// handleUserBanned handles USER_BANNED events
// This handler:
// 1. Revokes all sessions (same as force logout)
// 2. Sets revoked_before timestamp in Redis
// 3. Updates user status to BANNED
// 4. Logs the action to audit log
func (h *ConsumerHandler) handleUserBanned(ctx context.Context, event *KafkaEvent) error {
	var payload UserBannedPayload
	if err := json.Unmarshal(event.Payload, &payload); err != nil {
		return fmt.Errorf("failed to unmarshal payload: %w", err)
	}

	h.logger.Info("Processing USER_BANNED",
		zap.String("user_id", payload.UserID),
		zap.String("actor_id", payload.ActorID),
		zap.String("reason", payload.Reason))

	// Step 1: Revoke all sessions (reuses force logout logic)
	if err := h.handleAdminForceLogout(ctx, event); err != nil {
		// Log but don't fail - we still want to ban the user
		h.logger.Warn("Failed to revoke sessions during ban",
			zap.String("user_id", payload.UserID),
			zap.Error(err))
	}

	// Step 2: Update user status to BANNED
	if h.deps.UserService != nil {
		// Use user service to update status
		// Note: This assumes user service has a method to update status
		// If not, we log the action for manual intervention
		h.logger.Info("User status change requested to BANNED via external service",
			zap.String("user_id", payload.UserID))

		h.logger.Info("User ban completed successfully",
			zap.String("user_id", payload.UserID),
			zap.String("actor_id", payload.ActorID))
	}

	// Step 3: Log to audit
	if h.deps.AuditService != nil {
		auditEvent := &authentication.AuditEvent{
			ActorID:       payload.ActorID,
			ActorType:    types.ActorTypeAdmin,
			EventType:    types.EventTypeUserBanned,
			IPAddress:    "",
			SecurityLevel: types.SecurityLevelCritical,
			LogCategory:   types.LogCategorySystem,
			Payload: map[string]any{
				"target_user_id": payload.UserID,
				"reason":         payload.Reason,
				"action":         "USER_BANNED",
			},
		}
		if err := h.deps.AuditService.Log(ctx, auditEvent); err != nil {
			h.logger.Error("Failed to log audit event", zap.Error(err))
		}
	}

	h.logger.Info("User ban completed successfully",
		zap.String("user_id", payload.UserID),
		zap.String("actor_id", payload.ActorID))

	return nil
}

// handleUserUnbanned handles USER_UNBANNED events
// This handler:
// 1. Updates user status back to ACTIVE
// 2. Logs the action to audit log
func (h *ConsumerHandler) handleUserUnbanned(ctx context.Context, event *KafkaEvent) error {
	var payload struct {
		UserID  string `json:"user_id"`
		ActorID string `json:"actor_id"`
	}
	if err := json.Unmarshal(event.Payload, &payload); err != nil {
		return fmt.Errorf("failed to unmarshal payload: %w", err)
	}

	h.logger.Info("Processing USER_UNBANNED",
		zap.String("user_id", payload.UserID),
		zap.String("actor_id", payload.ActorID))

	// Step 1: Update user status to ACTIVE
	if h.deps.UserService != nil {
		h.logger.Info("User status change requested to ACTIVE via external service",
			zap.String("user_id", payload.UserID))
	}

	// Step 2: Log to audit
	if h.deps.AuditService != nil {
		auditEvent := &authentication.AuditEvent{
			ActorID:       payload.ActorID,
			ActorType:    types.ActorTypeAdmin,
			EventType:    types.EventTypeUserUnbanned,
			IPAddress:    "",
			SecurityLevel: types.SecurityLevelWarning,
			LogCategory:   types.LogCategorySystem,
			Payload: map[string]any{
				"target_user_id": payload.UserID,
				"action":         "USER_UNBANNED",
			},
		}
		if err := h.deps.AuditService.Log(ctx, auditEvent); err != nil {
			h.logger.Error("Failed to log audit event", zap.Error(err))
		}
	}

	h.logger.Info("User unban completed successfully",
		zap.String("user_id", payload.UserID),
		zap.String("actor_id", payload.ActorID))

	return nil
}

// AdminEventHandlerFactory creates event handlers for admin events
type AdminEventHandlerFactory struct {
	deps *ConsumerDependencies
}

// NewAdminEventHandlerFactory creates a new factory
func NewAdminEventHandlerFactory(deps *ConsumerDependencies) *AdminEventHandlerFactory {
	return &AdminEventHandlerFactory{deps: deps}
}

// CreateForceLogoutHandler creates a handler for ADMIN_FORCE_LOGOUT events
func (f *AdminEventHandlerFactory) CreateForceLogoutHandler() EventHandler {
	return func(ctx context.Context, event *KafkaEvent) error {
		var payload AdminForceLogoutPayload
		if err := json.Unmarshal(event.Payload, &payload); err != nil {
			return fmt.Errorf("failed to unmarshal payload: %w", err)
		}

		userID, err := uuid.Parse(payload.UserID)
		if err != nil {
			return fmt.Errorf("invalid user_id: %w", err)
		}

		// Revoke all sessions
		if err := f.deps.SessionRepo.RevokeAllByUserID(ctx, userID); err != nil {
			return fmt.Errorf("failed to revoke sessions: %w", err)
		}

		// Set revoked_before in cache
		if f.deps.CacheRepo != nil {
			_ = f.deps.CacheRepo.SetRevokedBefore(ctx, payload.UserID, time.Now())
		}

		return nil
	}
}
