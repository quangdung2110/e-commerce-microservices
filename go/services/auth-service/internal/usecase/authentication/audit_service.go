package authentication

import (
	"context"
	"encoding/json"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/entity"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/repository"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/types"
)

// AuditService handles audit logging operations
type AuditService struct {
	repo   repository.AuditRepository
	logger *zap.Logger
}

// AuditEvent represents an event to be logged
type AuditEvent struct {
	ActorID       string
	ActorType    types.ActorType
	EventType    types.EventType
	IPAddress    string
	DeviceID     string
	DeviceInfo   string
	SecurityLevel types.SecurityLevel
	LogCategory  types.LogCategory
	Payload      map[string]any
	RequestID    string
}

// NewAuditService creates a new audit service
func NewAuditService(
	repo repository.AuditRepository,
	logger *zap.Logger,
) *AuditService {
	return &AuditService{
		repo:   repo,
		logger: logger,
	}
}

// Log writes an audit log entry
func (s *AuditService) Log(ctx context.Context, event *AuditEvent) error {
	if s.repo == nil {
		s.logger.Debug("Audit repository not configured, skipping audit log")
		return nil
	}

	// Build payload JSON
	var payload json.RawMessage
	if event.Payload != nil {
		data, err := json.Marshal(event.Payload)
		if err != nil {
			s.logger.Warn("Failed to marshal audit payload", zap.Error(err))
		} else {
			payload = data
		}
	}

	// Build actor type
	actorType := event.ActorType
	if actorType == "" {
		actorType = types.ActorTypeSystem
	}

	// Build security level
	securityLevel := event.SecurityLevel
	if securityLevel == "" {
		securityLevel = types.SecurityLevelInfo
	}

	// Build log category
	logCategory := event.LogCategory
	if logCategory == "" {
		logCategory = types.LogCategorySystem
	}

	// Create audit log entry
	auditLog := &entity.AuditLog{
		ID:            uuid.New(),
		ActorID:       event.ActorID,
		ActorType:     actorType,
		EventType:     event.EventType,
		OccurredAt:    time.Now().UTC(),
		IPAddress:     event.IPAddress,
		DeviceID:      event.DeviceID,
		DeviceInfo:    event.DeviceInfo,
		SecurityLevel: securityLevel,
		Payload:      payload,
		LogCategory:   logCategory,
		RequestID:    event.RequestID,
		ServiceName:  "auth-service",
		CreatedAt:    time.Now().UTC(),
	}

	// Write to database
	if err := s.repo.Create(ctx, auditLog); err != nil {
		s.logger.Error("Failed to write audit log",
			zap.String("actor_id", event.ActorID),
			zap.String("event_type", string(event.EventType)),
			zap.Error(err))
		return err
	}

	// Log at appropriate level based on security level
	switch securityLevel {
	case types.SecurityLevelCritical:
		s.logger.Warn("AUDIT: CRITICAL",
			zap.String("actor_id", event.ActorID),
			zap.String("event_type", string(event.EventType)),
			zap.String("ip_address", event.IPAddress))
	case types.SecurityLevelWarning:
		s.logger.Warn("AUDIT: WARNING",
			zap.String("actor_id", event.ActorID),
			zap.String("event_type", string(event.EventType)))
	default:
		s.logger.Debug("AUDIT",
			zap.String("actor_id", event.ActorID),
			zap.String("event_type", string(event.EventType)))
	}

	return nil
}

// LogAsync writes an audit log entry asynchronously (fire-and-forget)
func (s *AuditService) LogAsync(ctx context.Context, event *AuditEvent) {
	go func() {
		// Create a new context with timeout for async operation
		asyncCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()

		if err := s.Log(asyncCtx, event); err != nil {
			s.logger.Error("Async audit log failed", zap.Error(err))
		}
	}()
}

// GetAuditLogs retrieves audit logs with pagination
func (s *AuditService) GetAuditLogs(ctx context.Context, actorID string, limit, offset int) ([]*entity.AuditLog, error) {
	if s.repo == nil {
		return nil, nil
	}
	return s.repo.GetByActorID(ctx, actorID, limit, offset)
}

// GetAuditLogsByEventType retrieves audit logs by event type
func (s *AuditService) GetAuditLogsByEventType(ctx context.Context, eventType types.EventType, limit, offset int) ([]*entity.AuditLog, error) {
	if s.repo == nil {
		return nil, nil
	}
	return s.repo.GetByEventType(ctx, eventType, limit, offset)
}

// GetAuditLogsByTimeRange retrieves audit logs within a time range
func (s *AuditService) GetAuditLogsByTimeRange(ctx context.Context, start, end time.Time, limit, offset int) ([]*entity.AuditLog, error) {
	if s.repo == nil {
		return nil, nil
	}
	return s.repo.GetByTimeRange(ctx, start, end, limit, offset)
}

// GetCriticalAuditLogs retrieves all critical security events
func (s *AuditService) GetCriticalAuditLogs(ctx context.Context, limit, offset int) ([]*entity.AuditLog, error) {
	if s.repo == nil {
		return nil, nil
	}
	return s.repo.GetBySecurityLevel(ctx, types.SecurityLevelCritical, limit, offset)
}

// CleanupOldLogs deletes audit logs older than the specified time
func (s *AuditService) CleanupOldLogs(ctx context.Context, olderThan time.Time) (int64, error) {
	if s.repo == nil {
		return 0, nil
	}
	return s.repo.DeleteOldLogs(ctx, olderThan)
}
