// Package port declares the outbound interfaces of the domain. Adapters in
// internal/infrastructure implement them; the domain never imports an adapter.
package port

import (
	"context"
	"encoding/base64"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"

	"github.com/ecommerce/auth-service/internal/domain/entity"
)

// AccountRepository owns the durable identity state. Implementations translate
// storage failures into apperr codes: a missing account is ErrAccountNotFound and
// a unique-identity violation is ErrEmailAlreadyRegistered.
type AccountRepository interface {
	// Create inserts the account together with its roles in one transaction.
	Create(ctx context.Context, account *entity.Account) error
	GetByID(ctx context.Context, id uuid.UUID) (*entity.Account, error)
	// GetByNormalizedEmail looks the identity up by lower(btrim(email)).
	GetByNormalizedEmail(ctx context.Context, normalizedEmail string) (*entity.Account, error)
	// Update persists the mutable columns of an existing account.
	Update(ctx context.Context, account *entity.Account) error
	// ExistsByNormalizedEmail answers the uniqueness pre-check of a registration
	// or an email change.
	ExistsByNormalizedEmail(ctx context.Context, normalizedEmail string) (bool, error)
	// ListExpiredLocks returns LOCKED accounts whose locked_until has passed.
	ListExpiredLocks(ctx context.Context, now time.Time, limit int) ([]*entity.Account, error)
	// ListDueForHardDelete returns DELETED accounts past their retention window.
	ListDueForHardDelete(ctx context.Context, now time.Time, limit int) ([]uuid.UUID, error)
	// HardDelete removes the rows permanently; roles and sessions cascade.
	HardDelete(ctx context.Context, ids []uuid.UUID) (int64, error)
}

// SessionCursor is the opaque pagination cursor of the session listing. It is
// keyed on (expires_at, family_id) so the ordering stays stable across pages.
type SessionCursor struct {
	ExpiresAt time.Time
	FamilyID  uuid.UUID
}

// Encode renders the cursor for the `next_cursor` response field.
func (c SessionCursor) Encode() string {
	raw := fmt.Sprintf("%s|%s", c.ExpiresAt.UTC().Format(time.RFC3339Nano), c.FamilyID)
	return base64.RawURLEncoding.EncodeToString([]byte(raw))
}

// DecodeSessionCursor parses a client-supplied cursor. An unparsable cursor is
// rejected rather than silently treated as "from the start".
func DecodeSessionCursor(encoded string) (SessionCursor, error) {
	decoded, err := base64.RawURLEncoding.DecodeString(encoded)
	if err != nil {
		return SessionCursor{}, fmt.Errorf("port: cursor is not valid base64url")
	}
	parts := strings.SplitN(string(decoded), "|", 2)
	if len(parts) != 2 {
		return SessionCursor{}, fmt.Errorf("port: cursor is malformed")
	}
	expiresAt, err := time.Parse(time.RFC3339Nano, parts[0])
	if err != nil {
		return SessionCursor{}, fmt.Errorf("port: cursor timestamp is malformed")
	}
	familyID, err := uuid.Parse(parts[1])
	if err != nil {
		return SessionCursor{}, fmt.Errorf("port: cursor session id is malformed")
	}
	return SessionCursor{ExpiresAt: expiresAt.UTC(), FamilyID: familyID}, nil
}

// RefreshTokenRepository owns the session families. PostgreSQL is the source of
// truth for refresh tokens; Redis never is (ADR-004).
type RefreshTokenRepository interface {
	Create(ctx context.Context, token *entity.RefreshToken) error
	// GetByHash returns the token with that digest, or ErrRefreshTokenInvalid.
	GetByHash(ctx context.Context, tokenHash []byte) (*entity.RefreshToken, error)
	// Rotate consumes currentHash and installs a successor in the same family as
	// one atomic step: the old row is revoked and linked to the new one, whose
	// sliding expiry is extended but never beyond the absolute expiry of the
	// session. Exactly one concurrent caller may succeed; every other caller
	// receives ErrRefreshTokenInvalid (ADR-002, AUTH-BR-002). There is no grace
	// period and no cached replay response.
	Rotate(ctx context.Context, currentHash, nextHash []byte, sliding time.Duration, sessionCtx entity.SessionContext, now time.Time) (*entity.RefreshToken, error)
	// RevokeFamily revokes every active token of one session.
	RevokeFamily(ctx context.Context, accountID, familyID uuid.UUID, now time.Time) (int64, error)
	// RevokeAllForAccount revokes every active session of an account.
	RevokeAllForAccount(ctx context.Context, accountID uuid.UUID, now time.Time) (int64, error)
	// FamilyExists reports whether the family belongs to the account, including
	// families that are already revoked, so revocation stays idempotent.
	FamilyExists(ctx context.Context, accountID, familyID uuid.UUID) (bool, error)
	// ListSessions returns the active sessions of an account, newest expiry
	// first, plus the cursor of the next page when one exists.
	ListSessions(ctx context.Context, accountID uuid.UUID, cursor *SessionCursor, limit int) ([]entity.Session, *SessionCursor, error)
	// DeleteExpiredBefore removes revoked or expired rows past the retention
	// window and reports how many were deleted.
	DeleteExpiredBefore(ctx context.Context, cutoff time.Time, limit int) (int64, error)
}

// JwtKeyRepository owns the signing-key lifecycle.
type JwtKeyRepository interface {
	Create(ctx context.Context, key *entity.JwtKey) error
	// GetActive returns the single ACTIVE key, or ErrDependencyUnavailable when
	// no key can sign, because issuing tokens must fail closed.
	GetActive(ctx context.Context) (*entity.JwtKey, error)
	// ListPublishable returns the ACTIVE and PHASE_OUT keys for JWKS.
	ListPublishable(ctx context.Context) ([]*entity.JwtKey, error)
	// Rotate phases out the current ACTIVE key and installs next atomically, so
	// there is never more or less than one ACTIVE key.
	Rotate(ctx context.Context, next *entity.JwtKey, now time.Time) error
	// ListRetirable returns PHASE_OUT keys whose overlap window has elapsed.
	ListRetirable(ctx context.Context, phasedOutBefore time.Time) ([]*entity.JwtKey, error)
	Retire(ctx context.Context, kid uuid.UUID, now time.Time) error
}

// TxManager runs a unit of work inside one PostgreSQL transaction. The
// transaction travels in the context, so repositories join it transparently.
type TxManager interface {
	WithinTx(ctx context.Context, fn func(ctx context.Context) error) error
}
