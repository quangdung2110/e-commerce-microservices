package worker

import (
	"context"
	"fmt"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/entity"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain/types"
)

// AuditLogEvent represents an audit log event
type AuditLogEvent struct {
	ActorID       string
	ActorType    types.ActorType
	EventType    types.EventType
	IPAddress    string
	SecurityLevel types.SecurityLevel
	LogCategory  types.LogCategory
	Payload      map[string]any
}

// AuditLogger interface for logging audit events
type AuditLogger interface {
	Log(ctx context.Context, event *AuditLogEvent) error
}

// AutoUnlockWorker handles automatic unlocking of user accounts after lock duration expires
// This worker runs periodically to unlock accounts that were locked due to failed login attempts
type AutoUnlockWorker struct {
	userRepo     UserRepoInterface
	auditLogger  AuditLogger
	logger       *zap.Logger
	lockDuration time.Duration
	batchSize    int
}

// UserRepoInterface interface for user operations (lock/unlock)
type UserRepoInterface interface {
	LockAccount(ctx context.Context, userID uuid.UUID, lockedAt time.Time) error
	UnlockAccount(ctx context.Context, userID uuid.UUID) error
	GetLockedUsers(ctx context.Context, unlockAfter time.Time, limit int) ([]*entity.User, error)
	GetByID(ctx context.Context, userID uuid.UUID) (*entity.User, error)
}

// NewAutoUnlockWorker creates a new auto-unlock worker
func NewAutoUnlockWorker(
	userRepo UserRepoInterface,
	auditLogger AuditLogger,
	logger *zap.Logger,
	lockDuration time.Duration,
	batchSize int,
) *AutoUnlockWorker {
	if batchSize <= 0 {
		batchSize = 100
	}
	if lockDuration <= 0 {
		lockDuration = 30 * time.Minute
	}

	return &AutoUnlockWorker{
		userRepo:     userRepo,
		auditLogger:  auditLogger,
		logger:       logger,
		lockDuration: lockDuration,
		batchSize:    batchSize,
	}
}

// Run executes the auto-unlock job
func (w *AutoUnlockWorker) Run(ctx context.Context) error {
	w.logger.Info("Starting auto-unlock worker job")

	// Calculate the threshold time - accounts locked before this time should be unlocked
	unlockAfter := time.Now().Add(-w.lockDuration)

	// Get all locked users that are eligible for auto-unlock
	lockedUsers, err := w.userRepo.GetLockedUsers(ctx, unlockAfter, w.batchSize)
	if err != nil {
		return fmt.Errorf("failed to get locked users: %w", err)
	}

	if len(lockedUsers) == 0 {
		w.logger.Debug("No locked users found for auto-unlock")
		return nil
	}

	w.logger.Info("Found locked users for auto-unlock",
		zap.Int("count", len(lockedUsers)))

	var unlockedCount int
	var failedCount int

	for _, user := range lockedUsers {
		if err := w.unlockUser(ctx, user); err != nil {
			w.logger.Error("Failed to unlock user",
				zap.String("user_id", user.ID.String()),
				zap.Error(err))
			failedCount++
			continue
		}
		unlockedCount++
	}

	w.logger.Info("Auto-unlock job completed",
		zap.Int("unlocked", unlockedCount),
		zap.Int("failed", failedCount))

	return nil
}

// unlockUser unlocks a single user account
func (w *AutoUnlockWorker) unlockUser(ctx context.Context, user *entity.User) error {
	w.logger.Info("Auto-unlocking user account",
		zap.String("user_id", user.ID.String()),
		zap.String("email", user.Email))

	lockedAt := time.Now()
	if user.LockedAt != nil {
		lockedAt = *user.LockedAt
	}

	// Step 1: Unlock the account in database
	if err := w.userRepo.UnlockAccount(ctx, user.ID); err != nil {
		return fmt.Errorf("failed to unlock account: %w", err)
	}

	// Step 2: Log to audit
	if w.auditLogger != nil {
		auditEvent := &AuditLogEvent{
			ActorID:       "SYSTEM",
			ActorType:    types.ActorTypeSystem,
			EventType:    types.EventTypeUserUnlocked,
			IPAddress:    "",
			SecurityLevel: types.SecurityLevelInfo,
			LogCategory:  types.LogCategorySystem,
			Payload: map[string]any{
				"target_user_id":    user.ID.String(),
				"email":             user.Email,
				"unlock_type":       "AUTO",
				"unlocked_by":       "SYSTEM",
				"action":            "USER_AUTO_UNLOCKED",
				"previous_locked_at": lockedAt,
			},
		}
		if err := w.auditLogger.Log(ctx, auditEvent); err != nil {
			w.logger.Error("Failed to log audit event", zap.Error(err))
		}
	}

	w.logger.Info("USER_UNLOCKED event should be published",
		zap.String("user_id", user.ID.String()),
		zap.String("email", user.Email),
		zap.String("event_type", string(types.EventTypeUserUnlocked)))

	w.logger.Info("User account auto-unlocked successfully",
		zap.String("user_id", user.ID.String()))

	return nil
}
