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

	return s.delete(ctx, account, vo.DeletionGDPRRequest)
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
	return s.delete(ctx, account, vo.DeletionAdminDeletion)
}

// delete anonymizes the identity and revokes the sessions in one transaction,
// then publishes the downstream event once the transaction committed.
func (s *AccountDeletionService) delete(ctx context.Context, account *entity.Account, reason vo.DeletionReason) (*DeletionResult, error) {
	now := s.clock.Now()

	err := s.tx.WithinTx(ctx, func(txCtx context.Context) error {
		if _, err := s.tokens.RevokeAllForAccount(txCtx, account.ID, now); err != nil {
			return err
		}
		if err := account.MarkDeleted(reason, now); err != nil {
			return err
		}
		return s.accounts.Update(txCtx, account)
	})
	if err != nil {
		return nil, err
	}

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

	hardDeleteAfter := now
	if account.HardDeleteAfter != nil {
		hardDeleteAfter = *account.HardDeleteAfter
	}
	s.notifier.Publish(ctx, event.NewUserDeleted(
		account.ID, now, hardDeleteAfter, reason.String(), reqctx.EventMetadata(ctx)))

	return &DeletionResult{
		Status:          vo.AccountDeleted.String(),
		HardDeleteAfter: hardDeleteAfter,
	}, nil
}
