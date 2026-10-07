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
	if account.UnlockIfExpired(now) {
		// The lock simply expired. The auto-unlock worker owns the USER_UNLOCKED
		// notification, so this inline transition stays silent.
		if err := s.accounts.Update(ctx, account); err != nil {
			return nil, err
		}
	}
	if err := account.EnsureCanAuthenticate(now); err != nil {
		return nil, err
	}

	valid, err := s.hasher.Verify(account.PasswordHash, cmd.Password)
	if err != nil {
		return nil, apperr.ErrInternal.WithCause(err)
	}
	if !valid {
		return nil, s.registerFailure(ctx, account, now)
	}

	// Only write when a previous failure is still recorded, so a normal login
	// costs one read instead of a read and a write.
	if account.FailedLoginCount != 0 || account.LastFailedLoginAt != nil {
		account.ResetFailedLogins(now)
		if err := s.accounts.Update(ctx, account); err != nil {
			return nil, err
		}
	}

	return s.openSession(ctx, account, entity.SessionContext{
		DeviceName: cmd.DeviceName,
		UserAgent:  cmd.UserAgent,
		IPAddress:  cmd.IPAddress,
	})
}

// registerFailure applies AUTH-BR-004 and returns the error the client sees.
func (s *AuthenticationService) registerFailure(ctx context.Context, account *entity.Account, now time.Time) error {
	locked := account.RegisterFailedLogin(now, s.lockout)
	if err := s.accounts.Update(ctx, account); err != nil {
		return err
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
