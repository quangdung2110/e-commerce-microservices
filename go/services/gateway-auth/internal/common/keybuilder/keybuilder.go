// Package keybuilder produces the revocation keys auth-service writes. The
// strings must match auth-service internal/common/keybuilder byte for byte
// (docs/gateway/runtime-contract.md section 6).
package keybuilder

import "fmt"

type Builder struct {
	prefix string
}

// New uses prefix, normally "auth:revocation".
func New(prefix string) Builder { return Builder{prefix: prefix} }

// Denylist keys one revoked access token by jti.
func (b Builder) Denylist(jti string) string {
	return fmt.Sprintf("%s:denylist:%s", b.prefix, jti)
}

// RevokedBefore keys the user-wide revocation marker (Unix seconds, UTC).
func (b Builder) RevokedBefore(subject string) string {
	return fmt.Sprintf("%s:user:%s:revoked_before", b.prefix, subject)
}
