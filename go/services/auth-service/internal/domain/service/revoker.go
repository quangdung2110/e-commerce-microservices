package service

import (
	"context"
	"time"

	"github.com/google/uuid"

	"github.com/ecommerce/auth-service/internal/common/clock"
	"github.com/ecommerce/auth-service/internal/domain/port"
)

// Revoker applies the dual-token revocation model of ADR-001.
//
// Revoking a refresh token stops new access tokens from being issued; the Redis
// markers stop the access tokens that are already in flight. gateway-auth reads
// both signals, so a Redis failure must fail the operation rather than report a
// revocation that never happened.
type Revoker struct {
	tokens     port.RefreshTokenRepository
	revocation port.RevocationStore
	clock      clock.Clock
	// markerTTL must not be shorter than the maximum access-token lifetime, or a
	// token issued just before the revocation could outlive its own marker.
	markerTTL time.Duration
}

// NewRevoker wires the revocation helper. accessTTL is the access-token lifetime
// and clockSkew the tolerance granted to verifiers.
func NewRevoker(tokens port.RefreshTokenRepository, revocation port.RevocationStore, clk clock.Clock, accessTTL, clockSkew time.Duration) *Revoker {
	return &Revoker{
		tokens:     tokens,
		revocation: revocation,
		clock:      clk,
		markerTTL:  accessTTL + clockSkew,
	}
}

// MarkerTTL exposes the lifetime applied to revocation markers.
func (r *Revoker) MarkerTTL() time.Duration { return r.markerTTL }

// RevokeAllSessions revokes every refresh token of an account and blocks every
// access token issued up to now. It is used by logout-all, password change,
// password reset, email change and account deletion (AUTH-BR-003).
func (r *Revoker) RevokeAllSessions(ctx context.Context, accountID uuid.UUID) (int64, error) {
	now := r.clock.Now()
	revoked, err := r.tokens.RevokeAllForAccount(ctx, accountID, now)
	if err != nil {
		return 0, err
	}
	if err := r.revocation.RevokeIssuedBefore(ctx, accountID, now, r.markerTTL); err != nil {
		return revoked, err
	}
	return revoked, nil
}

// MarkAllTokensRevoked writes only the user-wide marker. It is used when the
// refresh tokens were already revoked inside a database transaction, because
// Redis cannot take part in that transaction.
func (r *Revoker) MarkAllTokensRevoked(ctx context.Context, accountID uuid.UUID) error {
	return r.revocation.RevokeIssuedBefore(ctx, accountID, r.clock.Now(), r.markerTTL)
}

// RevokeSession revokes one session family. It is idempotent: revoking a family
// that is already revoked succeeds without disclosing anything.
func (r *Revoker) RevokeSession(ctx context.Context, accountID, familyID uuid.UUID) (int64, error) {
	return r.tokens.RevokeFamily(ctx, accountID, familyID, r.clock.Now())
}

// DenyAccessToken blocks a single access token by its jti.
//
// The gateway forwards only the verified jti, not the expiry, so the TTL is the
// maximum access-token lifetime. That is an upper bound on the remaining
// lifetime: the entry may outlive the token by the time already elapsed, which
// costs a little memory but never allows a revoked token through.
func (r *Revoker) DenyAccessToken(ctx context.Context, jti string) error {
	if jti == "" {
		return nil
	}
	return r.revocation.DenyAccessToken(ctx, jti, r.markerTTL)
}
