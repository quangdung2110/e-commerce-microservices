// Package port declares what the verifier needs from the outside world.
package port

import (
	"context"
	"crypto"
	"time"
)

// KeySource resolves the public key that verifies a JWT signed with kid.
//
// It returns domain.ErrUnknownKey when a successful JWKS read proves the kid is
// not published, and domain.ErrDependencyUnavailable when no usable key material
// can be established — the two must stay distinct because the first is a bad
// credential (401) and the second must fail closed (503).
type KeySource interface {
	PublicKey(ctx context.Context, kid string) (crypto.PublicKey, error)
}

// RevocationStore reads the revocation state auth-service writes.
//
// IsRevoked reports whether the token's jti is denylisted or the subject has a
// user-wide revocation marker not older than issuedAt. Any failure to read the
// state is returned as an error wrapping domain.ErrDependencyUnavailable.
type RevocationStore interface {
	IsRevoked(ctx context.Context, subject, jti string, issuedAt time.Time) (bool, error)
}
