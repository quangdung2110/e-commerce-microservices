package service

import (
	"context"
	"encoding/json"

	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/common/constants"
	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/port"
)

type AuditService struct {
	auditRepo port.AuditLogRepository
	logger    *zap.Logger
}

func NewAuditService(auditRepo port.AuditLogRepository, logger *zap.Logger) *AuditService {
	return &AuditService{
		auditRepo: auditRepo,
		logger:    logger.With(zap.String("service", "AuditService")),
	}
}

// LogLoginSuccess logs a successful login event
func (s *AuditService) LogLoginSuccess(ctx context.Context, userID, email, ipAddress, deviceID, deviceInfo string) error {
	if s.auditRepo == nil {
		return nil
	}
	auditLog := entity.NewAuditLog(
		userID,
		entity.ActorUser,
		"LOGIN_SUCCESS",
		entity.SecurityLevelInfo,
		entity.LogCategoryUser,
	).WithIP(ipAddress).WithDevice(deviceID, deviceInfo)

	payload := map[string]string{
		"email": email,
	}
	auditLog.WithPayload(payload)

	return s.auditRepo.Create(ctx, auditLog)
}

// LogLoginFailed logs a failed login event
func (s *AuditService) LogLoginFailed(ctx context.Context, email, ipAddress, deviceID, deviceInfo, reason string) error {
	if s.auditRepo == nil {
		return nil
	}
	auditLog := entity.NewAuditLog(
		"",
		entity.ActorGuest,
		"LOGIN_FAILED",
		entity.SecurityLevelWarning,
		entity.LogCategoryUser,
	).WithIP(ipAddress).WithDevice(deviceID, deviceInfo)

	payload := map[string]string{
		"email":  email,
		"reason": reason,
	}
	auditLog.WithPayload(payload)

	return s.auditRepo.Create(ctx, auditLog)
}

// LogLogout logs a logout event
func (s *AuditService) LogLogout(ctx context.Context, userID, ipAddress string) error {
	if s.auditRepo == nil {
		return nil
	}
	auditLog := entity.NewAuditLog(
		userID,
		entity.ActorUser,
		"LOGOUT",
		entity.SecurityLevelInfo,
		entity.LogCategoryUser,
	).WithIP(ipAddress)

	return s.auditRepo.Create(ctx, auditLog)
}

// LogForceLogout logs a force logout event (e.g., admin action or password change)
func (s *AuditService) LogForceLogout(ctx context.Context, userID, reason, ipAddress string) error {
	if s.auditRepo == nil {
		return nil
	}
	auditLog := entity.NewAuditLog(
		userID,
		entity.ActorUser,
		"FORCE_LOGOUT",
		entity.SecurityLevelWarning,
		entity.LogCategoryUser,
	).WithIP(ipAddress)

	payload := map[string]string{
		"reason": reason,
	}
	auditLog.WithPayload(payload)

	return s.auditRepo.Create(ctx, auditLog)
}

// LogAccountLocked logs an account lock event
func (s *AuditService) LogAccountLocked(ctx context.Context, userID, email, reason string) error {
	if s.auditRepo == nil {
		return nil
	}
	auditLog := entity.NewAuditLog(
		userID,
		entity.ActorSystem,
		"ACCOUNT_LOCKED",
		entity.SecurityLevelCritical,
		entity.LogCategoryUser,
	)

	payload := map[string]string{
		"email":  email,
		"reason": reason,
	}
	auditLog.WithPayload(payload)

	return s.auditRepo.Create(ctx, auditLog)
}

// LogAccountUnlocked logs an account unlock event
func (s *AuditService) LogAccountUnlocked(ctx context.Context, userID, email string) error {
	if s.auditRepo == nil {
		return nil
	}
	auditLog := entity.NewAuditLog(
		userID,
		entity.ActorSystem,
		"ACCOUNT_UNLOCKED",
		entity.SecurityLevelInfo,
		entity.LogCategoryUser,
	)

	payload := map[string]string{
		"email": email,
	}
	auditLog.WithPayload(payload)

	return s.auditRepo.Create(ctx, auditLog)
}

// LogTokenRefresh logs a token refresh event
func (s *AuditService) LogTokenRefresh(ctx context.Context, userID, ipAddress string) error {
	if s.auditRepo == nil {
		return nil
	}
	auditLog := entity.NewAuditLog(
		userID,
		entity.ActorUser,
		"TOKEN_REFRESH",
		entity.SecurityLevelInfo,
		entity.LogCategoryUser,
	).WithIP(ipAddress)

	return s.auditRepo.Create(ctx, auditLog)
}

// LogBruteForceAttempt logs a potential brute-force attack detection
func (s *AuditService) LogBruteForceAttempt(ctx context.Context, email, ipAddress string, attemptCount int) error {
	if s.auditRepo == nil {
		return nil
	}
	auditLog := entity.NewAuditLog(
		"",
		entity.ActorGuest,
		"BRUTE_FORCE_DETECTED",
		entity.SecurityLevelCritical,
		entity.LogCategorySystem,
	).WithIP(ipAddress)

	payload := map[string]interface{}{
		"email":        email,
		"attempt_count": attemptCount,
	}
	auditLog.WithPayload(payload)

	return s.auditRepo.Create(ctx, auditLog)
}

// GetLoginHistory retrieves login history for a user
func (s *AuditService) GetLoginHistory(ctx context.Context, userID string, limit int) ([]*entity.AuditLog, error) {
	if s.auditRepo == nil {
		return []*entity.AuditLog{}, nil
	}
	if limit <= 0 {
		limit = constants.MaxConcurrentSessions
	}

	logs, err := s.auditRepo.GetLoginHistory(ctx, userID, limit)
	if err != nil {
		return nil, err
	}

	return logs, nil
}

// ParseAuditLogPayload parses the JSON payload of an audit log
func ParseAuditLogPayload(log *entity.AuditLog) (map[string]interface{}, error) {
	if log.Payload == nil {
		return nil, nil
	}

	var payload map[string]interface{}
	if err := json.Unmarshal(log.Payload, &payload); err != nil {
		return nil, err
	}

	return payload, nil
}
