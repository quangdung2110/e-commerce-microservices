package entity

import (
	"encoding/json"
	"time"

	"github.com/google/uuid"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/types"
)

// AuditLog represents a security audit log entry
type AuditLog struct {
	ID           uuid.UUID       `json:"id"`
	ActorID      string         `json:"actor_id"`
	ActorType    types.ActorType `json:"actor_type"`
	EventType    types.EventType `json:"event_type"`
	OccurredAt   time.Time      `json:"occurred_at"`
	IPAddress    string         `json:"ip_address,omitempty"`
	DeviceID     string         `json:"device_id,omitempty"`
	DeviceInfo   string         `json:"device_info,omitempty"`
	SecurityLevel types.SecurityLevel `json:"security_level"`
	Payload      json.RawMessage `json:"payload,omitempty"`
	LogCategory  types.LogCategory `json:"log_category"`
	RequestID    string         `json:"request_id,omitempty"`
	ServiceName  string         `json:"service_name"`
	CreatedAt    time.Time      `json:"created_at"`
}

// NewAuditLog creates a new audit log entry
func NewAuditLog(
	actorID string,
	actorType types.ActorType,
	eventType types.EventType,
	securityLevel types.SecurityLevel,
	logCategory types.LogCategory,
	opts ...AuditLogOption,
) *AuditLog {
	log := &AuditLog{
		ID:            uuid.New(),
		ActorID:       actorID,
		ActorType:     actorType,
		EventType:     eventType,
		OccurredAt:    time.Now().UTC(),
		SecurityLevel: securityLevel,
		LogCategory:   logCategory,
		ServiceName:   "auth-service",
		CreatedAt:     time.Now().UTC(),
	}

	for _, opt := range opts {
		opt(log)
	}

	return log
}

// AuditLogOption is a functional option for AuditLog
type AuditLogOption func(*AuditLog)

// WithIPAddress sets the IP address
func WithIPAddress(ip string) AuditLogOption {
	return func(log *AuditLog) {
		log.IPAddress = ip
	}
}

// WithDeviceID sets the device ID
func WithDeviceID(deviceID string) AuditLogOption {
	return func(log *AuditLog) {
		log.DeviceID = deviceID
	}
}

// WithDeviceInfo sets the device info
func WithDeviceInfo(info string) AuditLogOption {
	return func(log *AuditLog) {
		log.DeviceInfo = info
	}
}

// WithPayload sets the payload
func WithPayload(payload map[string]interface{}) AuditLogOption {
	return func(log *AuditLog) {
		if payload != nil {
			data, _ := json.Marshal(payload)
			log.Payload = data
		}
	}
}

// WithRequestID sets the request ID
func WithRequestID(requestID string) AuditLogOption {
	return func(log *AuditLog) {
		log.RequestID = requestID
	}
}

// GetPayload parses and returns the payload as a map
func (a *AuditLog) GetPayload() (map[string]interface{}, error) {
	if a.Payload == nil || len(a.Payload) == 0 {
		return nil, nil
	}
	var result map[string]interface{}
	err := json.Unmarshal(a.Payload, &result)
	return result, err
}

// IsSecurityCritical returns true if the log level is CRITICAL
func (a *AuditLog) IsSecurityCritical() bool {
	return a.SecurityLevel == types.SecurityLevelCritical
}

// IsSecurityWarning returns true if the log level is WARNING
func (a *AuditLog) IsSecurityWarning() bool {
	return a.SecurityLevel == types.SecurityLevelWarning
}
