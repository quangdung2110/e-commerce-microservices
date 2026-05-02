package dto

import (
	"encoding/json"
	"time"

	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/entity"
)

// ========================= AUDIT DTOs =========================

// AuditLogDTO represents an audit log entry for API response
type AuditLogDTO struct {
	ID            string                 `json:"id"`
	ActorID       string                 `json:"actor_id"`
	ActorType     string                 `json:"actor_type"`
	EventType     string                 `json:"event_type"`
	OccurredAt    string                 `json:"occurred_at"`
	IPAddress     string                 `json:"ip_address,omitempty"`
	DeviceID      string                 `json:"device_id,omitempty"`
	DeviceInfo    string                 `json:"device_info,omitempty"`
	SecurityLevel string                 `json:"security_level"`
	Payload       map[string]interface{} `json:"payload,omitempty"`
	LogCategory   string                 `json:"log_category"`
	RequestID     string                 `json:"request_id,omitempty"`
	ServiceName   string                 `json:"service_name"`
}

// NewAuditLogDTO creates an AuditLogDTO from an AuditLog entity
func NewAuditLogDTO(log *entity.AuditLog) *AuditLogDTO {
	dto := &AuditLogDTO{
		ID:            log.ID.String(),
		ActorID:       log.ActorID,
		ActorType:     string(log.ActorType),
		EventType:     string(log.EventType),
		OccurredAt:    log.OccurredAt.Format(time.RFC3339),
		IPAddress:     log.IPAddress,
		DeviceID:      log.DeviceID,
		DeviceInfo:    log.DeviceInfo,
		SecurityLevel: string(log.SecurityLevel),
		LogCategory:   string(log.LogCategory),
		RequestID:     log.RequestID,
		ServiceName:   log.ServiceName,
	}

	if log.Payload != nil && len(log.Payload) > 0 {
		_ = json.Unmarshal(log.Payload, &dto.Payload)
	}

	return dto
}

// AuditAdminFilter represents filter options for admin audit queries
type AuditAdminFilter struct {
	FromDate      *time.Time `json:"from_date,omitempty"`
	ToDate        *time.Time `json:"to_date,omitempty"`
	EventType     string     `json:"event_type,omitempty"`
	ActorID       string     `json:"actor_id,omitempty"`
	SecurityLevel string     `json:"security_level,omitempty"`
	Limit         int        `json:"limit"`
	Offset        int        `json:"offset"`
}

// AuditLogListResponse represents paginated audit log response
type AuditLogListResponse struct {
	Data       []*AuditLogDTO `json:"data"`
	Total      int64          `json:"total"`
	Page       int            `json:"page"`
	Limit      int            `json:"limit"`
	TotalPages int            `json:"total_pages"`
}
