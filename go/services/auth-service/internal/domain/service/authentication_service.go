package service

import (
	"context"
	"errors"
	"sync"
	"time"

	"github.com/google/uuid"
	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/common/clock"
	"github.com/ecommerce/auth-service/internal/common/constants"
	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/common/secret"
	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/port"
	"github.com/ecommerce/auth-service/internal/domain/vo"
)

// SessionPolicy is the configured lifetime of a session family. Auth applies no
// session or device quota (AUTH-BR-009).
type SessionPolicy struct {
	SlidingTTL  time.Duration
	AbsoluteTTL time.Duration
}

// LoginCommand is the validated input of a login request.
type LoginCommand struct {
	Email      string
	Password   string
	DeviceName string
	UserAgent  string
	IPAddress  string
}

// LoginResult carries the freshly issued credentials. The refresh token is
// returned in plaintext exactly once, for the Set-Cookie header.
type LoginResult struct {
	Access           AccessToken
	SessionID        uuid.UUID
	RefreshToken     string
	RefreshExpiresAt time.Time
}

// AuthenticationService implements WF-AUTH-002: verify credentials, apply the
// lockout policy and open a session.
type AuthenticationService struct {
	accounts port.AccountRepository
	tokens   port.RefreshTokenRepository
	hasher   port.PasswordHasher
	limiter  port.RateLimiter
	issuer   *TokenService
	notifier *Notifier
	clock    clock.Clock
	lockout  entity.LockoutPolicy
	session  SessionPolicy
	// accountRule throttles login attempts per identity, complementing the
	// per-IP limit applied by the middleware.
	accountRule port.RateLimitRule
	logger      *zap.Logger

	dummyHashOnce sync.Once
	dummyHash     string
}

// NewAuthenticationService wires the login use case.
func NewAuthenticationService(
	accounts port.AccountRepository,
	tokens port.RefreshTokenRepository,
	hasher port.PasswordHasher,
	limiter port.RateLimiter,
	issuer *TokenService,
	notifier *Notifier,
	clk clock.Clock,
	lockout entity.LockoutPolicy,
	session SessionPolicy,
	accountRule port.RateLimitRule,
	logger *zap.Logger,
) *AuthenticationService {
	return &AuthenticationService{
		accounts:    accounts,
		tokens:      tokens,
		hasher:      hasher,
		limiter:     limiter,
		issuer:      issuer,
		notifier:    notifier,
		clock:       clk,
		lockout:     lockout,
		session:     session,
		accountRule: accountRule,
		logger:      logger,
	}
}

// Login verifies the credentials and opens a session for an ACTIVE account.
func (s *AuthenticationService) Login(ctx context.Context, cmd LoginCommand) (*LoginResult, error) {
	email, err := vo.NewEmail(cmd.Email)
	if err != nil {
		return nil, err
	}
	if cmd.Password == "" {
		return nil, apperr.ErrValidation.WithFields(apperr.FieldError{
			Field:   "password",
			Message: "must not be empty",
		})
	}

	if s.accountRule.Enabled() {
		result, err := s.limiter.Allow(ctx, "login:account", email.Normalized(), s.accountRule)
		if err != nil {
			return nil, err
		}
		if !result.Allowed {
			return nil, apperr.ErrRateLimited.WithRetryAfter(result.RetryAfter)
		}
	}

	account, err := s.accounts.GetByNormalizedEmail(ctx, email.Normalized())
	if err != nil {
		if errors.Is(err, apperr.ErrAccountNotFound) {
			// Spend the same work as a real verification so the response time
			// does not reveal whether the address exists.
			s.equalizeTiming(cmd.Password)
			return nil, apperr.ErrInvalidCredentials
		}
		return nil, err
	}

	now := s.clock.Now()

	// The password is checked before the account state is looked at. A pending or
	// locked account is reported (403, 423) only to a caller who proves the
	// credential; otherwise those answers would tell anybody which addresses are
	// registered and in what state.
	valid, err := s.hasher.Verify(account.PasswordHash, cmd.Password)
	if err != nil {
		return nil, apperr.ErrInternal.WithCause(err)
	}
	if !valid {
		return nil, s.registerFailure(ctx, account, now)
	}

	account, err = s.confirmLogin(ctx, account, now)
	if err != nil {
		return nil, err
	}

	return s.openSession(ctx, account, entity.SessionContext{
		DeviceName: cmd.DeviceName,
		UserAgent:  cmd.UserAgent,
		IPAddress:  cmd.IPAddress,
	})
}

// confirmLogin runs after a correct password. It lifts a lock that has run out,
// refuses a pending or still-locked account and clears a recorded failure streak.
// The save is a compare-and-set on the row the password was verified against: if
// the credential changed while the (slow) verification ran, the password the
// caller used is no longer valid.
func (s *AuthenticationService) confirmLogin(ctx context.Context, account *entity.Account, now time.Time) (*entity.Account, error) {
	verifiedHash := account.PasswordHash
	var unlocked bool

	account, saved, err := updateAccount(ctx, s.accounts, account, func(a *entity.Account) (bool, error) {
		unlocked = false
		if a.PasswordHash != verifiedHash {
			return false, apperr.ErrInvalidCredentials
		}
		unlocked = a.UnlockIfExpired(now)
		if err := a.EnsureCanAuthenticate(now); err != nil {
			return false, err
		}
		// Only write when something changed, so a normal login costs one read
		// instead of a read and a write.
		changed := unlocked
		if a.FailedLoginCount != 0 || a.LastFailedLoginAt != nil {
			a.ResetFailedLogins(now)
			changed = true
		}
		return changed, nil
	})
	if err != nil {
		return nil, err
	}
	if saved && unlocked {
		s.notifier.AccountUnlocked(ctx, account.ID, recipientOf(account), now)
	}
	return account, nil
}

// registerFailure applies AUTH-BR-004 and returns the error the client sees. A
// wrong password against a pending, still-locked or deleted account is a plain
// 401 and is not counted: nothing about the state is disclosed to a caller who
// did not prove the credential.
func (s *AuthenticationService) registerFailure(ctx context.Context, account *entity.Account, now time.Time) error {
	var locked, unlocked bool

	account, saved, err := updateAccount(ctx, s.accounts, account, func(a *entity.Account) (bool, error) {
		locked = false
		// A lock that ran out no longer protects the account, so the attempt is
		// counted as it would be on an active one.
		unlocked = a.UnlockIfExpired(now)
		if a.Status != vo.AccountActive {
			return unlocked, nil
		}
		locked = a.RegisterFailedLogin(now, s.lockout)
		return true, nil
	})
	if err != nil {
		return err
	}
	if saved && unlocked {
		s.notifier.AccountUnlocked(ctx, account.ID, recipientOf(account), now)
	}
	if !locked {
		return apperr.ErrInvalidCredentials
	}

	lockedUntil := now.Add(s.lockout.LockDuration)
	if account.LockedUntil != nil {
		lockedUntil = *account.LockedUntil
	}
	s.notifier.AccountLocked(ctx, account.ID, recipientOf(account), now, lockedUntil)
	return apperr.ErrAccountLocked.WithRetryAfter(lockedUntil.Sub(now))
}

// openSession creates a new session family and signs the first access token.
func (s *AuthenticationService) openSession(ctx context.Context, account *entity.Account, sessionCtx entity.SessionContext) (*LoginResult, error) {
	plaintext, err := secret.NewOpaqueToken(constants.RefreshTokenBytes)
	if err != nil {
		return nil, apperr.ErrInternal.WithCause(err)
	}

	now := s.clock.Now()
	session := entity.NewSession(account.ID, secret.Hash(plaintext),
		s.session.SlidingTTL, s.session.AbsoluteTTL, sessionCtx, now)

	if err := s.tokens.Create(ctx, session); err != nil {
		return nil, err
	}

	access, err := s.issuer.Issue(ctx, account, session.FamilyID)
	if err != nil {
		// The session is unusable without an access token; revoke it rather than
		// leaving a credential the client never received.
		if _, revokeErr := s.tokens.RevokeFamily(ctx, account.ID, session.FamilyID, now); revokeErr != nil {
			s.logger.Error("could not revoke the session of a failed login",
				zap.String("account_id", account.ID.String()),
				zap.String("session_id", session.FamilyID.String()),
				zap.Error(revokeErr),
			)
		}
		return nil, err
	}

	return &LoginResult{
		Access:           access,
		SessionID:        session.FamilyID,
		RefreshToken:     plaintext,
		RefreshExpiresAt: session.ExpiresAt,
	}, nil
}

// equalizeTiming runs one password verification against a throwaway hash so an
// unknown address costs the same as a known one.
func (s *AuthenticationService) equalizeTiming(password string) {
	s.dummyHashOnce.Do(func() {
		hash, err := s.hasher.Hash("timing-equalization-placeholder")
		if err != nil {
			s.logger.Warn("could not build the timing-equalization hash", zap.Error(err))
			return
		}
		s.dummyHash = hash
	})
	if s.dummyHash == "" {
		return
	}
	if _, err := s.hasher.Verify(s.dummyHash, password); err != nil {
		s.logger.Debug("timing-equalization verification failed", zap.Error(err))
	}
}
