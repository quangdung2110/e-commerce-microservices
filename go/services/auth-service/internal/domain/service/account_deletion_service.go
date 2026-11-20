package service

import (
	"context"
	"errors"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/common/clock"
	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/common/reqctx"
	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/event"
	"github.com/ecommerce/auth-service/internal/domain/port"
	"github.com/ecommerce/auth-service/internal/domain/vo"
)

// DeletionResult is what the client learns about an accepted deletion.
type DeletionResult struct {
	Status          string
	HardDeleteAfter time.Time
}

// AccountDeletionService implements WF-AUTH-006: anonymize immediately, revoke
// every session and let the compliance worker remove the row after 90 days.
// DELETED is terminal; there is no restore.
type AccountDeletionService struct {
	accounts port.AccountRepository
	tokens   port.RefreshTokenRepository
	hasher   port.PasswordHasher
	revoker  *Revoker
	notifier *Notifier
	tx       port.TxManager
	clock    clock.Clock
	logger   *zap.Logger
}

// NewAccountDeletionService wires the deletion use cases.
func NewAccountDeletionService(
	accounts port.AccountRepository,
	tokens port.RefreshTokenRepository,
	hasher port.PasswordHasher,
	revoker *Revoker,
	notifier *Notifier,
	tx port.TxManager,
	clk clock.Clock,
	logger *zap.Logger,
) *AccountDeletionService {
	return &AccountDeletionService{
		accounts: accounts,
		tokens:   tokens,
		hasher:   hasher,
		revoker:  revoker,
		notifier: notifier,
		tx:       tx,
		clock:    clk,
		logger:   logger,
	}
}

// DeleteOwn handles a GDPR request from the account owner, who must prove
// knowledge of the current password.
func (s *AccountDeletionService) DeleteOwn(ctx context.Context, accountID uuid.UUID, currentPassword string) (*DeletionResult, error) {
	account, err := s.accounts.GetByID(ctx, accountID)
	if err != nil {
		if errors.Is(err, apperr.ErrAccountNotFound) {
			return nil, apperr.ErrUnauthorized
		}
		return nil, err
	}
	if account.IsDeleted() {
		return nil, apperr.ErrAccountNotActive.WithDetail("The account has already been deleted.")
	}

	valid, err := s.hasher.Verify(account.PasswordHash, currentPassword)
	if err != nil {
		return nil, apperr.ErrInternal.WithCause(err)
	}
	if !valid {
		return nil, apperr.ErrInvalidCredentials
	}

	// The password was checked against this hash; if the credential moves before
	// the save, that check no longer holds.
	verifiedHash := account.PasswordHash
	return s.delete(ctx, account, vo.DeletionGDPRRequest, &verifiedHash)
}

// DeleteByAdmin handles a deletion requested by an operator with the ADMIN role.
func (s *AccountDeletionService) DeleteByAdmin(ctx context.Context, accountID uuid.UUID) (*DeletionResult, error) {
	account, err := s.accounts.GetByID(ctx, accountID)
	if err != nil {
		return nil, err
	}
	if account.IsDeleted() {
		return nil, apperr.ErrAccountNotActive.WithDetail("The account has already been deleted.")
	}
	return s.delete(ctx, account, vo.DeletionAdminDeletion, nil)
}

// delete anonymizes the identity and revokes the sessions in one transaction,
// then publishes the downstream event once the transaction committed.
//
// verifiedHash is set for an owner deletion: the row is saved only while it still
// carries the credential the password was checked against. A concurrent request
// that already deleted the account makes MarkDeleted fail, so the 90-day window
// is never restarted.
func (s *AccountDeletionService) delete(ctx context.Context, account *entity.Account, reason vo.DeletionReason, verifiedHash *string) (*DeletionResult, error) {
	now := s.clock.Now()

	var deleted *entity.Account
	err := s.tx.WithinTx(ctx, func(txCtx context.Context) error {
		if _, err := s.tokens.RevokeAllForAccount(txCtx, account.ID, now); err != nil {
			return err
		}
		saved, _, err := updateAccount(txCtx, s.accounts, account, func(a *entity.Account) (bool, error) {
			if verifiedHash != nil && a.PasswordHash != *verifiedHash {
				return false, apperr.ErrInvalidCredentials
			}
			if err := a.MarkDeleted(reason, now); err != nil {
				return false, err
			}
			return true, nil
		})
		deleted = saved
		return err
	})
	if err != nil {
		return nil, err
	}
	account = deleted

	hardDeleteAfter := now
	if account.HardDeleteAfter != nil {
		hardDeleteAfter = *account.HardDeleteAfter
	}

	// The deletion is committed and cannot be retried (the row is anonymized, so
	// a second request is a 409), which makes this event the only chance to tell
	// User, Notification, Review, Chat and Cart to purge their PII. It therefore
	// goes out before the Redis marker is attempted (ADR-006).
	s.notifier.Publish(ctx, event.NewUserDeleted(
		account.ID, now, hardDeleteAfter, reason.String(), reqctx.EventMetadata(ctx)))

	// Redis cannot join the transaction, so the marker is written after the
	// commit. A failure here leaves access tokens valid until they expire, which
	// is why it is reported rather than swallowed.
	if err := s.revoker.MarkAllTokensRevoked(ctx, account.ID); err != nil {
		s.logger.Error("could not write the revocation marker of a deleted account",
			zap.String("account_id", account.ID.String()),
			zap.Error(err),
		)
		return nil, err
	}

	return &DeletionResult{
		Status:          vo.AccountDeleted.String(),
		HardDeleteAfter: hardDeleteAfter,
	}, nil
}
