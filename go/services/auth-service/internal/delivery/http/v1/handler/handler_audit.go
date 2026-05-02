package v1_handler

import (
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"go.uber.org/zap"

	v1 "github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/delivery/http/v1"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/entity"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/types"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/authentication"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/usecase/dto"
)

// AuditHandler handles audit-related HTTP requests
type AuditHandler struct {
	auditService *authentication.AuditService
	logger      *zap.Logger
}

// NewAuditHandler creates new audit handler
func NewAuditHandler(
	auditService *authentication.AuditService,
	logger *zap.Logger,
) *AuditHandler {
	return &AuditHandler{
		auditService: auditService,
		logger:       logger,
	}
}

// GetMyLoginHistory returns the login history for the authenticated user
// @Summary Get my login history
// @Description Get paginated login history for the authenticated user
// @Tags audit
// @Produce json
// @Param X-User-Id header string true "User ID from Gateway"
// @Param page query int false "Page number (default: 0)"
// @Param limit query int false "Items per page (default: 20, max: 100)"
// @Success 200 {object} v1.PaginatedResponse
// @Failure 401 {object} v1.ErrorResponse
// @Failure 500 {object} v1.ErrorResponse
// @Router /api/v1/auth/audit/my-login-history [get]
func (h *AuditHandler) GetMyLoginHistory(c *gin.Context) {
	userID := c.GetHeader("X-User-Id")
	if userID == "" {
		v1.SendErrorResponse(c, http.StatusUnauthorized, "UNAUTHORIZED", "User ID not found in request header", nil)
		return
	}

	page := parseIntQuery(c, "page", 0)
	limit := parseIntQuery(c, "limit", 20)
	if limit > 100 {
		limit = 100
	}
	offset := page * limit

	events, err := h.auditService.GetAuditLogs(c.Request.Context(), userID, limit, offset)
	if err != nil {
		h.logger.Error("Failed to get login history", zap.Error(err))
		v1.SendErrorResponse(c, http.StatusInternalServerError, "INTERNAL_ERROR", "Failed to retrieve login history", err)
		return
	}

	eventDTOs := make([]*dto.AuditLogDTO, 0, len(events))
	for _, event := range events {
		eventDTOs = append(eventDTOs, dto.NewAuditLogDTO(event))
	}

	v1.SendSuccessResponse(c, http.StatusOK, eventDTOs)
}

// GetAdminHistoryLogs returns audit logs for admin users with advanced filtering
// @Summary Get admin audit history logs
// @Description Get paginated audit logs with advanced filtering (admin only)
// @Tags audit
// @Produce json
// @Security BearerAuth
// @Param X-User-Id header string true "User ID from Gateway"
// @Param X-User-Roles header string true "User roles from Gateway (comma-separated)"
// @Param from_date query string false "Start date (RFC3339 format)"
// @Param to_date query string false "End date (RFC3339 format)"
// @Param event_type query string false "Filter by event type"
// @Param user_id query string false "Filter by user ID"
// @Param security_level query string false "Filter by security level"
// @Param page query int false "Page number (default: 0)"
// @Param limit query int false "Items per page (default: 50, max: 200)"
// @Success 200 {object} v1.PaginatedResponse
// @Failure 403 {object} v1.ErrorResponse
// @Failure 500 {object} v1.ErrorResponse
// @Router /api/v1/auth/audit/admin/history-logs [get]
func (h *AuditHandler) GetAdminHistoryLogs(c *gin.Context) {
	if !hasAdminRole(c) {
		v1.SendErrorResponse(c, http.StatusForbidden, "FORBIDDEN", "Admin role required", nil)
		return
	}

	filter := &dto.AuditAdminFilter{
		FromDate:      parseTimeQuery(c, "from_date"),
		ToDate:        parseTimeQuery(c, "to_date"),
		EventType:     c.Query("event_type"),
		ActorID:       c.Query("user_id"),
		SecurityLevel: c.Query("security_level"),
		Limit:         parseIntQuery(c, "limit", 50),
		Offset:        parseIntQuery(c, "page", 0) * parseIntQuery(c, "limit", 50),
	}

	if filter.Limit > 200 {
		filter.Limit = 200
	}

	var events []*entity.AuditLog
	var err error

	switch {
	case filter.ActorID != "":
		events, err = h.auditService.GetAuditLogs(c.Request.Context(), filter.ActorID, filter.Limit, filter.Offset)

	case filter.EventType != "":
		events, err = h.auditService.GetAuditLogsByEventType(c.Request.Context(), types.EventType(filter.EventType), filter.Limit, filter.Offset)

	case filter.SecurityLevel != "":
		events, err = h.auditService.GetCriticalAuditLogs(c.Request.Context(), filter.Limit, filter.Offset)

	case filter.FromDate != nil && filter.ToDate != nil:
		events, err = h.auditService.GetAuditLogsByTimeRange(c.Request.Context(), *filter.FromDate, *filter.ToDate, filter.Limit, filter.Offset)

	default:
		now := time.Now()
		since := now.Add(-24 * time.Hour)
		events, err = h.auditService.GetAuditLogsByTimeRange(c.Request.Context(), since, now, filter.Limit, filter.Offset)
	}

	if err != nil {
		h.logger.Error("Failed to get audit logs", zap.Error(err))
		v1.SendErrorResponse(c, http.StatusInternalServerError, "INTERNAL_ERROR", "Failed to retrieve audit logs", err)
		return
	}

	eventDTOs := make([]*dto.AuditLogDTO, 0, len(events))
	for _, event := range events {
		eventDTOs = append(eventDTOs, dto.NewAuditLogDTO(event))
	}

	v1.SendSuccessResponse(c, http.StatusOK, eventDTOs)
}

// GetCriticalSecurityEvents returns all critical security events
// @Summary Get critical security events
// @Description Get paginated list of critical security events (admin only)
// @Tags audit
// @Produce json
// @Security BearerAuth
// @Param X-User-Id header string true "User ID from Gateway"
// @Param X-User-Roles header string true "User roles from Gateway"
// @Param page query int false "Page number (default: 0)"
// @Param limit query int false "Items per page (default: 50, max: 200)"
// @Success 200 {object} v1.PaginatedResponse
// @Failure 403 {object} v1.ErrorResponse
// @Failure 500 {object} v1.ErrorResponse
// @Router /api/v1/auth/audit/admin/critical-events [get]
func (h *AuditHandler) GetCriticalSecurityEvents(c *gin.Context) {
	if !hasAdminRole(c) {
		v1.SendErrorResponse(c, http.StatusForbidden, "FORBIDDEN", "Admin role required", nil)
		return
	}

	page := parseIntQuery(c, "page", 0)
	limit := parseIntQuery(c, "limit", 50)
	if limit > 200 {
		limit = 200
	}
	offset := page * limit

	events, err := h.auditService.GetCriticalAuditLogs(c.Request.Context(), limit, offset)
	if err != nil {
		h.logger.Error("Failed to get critical audit logs", zap.Error(err))
		v1.SendErrorResponse(c, http.StatusInternalServerError, "INTERNAL_ERROR", "Failed to retrieve critical events", err)
		return
	}

	eventDTOs := make([]*dto.AuditLogDTO, 0, len(events))
	for _, event := range events {
		eventDTOs = append(eventDTOs, dto.NewAuditLogDTO(event))
	}

	v1.SendSuccessResponse(c, http.StatusOK, eventDTOs)
}

// parseIntQuery parses an integer query parameter with default value
func parseIntQuery(c *gin.Context, key string, defaultVal int) int {
	val := c.Query(key)
	if val == "" {
		return defaultVal
	}

	parsed, err := strconv.Atoi(val)
	if err != nil {
		return defaultVal
	}
	return parsed
}

// parseTimeQuery parses a time query parameter in RFC3339 format
func parseTimeQuery(c *gin.Context, key string) *time.Time {
	val := c.Query(key)
	if val == "" {
		return nil
	}

	parsed, err := time.Parse(time.RFC3339, val)
	if err != nil {
		return nil
	}
	return &parsed
}

// hasAdminRole checks if the request has admin role
func hasAdminRole(c *gin.Context) bool {
	rolesHeader := c.GetHeader("X-User-Roles")
	if rolesHeader == "" {
		return false
	}

	roles := strings.Split(rolesHeader, ",")
	for _, role := range roles {
		if strings.TrimSpace(role) == "ADMIN" {
			return true
		}
	}
	return false
}
