package service

import (
	"context"
	"time"

	"github.com/google/uuid"

	"github.com/ecommerce/auth-service/internal/common/clock"
	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/port"
	"github.com/ecommerce/auth-service/internal/domain/vo"
)

// AccessToken is a freshly signed JWT plus the metadata the caller needs.
type AccessToken struct {
	Token     string
	JTI       string
	KeyID     string
	IssuedAt  time.Time
	ExpiresAt time.Time
}

// ExpiresIn renders the whole seconds returned as `expires_in`.
func (t AccessToken) ExpiresIn() int {
	seconds := int(t.ExpiresAt.Sub(t.IssuedAt).Seconds())
	if seconds < 1 {
		return 1
	}
	return seconds
}

// TokenConfig is the claim contract shared with gateway-auth. Issuer, audience
// and the allowed algorithm must match the verifier exactly.
type TokenConfig struct {
	Issuer    string
	Audience  string
	AccessTTL time.Duration
	ClockSkew time.Duration
}

// TokenService issues access tokens. Claim construction stays in the domain; the
// signer adapter only performs the cryptography and key lookup.
type TokenService struct {
	signer port.Signer
	clock  clock.Clock
	cfg    TokenConfig
}

// NewTokenService wires the access-token issuer.
func NewTokenService(signer port.Signer, clk clock.Clock, cfg TokenConfig) *TokenService {
	return &TokenService{signer: signer, clock: clk, cfg: cfg}
}

// AccessTTL exposes the configured lifetime so revocation markers can be given a
// TTL that outlives every token they must block.
func (s *TokenService) AccessTTL() time.Duration { return s.cfg.AccessTTL }

// Issue signs an access token for one session of an account. Only an ACTIVE
// account may receive one (AUTH-INV-001).
func (s *TokenService) Issue(ctx context.Context, account *entity.Account, sessionID uuid.UUID) (AccessToken, error) {
	if !account.IsActive() {
		return AccessToken{}, apperr.ErrAccountNotActive
	}

	now := s.clock.Now()
	expiresAt := now.Add(s.cfg.AccessTTL)
	jti := uuid.NewString()

	claims := map[string]any{
		"iss":   s.cfg.Issuer,
		"aud":   s.cfg.Audience,
		"sub":   account.ID.String(),
		"jti":   jti,
		"sid":   sessionID.String(),
		"roles": vo.RoleStrings(account.Roles),
		"iat":   now.Unix(),
		"nbf":   now.Add(-s.cfg.ClockSkew).Unix(),
		"exp":   expiresAt.Unix(),
	}

	token, kid, err := s.signer.Sign(ctx, claims)
	if err != nil {
		return AccessToken{}, err
	}
	return AccessToken{
		Token:     token,
		JTI:       jti,
		KeyID:     kid,
		IssuedAt:  now,
		ExpiresAt: expiresAt,
	}, nil
}

// JwksService serves the public key set consumed by gateway-auth.
type JwksService struct {
	signer port.Signer
}

// NewJwksService wires the JWKS reader.
func NewJwksService(signer port.Signer) *JwksService {
	return &JwksService{signer: signer}
}

// Keys returns the ACTIVE and PHASE_OUT public keys. A private key is never part
// of this document (AUTH-BR-006).
func (s *JwksService) Keys(ctx context.Context) ([]port.JWK, error) {
	return s.signer.PublicKeys(ctx)
}
