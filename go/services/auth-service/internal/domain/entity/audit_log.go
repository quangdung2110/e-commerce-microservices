package entity

import (
	"encoding/json"
	"time"
)

type ActorType string

const (
	ActorUser   ActorType = "USER"
	ActorAdmin  ActorType = "ADMIN"
	ActorSystem ActorType = "SYSTEM"
	ActorGuest  ActorType = "GUEST"
)

type SecurityLevel string

const (
	SecurityLevelInfo     SecurityLevel = "INFO"
	SecurityLevelWarning  SecurityLevel = "WARNING"
	SecurityLevelCritical SecurityLevel = "CRITICAL"
)

type LogCategory string

const (
	LogCategoryUser     LogCategory = "USER"
	LogCategorySystem   LogCategory = "SYSTEM"
	LogCategorySecurity LogCategory = "SECURITY"
)

type AuditLog struct {
	ID            string          `json:"id"`
	ActorID       string          `json:"actor_id"`
	ActorType     ActorType       `json:"actor_type"`
	EventType     string          `json:"event_type"`
	OccurredAt    time.Time       `json:"occurred_at"`
	IPAddress     string          `json:"ip_address,omitempty"`
	DeviceID      string          `json:"device_id,omitempty"`
	DeviceInfo    string          `json:"device_info,omitempty"`
	SecurityLevel SecurityLevel   `json:"security_level"`
	Payload       json.RawMessage `json:"payload,omitempty"`
	LogCategory   LogCategory     `json:"log_category"`
}

func NewAuditLog(
	actorID string,
	actorType ActorType,
	eventType string,
	securityLevel SecurityLevel,
	logCategory LogCategory,
) *AuditLog {
	return &AuditLog{
		ID:            generateUUID(),
		ActorID:       actorID,
		ActorType:     actorType,
		EventType:     eventType,
		OccurredAt:    time.Now().UTC(),
		SecurityLevel: securityLevel,
		LogCategory:   logCategory,
	}
}

func (a *AuditLog) WithIP(ip string) *AuditLog {
	a.IPAddress = ip
	return a
}

func (a *AuditLog) WithDevice(deviceID, deviceInfo string) *AuditLog {
	a.DeviceID = deviceID
	a.DeviceInfo = deviceInfo
	return a
}

func (a *AuditLog) WithPayload(payload interface{}) *AuditLog {
	if data, err := json.Marshal(payload); err == nil {
		a.Payload = data
	}
	return a
}

func generateUUID() string {
	return time.Now().Format("20060102150405") + "-" + randomString(8)
}

func randomString(n int) string {
	const letters = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
	b := make([]byte, n)
	for i := range b {
		b[i] = letters[time.Now().UnixNano()%int64(len(letters))]
	}
	return string(b)
}
