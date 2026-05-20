package handler

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"

	"github.com/IBM/sarama"
	"go.uber.org/zap"
)

// AdminForceLogoutPayload - event received from Admin Dashboard
type AdminForceLogoutPayload struct {
	UserID  string `json:"user_id"`
	Reason  string `json:"reason"`
	AdminID string `json:"admin_id,omitempty"`
}

// UserBannedPayload - event received from Admin Dashboard
type UserBannedPayload struct {
	UserID  string `json:"user_id"`
	Reason  string `json:"reason"`
	AdminID string `json:"admin_id,omitempty"`
}

type AdminEventHandler struct {
	forceLogoutFunc func(ctx context.Context, userID string, reason string) error
	banUserFunc     func(ctx context.Context, userID string, reason string) error
	logger          *zap.Logger
}

func NewAdminEventHandler(
	forceLogoutFunc func(ctx context.Context, userID string, reason string) error,
	banUserFunc func(ctx context.Context, userID string, reason string) error,
	logger *zap.Logger,
) *AdminEventHandler {
	return &AdminEventHandler{
		forceLogoutFunc: forceLogoutFunc,
		banUserFunc:     banUserFunc,
		logger:          logger.With(zap.String("component", "admin_event_handler")),
	}
}

func (h *AdminEventHandler) HandleMessage(ctx context.Context, msg *sarama.ConsumerMessage) error {
	var event map[string]interface{}
	if err := json.Unmarshal(msg.Value, &event); err != nil {
		return fmt.Errorf("failed to unmarshal event: %w", err)
	}

	eventType, ok := event["event_type"].(string)
	if !ok {
		return fmt.Errorf("missing or invalid event_type in message")
	}

	payload, ok := event["payload"].(map[string]interface{})
	if !ok {
		return fmt.Errorf("missing or invalid payload in message")
	}

	switch strings.ToUpper(eventType) {
	case "ADMIN_FORCE_LOGOUT":
		return h.handleForceLogout(ctx, payload)
	case "USER_BANNED":
		return h.handleUserBanned(ctx, payload)
	default:
		h.logger.Debug("Ignoring unknown admin event type",
			zap.String("event_type", eventType),
		)
		return nil
	}
}

func (h *AdminEventHandler) handleForceLogout(ctx context.Context, payload map[string]interface{}) error {
	userID, ok := payload["user_id"].(string)
	if !ok || userID == "" {
		return fmt.Errorf("missing or invalid user_id in ADMIN_FORCE_LOGOUT payload")
	}

	reason, _ := payload["reason"].(string)
	if reason == "" {
		reason = "admin_force_logout"
	}

	h.logger.Info("Processing ADMIN_FORCE_LOGOUT",
		zap.String("user_id", userID),
		zap.String("reason", reason),
	)

	if err := h.forceLogoutFunc(ctx, userID, reason); err != nil {
		return fmt.Errorf("failed to force logout user %s: %w", userID, err)
	}

	h.logger.Info("ADMIN_FORCE_LOGOUT completed",
		zap.String("user_id", userID),
	)
	return nil
}

func (h *AdminEventHandler) handleUserBanned(ctx context.Context, payload map[string]interface{}) error {
	userID, ok := payload["user_id"].(string)
	if !ok || userID == "" {
		return fmt.Errorf("missing or invalid user_id in USER_BANNED payload")
	}

	reason, _ := payload["reason"].(string)
	if reason == "" {
		reason = "admin_ban"
	}

	h.logger.Info("Processing USER_BANNED",
		zap.String("user_id", userID),
		zap.String("reason", reason),
	)

	if err := h.banUserFunc(ctx, userID, reason); err != nil {
		return fmt.Errorf("failed to ban user %s: %w", userID, err)
	}

	h.logger.Info("USER_BANNED completed",
		zap.String("user_id", userID),
	)
	return nil
}
