package port

import (
	"context"
	"time"

	"github.com/google/uuid"

	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/event"
)

// RevocationStore writes the revocation signals that gateway-auth reads. Auth
// has write access to the namespace; the gateway only reads it.
type RevocationStore interface {
	// DenyAccessToken blocks a single access token by its jti for the remaining
	// lifetime of that token.
	DenyAccessToken(ctx context.Context, jti string, ttl time.Duration) error
	// RevokeIssuedBefore stores the user-wide marker; the gateway rejects any
	// JWT whose iat is not newer than issuedBefore.
	RevokeIssuedBefore(ctx context.Context, accountID uuid.UUID, issuedBefore time.Time, ttl time.Duration) error
}

// RateLimitRule is one fixed window.
type RateLimitRule struct {
	Limit  int
	Window time.Duration
}

// Enabled reports whether the rule should be applied at all.
func (r RateLimitRule) Enabled() bool { return r.Limit > 0 && r.Window > 0 }

// RateLimitResult reports the outcome of one rate-limit check.
type RateLimitResult struct {
	Allowed    bool
	Remaining  int
	RetryAfter time.Duration
}

// RateLimiter enforces the anti-abuse limits. It fails closed: a store failure
// must not silently allow the request.
type RateLimiter interface {
	Allow(ctx context.Context, scope, subject string, rule RateLimitRule) (RateLimitResult, error)
}

// OtpChallenge is the TTL-bound verification state of one account and purpose.
type OtpChallenge struct {
	AccountID uuid.UUID
	Purpose   string
	// CodeHash is the hex-encoded SHA-256 of the code; the code is never stored.
	CodeHash string
	// Subject binds the challenge to what it verifies, such as the new email of
	// an email change. Empty when the purpose needs no binding.
	Subject     string
	ExpiresAt   time.Time
	MaxAttempts int
}

// OtpStore holds one-time verification codes. Issue replaces any pending code
// for the same account and purpose so only the newest code is valid.
type OtpStore interface {
	Issue(ctx context.Context, challenge OtpChallenge, ttl time.Duration) error
	// Consume verifies and burns the code atomically. It returns ErrOtpInvalid
	// for a wrong, expired, exhausted or already used code, so a replay can never
	// succeed (AUTH-BR-005).
	Consume(ctx context.Context, purpose string, accountID uuid.UUID, codeHash, expectedSubject string) error
	// RegisterResend counts a resend inside the window and reports the new count.
	RegisterResend(ctx context.Context, purpose string, accountID uuid.UUID, window time.Duration) (int, error)
}

// ResetTokenStore maps the hash of an opaque reset token to an account.
type ResetTokenStore interface {
	Issue(ctx context.Context, tokenHash string, accountID uuid.UUID, ttl time.Duration) error
	// Consume burns the token atomically and returns its account, or
	// ErrResetTokenInvalid.
	Consume(ctx context.Context, tokenHash string) (uuid.UUID, error)
	// InvalidateForAccount drops any pending reset token of an account after the
	// password changed by another path.
	InvalidateForAccount(ctx context.Context, accountID uuid.UUID) error
}

// DeliveryStore holds the notification render state resolved over the internal
// mTLS API.
type DeliveryStore interface {
	Put(ctx context.Context, delivery *entity.NotificationDelivery, ttl time.Duration) error
	// Get returns ErrDeliveryNotFound when the id is unknown and
	// ErrDeliveryExpired when the state outlived its TTL.
	Get(ctx context.Context, deliveryID uuid.UUID) (*entity.NotificationDelivery, error)
}

// EventPublisher publishes a CloudEvent after the database transaction has
// committed. Delivery is best effort with bounded retry (ADR-005).
type EventPublisher interface {
	Publish(ctx context.Context, envelope *event.Envelope) error
}

// JwksNotifier signals gateway caches that the key set changed. It is a cache
// optimization, never a durable event.
type JwksNotifier interface {
	NotifyKeysChanged(ctx context.Context) error
}

// Lock is a held distributed lock.
type Lock interface {
	Release(ctx context.Context) error
}

// Locker keeps a scheduled run singleton across replicas. Acquire returns a nil
// lock when another replica holds it.
type Locker interface {
	Acquire(ctx context.Context, name string, ttl time.Duration) (Lock, error)
}

// PasswordHasher hides the Argon2id parameters from the domain.
type PasswordHasher interface {
	Hash(password string) (string, error)
	// Verify compares a candidate against a stored PHC string. It returns false
	// for a mismatch and an error only for an unusable hash.
	Verify(encodedHash, password string) (bool, error)
}

// JWK is one published public key.
type JWK struct {
	Kty string `json:"kty"`
	Kid string `json:"kid"`
	Use string `json:"use"`
	Alg string `json:"alg"`
	N   string `json:"n"`
	E   string `json:"e"`
}

// Signer signs access tokens with the ACTIVE key and exposes the publishable
// public keys. Claims are built by the domain; the adapter only does crypto.
type Signer interface {
	// Sign returns the compact JWS and the kid of the key that signed it.
	Sign(ctx context.Context, claims map[string]any) (token string, kid string, err error)
	// PublicKeys returns the ACTIVE and PHASE_OUT keys as JWKs.
	PublicKeys(ctx context.Context) ([]JWK, error)
	// InvalidateCache drops cached key material after a rotation.
	InvalidateCache()
}

// KeyMaterial is a freshly generated RSA key pair, already encrypted.
type KeyMaterial struct {
	PublicKeyPEM         string
	PrivateKeyCiphertext []byte
	PrivateKeyNonce      []byte
	EncryptionKeyVersion int16
}

// KeyGenerator creates and encrypts new signing material. The plaintext private
// key never leaves the adapter.
type KeyGenerator interface {
	Generate() (KeyMaterial, error)
}
