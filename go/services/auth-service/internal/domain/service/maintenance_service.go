package service

import (
	"context"
	"time"

	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/common/clock"
	"github.com/ecommerce/auth-service/internal/domain/port"
)

// MaintenanceService holds the scheduled account operations: returning expired
// locks to ACTIVE and hard deleting accounts past their retention window.
type MaintenanceService struct {
	accounts port.AccountRepository
	tokens   port.RefreshTokenRepository
	notifier *Notifier
	clock    clock.Clock
	logger   *zap.Logger
}

// NewMaintenanceService wires the worker-facing use cases.
func NewMaintenanceService(
	accounts port.AccountRepository,
	tokens port.RefreshTokenRepository,
	notifier *Notifier,
	clk clock.Clock,
	logger *zap.Logger,
) *MaintenanceService {
	return &MaintenanceService{
		accounts: accounts,
		tokens:   tokens,
		notifier: notifier,
		clock:    clk,
		logger:   logger,
	}
}

// UnlockExpired returns accounts whose lock window elapsed to ACTIVE. The
// transition is idempotent: an account already unlocked is simply skipped.
func (s *MaintenanceService) UnlockExpired(ctx context.Context, limit int) (int, error) {
	now := s.clock.Now()
	accounts, err := s.accounts.ListExpiredLocks(ctx, now, limit)
	if err != nil {
		return 0, err
	}

	unlocked := 0
	for _, account := range accounts {
		if !account.UnlockIfExpired(now) {
			continue
		}
		if err := s.accounts.Update(ctx, account); err != nil {
			s.logger.Error("could not unlock an account",
				zap.String("account_id", account.ID.String()),
				zap.Error(err),
			)
			continue
		}
		unlocked++
		s.notifier.AccountUnlocked(ctx, account.ID, recipientOf(account), now)
	}
	return unlocked, nil
}

// HardDeleteDue removes DELETED accounts whose 90-day window elapsed. Roles and
// remaining sessions cascade with the row.
func (s *MaintenanceService) HardDeleteDue(ctx context.Context, limit int) (int64, error) {
	now := s.clock.Now()
	ids, err := s.accounts.ListDueForHardDelete(ctx, now, limit)
	if err != nil {
		return 0, err
	}
	if len(ids) == 0 {
		return 0, nil
	}
	return s.accounts.HardDelete(ctx, ids)
}

// CleanupExpiredSessions removes refresh-token rows that have been revoked or
// expired for longer than the retention window. The retention keeps a short
// audit trail available after an incident before the rows disappear.
func (s *MaintenanceService) CleanupExpiredSessions(ctx context.Context, retention time.Duration, limit int) (int64, error) {
	cutoff := s.clock.Now().Add(-retention)
	return s.tokens.DeleteExpiredBefore(ctx, cutoff, limit)
}
