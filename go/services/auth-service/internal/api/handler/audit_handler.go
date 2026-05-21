package handler

import (
	"encoding/json"
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/api/dto/response"
	apperrors "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/domain/port"
	"github.com/ecommerce/auth-service/internal/domain/service"
)

type AuditHandler struct {
	auditService *service.AuditService
	logger       *zap.Logger
}

func NewAuditHandler(
	auditService *service.AuditService,
	logger *zap.Logger,
) *AuditHandler {
	return &AuditHandler{
		auditService: auditService,
		logger:       logger.With(zap.String("handler", "AuditHandler")),
	}
}

// GetMyLoginHistory handles GET /api/v1/auth/audit/my-login-history
func (h *AuditHandler) GetMyLoginHistory(c *gin.Context) {
	userID, exists := c.Get("user_id")
	if !exists {
		respondError(c, apperrors.ErrUnauthorized)
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

	// Get login history via service layer
	logs, err := h.auditService.GetLoginHistory(c.Request.Context(), userID.(string), limit)
	if err != nil {
		h.logger.Error("Failed to get login history",
			zap.String("user_id", userID.(string)),
			zap.Error(err),
		)
		respondError(c, apperrors.ErrInternal)
		return
	}

	// Format response
	history := make([]*response.LoginHistoryItem, 0, len(logs))
	for _, log := range logs {
		history = append(history, &response.LoginHistoryItem{
			ID:         log.ID,
			EventType:  log.EventType,
			IPAddress:  log.IPAddress,
			DeviceInfo: formatDeviceInfo(log.DeviceInfo),
			OccurredAt: log.OccurredAt,
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
		filters.ActorType = actorType
	}

	if eventType := c.Query("event_type"); eventType != "" {
		filters.EventType = eventType
	}

	if securityLevel := c.Query("security_level"); securityLevel != "" {
		filters.SecurityLevel = securityLevel
	}

	if logCategory := c.Query("log_category"); logCategory != "" {
		filters.LogCategory = logCategory
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

	// Get audit logs via service layer
	logs, total, err := h.auditService.GetAuditLogs(c.Request.Context(), filters, limit, offset)
	if err != nil {
		h.logger.Error("Failed to get audit logs", zap.Error(err))
		respondError(c, apperrors.ErrInternal)
		return
	}

	// Format response
	auditLogs := make([]*response.AuditLogItem, 0, len(logs))
	for _, log := range logs {
		auditLogs = append(auditLogs, &response.AuditLogItem{
			ID:            log.ID,
			ActorID:       log.ActorID,
			ActorType:     string(log.ActorType),
			EventType:     log.EventType,
			OccurredAt:    log.OccurredAt,
			IPAddress:     log.IPAddress,
			DeviceInfo:    formatDeviceInfo(log.DeviceInfo),
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

// GetAuditLogsByTimeRange handles GET /api/v1/auth/admin/audit/by-time-range
func (h *AuditHandler) GetAuditLogsByTimeRange(c *gin.Context) {
	fromStr := c.Query("from")
	toStr := c.Query("to")

	if fromStr == "" || toStr == "" {
		respondError(c, apperrors.NewAppError("BAD_REQUEST", "from and to are required", http.StatusBadRequest, nil))
		return
	}

	from, err := time.Parse(time.RFC3339, fromStr)
	if err != nil {
		respondError(c, apperrors.NewAppError("BAD_REQUEST", "invalid from date format", http.StatusBadRequest, nil))
		return
	}

	to, err := time.Parse(time.RFC3339, toStr)
	if err != nil {
		respondError(c, apperrors.NewAppError("BAD_REQUEST", "invalid to date format", http.StatusBadRequest, nil))
		return
	}

	limit := 100
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

	// Get audit logs via service layer
	logs, err := h.auditService.GetAuditLogsByTimeRange(c.Request.Context(), from, to, limit, offset)
	if err != nil {
		h.logger.Error("Failed to get audit logs by time range", zap.Error(err))
		respondError(c, apperrors.ErrInternal)
		return
	}

	auditLogs := make([]*response.AuditLogItem, 0, len(logs))
	for _, log := range logs {
		auditLogs = append(auditLogs, &response.AuditLogItem{
			ID:            log.ID,
			ActorID:       log.ActorID,
			ActorType:     string(log.ActorType),
			EventType:     log.EventType,
			OccurredAt:    log.OccurredAt,
			IPAddress:     log.IPAddress,
			DeviceInfo:    formatDeviceInfo(log.DeviceInfo),
			SecurityLevel: string(log.SecurityLevel),
			Payload:       parsePayload(log.Payload),
		})
	}

	c.JSON(http.StatusOK, response.AuditLogsResponse{
		Logs:   auditLogs,
		Total:  len(auditLogs),
		Limit:  limit,
		Offset: offset,
	})
}

// parsePayload parses JSON payload
func parsePayload(payload json.RawMessage) map[string]interface{} {
	if payload == nil {
		return nil
	}
	var result map[string]interface{}
	if err := json.Unmarshal(payload, &result); err != nil {
		return nil
	}
	return result
}
