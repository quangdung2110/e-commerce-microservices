// Package domain holds the verification rules of gateway-auth.
package domain

import "errors"

// Outcome classes of a verification. The HTTP layer maps each to exactly one
// status; runtime-contract section 6 answers 401 only for bad credentials and
// 503 for a mandatory dependency that cannot be reached.
var (
	// ErrMissingCredentials: no usable Bearer token in the request.
	ErrMissingCredentials = errors.New("missing bearer credentials")
	// ErrInvalidToken: signature, algorithm, issuer, audience, time window or
	// claim set does not satisfy the contract.
	ErrInvalidToken = errors.New("invalid token")
	// ErrRevokedToken: the token is denylisted or issued before a user-wide
	// revocation marker.
	ErrRevokedToken = errors.New("token revoked")
	// ErrDependencyUnavailable: JWKS or Redis could not be consulted. Never
	// treated as success.
	ErrDependencyUnavailable = errors.New("validation dependency unavailable")
	// ErrUnknownKey: the JWKS was read successfully and does not contain the kid.
	ErrUnknownKey = errors.New("unknown signing key")
)
