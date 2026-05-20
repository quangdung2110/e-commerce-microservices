package response

import (
	"encoding/json"
	"time"
)

type SessionResponse struct {
	ID         string    `json:"id"`
	DeviceID   string    `json:"device_id"`
	DeviceInfo string    `json:"device_info"`
	IPAddress  string    `json:"ip_address,omitempty"`
	CreatedAt  time.Time `json:"created_at"`
	ExpiresAt  time.Time `json:"expires_at"`
	IsCurrent  bool      `json:"is_current"`
}

type SessionsResponse struct {
	Sessions []*SessionResponse `json:"sessions"`
	Total    int                `json:"total"`
}

type AuditLogItem struct {
	ID            string                 `json:"id"`
	ActorID       string                 `json:"actor_id"`
	ActorType     string                 `json:"actor_type"`
	EventType     string                 `json:"event_type"`
	OccurredAt    time.Time              `json:"occurred_at"`
	IPAddress     string                 `json:"ip_address,omitempty"`
	DeviceInfo    string                 `json:"device_info,omitempty"`
	SecurityLevel string                 `json:"security_level"`
	Payload       map[string]interface{} `json:"payload,omitempty"`
}

type AuditLogsResponse struct {
	Logs   []*AuditLogItem `json:"logs"`
	Total  int             `json:"total"`
	Limit  int             `json:"limit"`
	Offset int             `json:"offset"`
}

type LoginHistoryItem struct {
	ID         string    `json:"id"`
	EventType  string    `json:"event_type"`
	IPAddress  string    `json:"ip_address"`
	DeviceInfo string    `json:"device_info"`
	OccurredAt time.Time `json:"occurred_at"`
}

type LoginHistoryResponse struct {
	History []*LoginHistoryItem `json:"history"`
	Total   int                 `json:"total"`
	Limit   int                 `json:"limit"`
	Offset  int                 `json:"offset"`
}

func ParsePayload(payload json.RawMessage) map[string]interface{} {
	if payload == nil {
		return nil
	}
	var result map[string]interface{}
	if err := json.Unmarshal(payload, &result); err != nil {
		return nil
	}
	return result
}
