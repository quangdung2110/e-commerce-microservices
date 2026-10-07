// Package keybuilder centralises every Redis key Auth writes.
//
// The revocation namespace is shared with gateway-auth, which only reads it:
// docs/gateway/runtime-contract.md section 6 pins `auth:revocation:*`.
package keybuilder

import "fmt"

// Builder produces namespaced keys. Prefixes are configurable so a deployment
// can isolate environments inside one Redis logical store.
type Builder struct {
	revocationPrefix string
	statePrefix      string
}

// New returns a Builder. revocationPrefix defaults to "auth:revocation" and
// statePrefix to "auth".
func New(revocationPrefix, statePrefix string) *Builder {
	if revocationPrefix == "" {
		revocationPrefix = "auth:revocation"
	}
	if statePrefix == "" {
		statePrefix = "auth"
	}
	return &Builder{revocationPrefix: revocationPrefix, statePrefix: statePrefix}
}

// Denylist keys a single revoked access token by its `jti`.
func (b *Builder) Denylist(jti string) string {
	return fmt.Sprintf("%s:denylist:%s", b.revocationPrefix, jti)
}

// RevokedBefore keys the user-wide revocation marker read by gateway-auth,
// which rejects any JWT whose `iat` is not newer than the stored timestamp.
func (b *Builder) RevokedBefore(subject string) string {
	return fmt.Sprintf("%s:user:%s:revoked_before", b.revocationPrefix, subject)
}

// OTP keys the one-time verification state of an account for one purpose.
func (b *Builder) OTP(purpose, accountID string) string {
	return fmt.Sprintf("%s:otp:%s:%s", b.statePrefix, purpose, accountID)
}

// OTPResend counts resend requests inside the resend window.
func (b *Builder) OTPResend(purpose, accountID string) string {
	return fmt.Sprintf("%s:otp:resend:%s:%s", b.statePrefix, purpose, accountID)
}

// ResetToken maps the hash of an opaque reset token to an account.
func (b *Builder) ResetToken(tokenHash string) string {
	return fmt.Sprintf("%s:reset:%s", b.statePrefix, tokenHash)
}

// Delivery keys the notification delivery state resolved over the internal API.
func (b *Builder) Delivery(deliveryID string) string {
	return fmt.Sprintf("%s:delivery:%s", b.statePrefix, deliveryID)
}

// RateLimit keys a fixed-window counter for one scope and subject.
func (b *Builder) RateLimit(scope, subject string) string {
	return fmt.Sprintf("%s:ratelimit:%s:%s", b.statePrefix, scope, subject)
}

// WorkerLock keys the distributed lock that keeps a scheduled run singleton.
func (b *Builder) WorkerLock(name string) string {
	return fmt.Sprintf("%s:lock:%s", b.statePrefix, name)
}

// JwksChannel is the Redis Pub/Sub channel carrying the JWKS_UPDATED signal.
func (b *Builder) JwksChannel() string {
	return fmt.Sprintf("%s:jwks:update", b.statePrefix)
}
