package service

import (
	"context"
	"errors"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/common/clock"
	"github.com/ecommerce/auth-service/internal/common/constants"
	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/common/secret"
	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/port"
)

// DefaultSessionPageSize matches the `limit` default of the public contract.
const DefaultSessionPageSize = 50

// MaxSessionPageSize matches the `limit` maximum of the public contract.
const MaxSessionPageSize = 100

// SessionService implements WF-AUTH-003 and WF-AUTH-004: refresh-token rotation
// plus session listing and revocation.
type SessionService struct {
	accounts port.AccountRepository
	tokens   port.RefreshTokenRepository
	issuer   *TokenService
	revoker  *Revoker
	notifier *Notifier
	clock    clock.Clock
	policy   SessionPolicy
	logger   *zap.Logger
}

// NewSessionService wires the session use cases.
func NewSessionService(
	accounts port.AccountRepository,
	tokens port.RefreshTokenRepository,
	issuer *TokenService,
	revoker *Revoker,
	notifier *Notifier,
	clk clock.Clock,
	policy SessionPolicy,
	logger *zap.Logger,
) *SessionService {
	return &SessionService{
		accounts: accounts,
		tokens:   tokens,
		issuer:   issuer,
		revoker:  revoker,
		notifier: notifier,
		clock:    clk,
		policy:   policy,
		logger:   logger,
	}
}

// RefreshCommand is the input of a rotation request.
type RefreshCommand struct {
	RefreshToken string
	UserAgent    string
	IPAddress    string
}

// Refresh rotates the presented refresh token and issues a new token pair.
//
// A replayed, revoked or expired token only produces an error: it never issues a
// second pair and never revokes the family that legitimately replaced it.
func (s *SessionService) Refresh(ctx context.Context, cmd RefreshCommand) (*LoginResult, error) {
	if cmd.RefreshToken == "" {
		return nil, apperr.ErrRefreshTokenInvalid
	}

	plaintext, err := secret.NewOpaqueToken(constants.RefreshTokenBytes)
	if err != nil {
		return nil, apperr.ErrInternal.WithCause(err)
	}

	now := s.clock.Now()
	rotated, err := s.tokens.Rotate(ctx,
		secret.Hash(cmd.RefreshToken),
		secret.Hash(plaintext),
		s.policy.SlidingTTL,
		entity.SessionContext{UserAgent: cmd.UserAgent, IPAddress: cmd.IPAddress},
		now,
	)
	if err != nil {
		return nil, err
	}

	account, err := s.accounts.GetByID(ctx, rotated.AccountID)
	if err != nil {
		if errors.Is(err, apperr.ErrAccountNotFound) {
			return nil, apperr.ErrRefreshTokenInvalid
		}
		return nil, err
	}
	// A lock that ran out is lifted here as it is at login; whoever performs the
	// LOCKED to ACTIVE write also announces it, so the notice goes out once.
	var unlocked bool
	account, saved, err := updateAccount(ctx, s.accounts, account, func(a *entity.Account) (bool, error) {
		unlocked = a.UnlockIfExpired(now)
		return unlocked, nil
	})
	if err != nil {
		return nil, err
	}
	if saved && unlocked {
		s.notifier.AccountUnlocked(ctx, account.ID, recipientOf(account), now)
	}
	if !account.IsActive() {
		// AUTH-BR-001: only an ACTIVE account may hold credentials. The token
		// just created is withdrawn so the caller keeps nothing usable.
		s.revokeAfterFailure(ctx, account.ID, rotated.FamilyID)
		return nil, apperr.ErrRefreshTokenInvalid
	}

	access, err := s.issuer.Issue(ctx, account, rotated.FamilyID)
	if err != nil {
		s.revokeAfterFailure(ctx, account.ID, rotated.FamilyID)
		return nil, err
	}

	return &LoginResult{
		Access:           access,
		SessionID:        rotated.FamilyID,
		RefreshToken:     plaintext,
		RefreshExpiresAt: rotated.ExpiresAt,
	}, nil
}

// ListSessions returns the active sessions of the caller, page by page.
func (s *SessionService) ListSessions(ctx context.Context, accountID uuid.UUID, rawCursor string, limit int) ([]entity.Session, string, error) {
	if limit <= 0 {
		limit = DefaultSessionPageSize
	}
	if limit > MaxSessionPageSize {
		limit = MaxSessionPageSize
	}

	var cursor *port.SessionCursor
	if rawCursor != "" {
		decoded, err := port.DecodeSessionCursor(rawCursor)
		if err != nil {
			return nil, "", apperr.ErrValidation.WithFields(apperr.FieldError{
				Field:   "cursor",
				Message: "is not a cursor returned by this API",
			})
		}
		cursor = &decoded
	}

	sessions, next, err := s.tokens.ListSessions(ctx, accountID, cursor, limit)
	if err != nil {
		return nil, "", err
	}
	if next == nil {
		return sessions, "", nil
	}
	return sessions, next.Encode(), nil
}

// RevokeSessionCommand is the input of a single-session revocation.
type RevokeSessionCommand struct {
	AccountID uuid.UUID
	SessionID uuid.UUID
	// TokenJti is the verified jti the gateway injected for the caller.
	TokenJti string
	// RefreshToken is the cookie the browser sent with the request, if any. It
	// identifies whether the revoked session is the caller's current one.
	RefreshToken string
	// CurrentSessionID is the session id the gateway forwarded from the access
	// token, when it forwards one.
	CurrentSessionID *uuid.UUID
}

// RevokeSessionResult tells the adapter whether the caller just revoked the
// session it is using, in which case the refresh cookie must be cleared.
type RevokeSessionResult struct {
	CurrentSessionRevoked bool
}

// RevokeSession revokes one session family of the caller. It is idempotent: a
// family that is already revoked still returns success.
func (s *SessionService) RevokeSession(ctx context.Context, cmd RevokeSessionCommand) (RevokeSessionResult, error) {
	exists, err := s.tokens.FamilyExists(ctx, cmd.AccountID, cmd.SessionID)
	if err != nil {
		return RevokeSessionResult{}, err
	}
	if !exists {
		// A family of another account is reported as not found so the endpoint
		// cannot be used to probe other accounts.
		return RevokeSessionResult{}, apperr.ErrSessionNotFound
	}

	isCurrent := s.isCurrentSession(ctx, cmd)

	if _, err := s.revoker.RevokeSession(ctx, cmd.AccountID, cmd.SessionID); err != nil {
		return RevokeSessionResult{}, err
	}

	if isCurrent && cmd.TokenJti != "" {
		// The access token of this device stays valid until it expires unless it
		// is denylisted (ADR-001).
		if err := s.revoker.DenyAccessToken(ctx, cmd.TokenJti); err != nil {
			return RevokeSessionResult{}, err
		}
	}
	return RevokeSessionResult{CurrentSessionRevoked: isCurrent}, nil
}

// RevokeAllSessions revokes every session of the caller and blocks every access
// token issued so far.
func (s *SessionService) RevokeAllSessions(ctx context.Context, accountID uuid.UUID) error {
	_, err := s.revoker.RevokeAllSessions(ctx, accountID)
	return err
}

func (s *SessionService) isCurrentSession(ctx context.Context, cmd RevokeSessionCommand) bool {
	if cmd.CurrentSessionID != nil {
		return *cmd.CurrentSessionID == cmd.SessionID
	}
	if cmd.RefreshToken == "" {
		return false
	}
	current, err := s.tokens.GetByHash(ctx, secret.Hash(cmd.RefreshToken))
	if err != nil {
		if !errors.Is(err, apperr.ErrRefreshTokenInvalid) {
			s.logger.Warn("could not resolve the current session from the refresh cookie",
				zap.String("account_id", cmd.AccountID.String()),
				zap.Error(err),
			)
		}
		return false
	}
	return current.AccountID == cmd.AccountID && current.FamilyID == cmd.SessionID
}

func (s *SessionService) revokeAfterFailure(ctx context.Context, accountID, familyID uuid.UUID) {
	if _, err := s.tokens.RevokeFamily(ctx, accountID, familyID, s.clock.Now()); err != nil {
		s.logger.Error("could not revoke the session of a failed refresh",
			zap.String("account_id", accountID.String()),
			zap.String("session_id", familyID.String()),
			zap.Error(err),
		)
	}
}
