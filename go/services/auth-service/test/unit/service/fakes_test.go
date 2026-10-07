// Package service_test exercises the Auth use cases against in-memory doubles.
//
// The doubles are deliberately strict where a rule depends on them: the refresh
// repository enforces single consumption, and the OTP store enforces
// single use and the attempt budget, because those are the invariants under test.
package service_test

import (
	"context"
	"sync"
	"time"

	"github.com/google/uuid"

	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/event"
	"github.com/ecommerce/auth-service/internal/domain/port"
)

// ---------------------------------------------------------------- accounts --

type fakeAccountRepo struct {
	mu       sync.Mutex
	accounts map[uuid.UUID]*entity.Account
	failWith error
}

func newAccountRepo() *fakeAccountRepo {
	return &fakeAccountRepo{accounts: map[uuid.UUID]*entity.Account{}}
}

func (r *fakeAccountRepo) put(account *entity.Account) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.accounts[account.ID] = account
}

func (r *fakeAccountRepo) Create(_ context.Context, account *entity.Account) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.failWith != nil {
		return r.failWith
	}
	for _, existing := range r.accounts {
		if !existing.Email.IsZero() && existing.Email.Normalized() == account.Email.Normalized() {
			return apperr.ErrEmailAlreadyRegistered
		}
	}
	r.accounts[account.ID] = account
	return nil
}

func (r *fakeAccountRepo) GetByID(_ context.Context, id uuid.UUID) (*entity.Account, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	account, ok := r.accounts[id]
	if !ok {
		return nil, apperr.ErrAccountNotFound
	}
	return account, nil
}

func (r *fakeAccountRepo) GetByNormalizedEmail(_ context.Context, normalized string) (*entity.Account, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	for _, account := range r.accounts {
		if !account.Email.IsZero() && account.Email.Normalized() == normalized {
			return account, nil
		}
	}
	return nil, apperr.ErrAccountNotFound
}

func (r *fakeAccountRepo) ExistsByNormalizedEmail(ctx context.Context, normalized string) (bool, error) {
	if _, err := r.GetByNormalizedEmail(ctx, normalized); err != nil {
		return false, nil
	}
	return true, nil
}

func (r *fakeAccountRepo) Update(_ context.Context, account *entity.Account) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, ok := r.accounts[account.ID]; !ok {
		return apperr.ErrAccountNotFound
	}
	r.accounts[account.ID] = account
	return nil
}

func (r *fakeAccountRepo) ListExpiredLocks(_ context.Context, now time.Time, limit int) ([]*entity.Account, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	var locked []*entity.Account
	for _, account := range r.accounts {
		if account.LockedUntil != nil && !account.LockedUntil.After(now) && len(locked) < limit {
			locked = append(locked, account)
		}
	}
	return locked, nil
}

func (r *fakeAccountRepo) ListDueForHardDelete(_ context.Context, now time.Time, limit int) ([]uuid.UUID, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	var due []uuid.UUID
	for id, account := range r.accounts {
		if account.HardDeleteAfter != nil && !account.HardDeleteAfter.After(now) && len(due) < limit {
			due = append(due, id)
		}
	}
	return due, nil
}

func (r *fakeAccountRepo) HardDelete(_ context.Context, ids []uuid.UUID) (int64, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	var deleted int64
	for _, id := range ids {
		if _, ok := r.accounts[id]; ok {
			delete(r.accounts, id)
			deleted++
		}
	}
	return deleted, nil
}

// ----------------------------------------------------------- refresh tokens --

type fakeRefreshRepo struct {
	mu     sync.Mutex
	byHash map[string]*entity.RefreshToken
}

func newRefreshRepo() *fakeRefreshRepo {
	return &fakeRefreshRepo{byHash: map[string]*entity.RefreshToken{}}
}

func (r *fakeRefreshRepo) Create(_ context.Context, token *entity.RefreshToken) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.byHash[string(token.TokenHash)] = token
	return nil
}

func (r *fakeRefreshRepo) GetByHash(_ context.Context, hash []byte) (*entity.RefreshToken, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	token, ok := r.byHash[string(hash)]
	if !ok {
		return nil, apperr.ErrRefreshTokenInvalid
	}
	return token, nil
}

// Rotate mirrors the single-statement guarantee of the PostgreSQL adapter: the
// current token is only consumable once, and a replay produces no new token.
func (r *fakeRefreshRepo) Rotate(_ context.Context, currentHash, nextHash []byte, sliding time.Duration, sessionCtx entity.SessionContext, now time.Time) (*entity.RefreshToken, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	current, ok := r.byHash[string(currentHash)]
	if !ok || !current.IsUsable(now) {
		return nil, apperr.ErrRefreshTokenInvalid
	}

	revokedAt := now
	current.RevokedAt = &revokedAt
	current.LastUsedAt = &revokedAt

	next := current.Rotate(nextHash, sliding, sessionCtx, now)
	current.ReplacedBy = &next.ID
	r.byHash[string(nextHash)] = next
	return next, nil
}

func (r *fakeRefreshRepo) RevokeFamily(_ context.Context, accountID, familyID uuid.UUID, now time.Time) (int64, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	var revoked int64
	for _, token := range r.byHash {
		if token.AccountID == accountID && token.FamilyID == familyID && token.RevokedAt == nil {
			revokedAt := now
			token.RevokedAt = &revokedAt
			revoked++
		}
	}
	return revoked, nil
}

func (r *fakeRefreshRepo) RevokeAllForAccount(_ context.Context, accountID uuid.UUID, now time.Time) (int64, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	var revoked int64
	for _, token := range r.byHash {
		if token.AccountID == accountID && token.RevokedAt == nil {
			revokedAt := now
			token.RevokedAt = &revokedAt
			revoked++
		}
	}
	return revoked, nil
}

func (r *fakeRefreshRepo) FamilyExists(_ context.Context, accountID, familyID uuid.UUID) (bool, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	for _, token := range r.byHash {
		if token.AccountID == accountID && token.FamilyID == familyID {
			return true, nil
		}
	}
	return false, nil
}

func (r *fakeRefreshRepo) ListSessions(_ context.Context, accountID uuid.UUID, _ *port.SessionCursor, limit int) ([]entity.Session, *port.SessionCursor, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	seen := map[uuid.UUID]bool{}
	sessions := make([]entity.Session, 0)
	for _, token := range r.byHash {
		if token.AccountID != accountID || token.RevokedAt != nil || seen[token.FamilyID] {
			continue
		}
		seen[token.FamilyID] = true
		sessions = append(sessions, entity.Session{
			ID:         token.FamilyID,
			DeviceName: token.DeviceName,
			IPAddress:  token.IPAddress,
			CreatedAt:  token.CreatedAt,
			LastUsedAt: token.CreatedAt,
			ExpiresAt:  token.ExpiresAt,
		})
		if len(sessions) == limit {
			break
		}
	}
	return sessions, nil, nil
}

func (r *fakeRefreshRepo) DeleteExpiredBefore(_ context.Context, cutoff time.Time, limit int) (int64, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	var deleted int64
	for hash, token := range r.byHash {
		if deleted >= int64(limit) {
			break
		}
		if token.ExpiresAt.Before(cutoff) || (token.RevokedAt != nil && token.RevokedAt.Before(cutoff)) {
			delete(r.byHash, hash)
			deleted++
		}
	}
	return deleted, nil
}

// -------------------------------------------------------------- revocation --

type fakeRevocation struct {
	mu       sync.Mutex
	denied   map[string]time.Duration
	markers  map[uuid.UUID]time.Time
	failWith error
}

func newRevocation() *fakeRevocation {
	return &fakeRevocation{denied: map[string]time.Duration{}, markers: map[uuid.UUID]time.Time{}}
}

func (s *fakeRevocation) DenyAccessToken(_ context.Context, jti string, ttl time.Duration) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.failWith != nil {
		return s.failWith
	}
	s.denied[jti] = ttl
	return nil
}

func (s *fakeRevocation) RevokeIssuedBefore(_ context.Context, accountID uuid.UUID, issuedBefore time.Time, _ time.Duration) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.failWith != nil {
		return s.failWith
	}
	s.markers[accountID] = issuedBefore
	return nil
}

// ------------------------------------------------------------ rate limiter --

type fakeLimiter struct {
	mu       sync.Mutex
	counts   map[string]int
	failWith error
}

func newLimiter() *fakeLimiter {
	return &fakeLimiter{counts: map[string]int{}}
}

func (l *fakeLimiter) Allow(_ context.Context, scope, subject string, rule port.RateLimitRule) (port.RateLimitResult, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.failWith != nil {
		return port.RateLimitResult{}, l.failWith
	}
	if !rule.Enabled() {
		return port.RateLimitResult{Allowed: true}, nil
	}

	key := scope + "|" + subject
	l.counts[key]++
	return port.RateLimitResult{
		Allowed:    l.counts[key] <= rule.Limit,
		Remaining:  rule.Limit - l.counts[key],
		RetryAfter: rule.Window,
	}, nil
}

// -------------------------------------------------------------- otp store --

type storedOtp struct {
	challenge port.OtpChallenge
	attempts  int
}

type fakeOtpStore struct {
	mu        sync.Mutex
	issued    map[string]*storedOtp
	resends   map[string]int
	lastCodes map[string]string
}

func newOtpStore() *fakeOtpStore {
	return &fakeOtpStore{
		issued:    map[string]*storedOtp{},
		resends:   map[string]int{},
		lastCodes: map[string]string{},
	}
}

func otpKey(purpose string, accountID uuid.UUID) string {
	return purpose + "|" + accountID.String()
}

func (s *fakeOtpStore) Issue(_ context.Context, challenge port.OtpChallenge, _ time.Duration) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.issued[otpKey(challenge.Purpose, challenge.AccountID)] = &storedOtp{challenge: challenge}
	return nil
}

func (s *fakeOtpStore) Consume(_ context.Context, purpose string, accountID uuid.UUID, codeHash, expectedSubject string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	key := otpKey(purpose, accountID)
	stored, ok := s.issued[key]
	if !ok {
		return apperr.ErrOtpInvalid
	}
	if stored.challenge.CodeHash == codeHash &&
		(stored.challenge.Subject == "" || stored.challenge.Subject == expectedSubject) {
		delete(s.issued, key)
		return nil
	}

	stored.attempts++
	if stored.challenge.MaxAttempts > 0 && stored.attempts >= stored.challenge.MaxAttempts {
		delete(s.issued, key)
	}
	return apperr.ErrOtpInvalid
}

func (s *fakeOtpStore) RegisterResend(_ context.Context, purpose string, accountID uuid.UUID, _ time.Duration) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	key := otpKey(purpose, accountID)
	s.resends[key]++
	return s.resends[key], nil
}

func (s *fakeOtpStore) issuedFor(purpose string, accountID uuid.UUID) (port.OtpChallenge, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	stored, ok := s.issued[otpKey(purpose, accountID)]
	if !ok {
		return port.OtpChallenge{}, false
	}
	return stored.challenge, true
}

// ------------------------------------------------------- reset token store --

type fakeResetStore struct {
	mu     sync.Mutex
	tokens map[string]uuid.UUID
}

func newResetStore() *fakeResetStore {
	return &fakeResetStore{tokens: map[string]uuid.UUID{}}
}

func (s *fakeResetStore) Issue(_ context.Context, tokenHash string, accountID uuid.UUID, _ time.Duration) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.tokens[tokenHash] = accountID
	return nil
}

func (s *fakeResetStore) Consume(_ context.Context, tokenHash string) (uuid.UUID, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	accountID, ok := s.tokens[tokenHash]
	if !ok {
		return uuid.Nil, apperr.ErrResetTokenInvalid
	}
	delete(s.tokens, tokenHash)
	return accountID, nil
}

func (s *fakeResetStore) InvalidateForAccount(_ context.Context, accountID uuid.UUID) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for hash, id := range s.tokens {
		if id == accountID {
			delete(s.tokens, hash)
		}
	}
	return nil
}

// --------------------------------------------------------- delivery store --

type fakeDeliveryStore struct {
	mu    sync.Mutex
	items map[uuid.UUID]*entity.NotificationDelivery
}

func newDeliveryStore() *fakeDeliveryStore {
	return &fakeDeliveryStore{items: map[uuid.UUID]*entity.NotificationDelivery{}}
}

func (s *fakeDeliveryStore) Put(_ context.Context, delivery *entity.NotificationDelivery, _ time.Duration) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.items[delivery.DeliveryID] = delivery
	return nil
}

func (s *fakeDeliveryStore) Get(_ context.Context, deliveryID uuid.UUID) (*entity.NotificationDelivery, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	delivery, ok := s.items[deliveryID]
	if !ok {
		return nil, apperr.ErrDeliveryNotFound
	}
	return delivery, nil
}

func (s *fakeDeliveryStore) all() []*entity.NotificationDelivery {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]*entity.NotificationDelivery, 0, len(s.items))
	for _, delivery := range s.items {
		out = append(out, delivery)
	}
	return out
}

func (s *fakeDeliveryStore) byTemplate(template string) *entity.NotificationDelivery {
	for _, delivery := range s.all() {
		if delivery.Template == template {
			return delivery
		}
	}
	return nil
}

// ------------------------------------------------------------- publisher ---

type fakePublisher struct {
	mu       sync.Mutex
	events   []*event.Envelope
	failWith error
}

func newPublisher() *fakePublisher { return &fakePublisher{} }

func (p *fakePublisher) Publish(_ context.Context, envelope *event.Envelope) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.failWith != nil {
		return p.failWith
	}
	p.events = append(p.events, envelope)
	return nil
}

func (p *fakePublisher) types() []string {
	p.mu.Lock()
	defer p.mu.Unlock()
	out := make([]string, 0, len(p.events))
	for _, envelope := range p.events {
		out = append(out, envelope.Type)
	}
	return out
}

func (p *fakePublisher) has(eventType string) bool {
	for _, published := range p.types() {
		if published == eventType {
			return true
		}
	}
	return false
}

// ---------------------------------------------------------------- hasher ---

type fakeHasher struct{}

func (fakeHasher) Hash(password string) (string, error) { return "hashed:" + password, nil }

func (fakeHasher) Verify(encodedHash, password string) (bool, error) {
	return encodedHash == "hashed:"+password, nil
}

// ---------------------------------------------------------------- signer ---

type fakeSigner struct {
	mu          sync.Mutex
	kid         string
	signed      int
	invalidated int
	failWith    error
}

func newSigner() *fakeSigner { return &fakeSigner{kid: uuid.NewString()} }

func (s *fakeSigner) Sign(_ context.Context, claims map[string]any) (string, string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.failWith != nil {
		return "", "", s.failWith
	}
	s.signed++
	jti, _ := claims["jti"].(string)
	return "signed-token." + jti, s.kid, nil
}

func (s *fakeSigner) PublicKeys(context.Context) ([]port.JWK, error) {
	return []port.JWK{{Kty: "RSA", Kid: s.kid, Use: "sig", Alg: "RS256", N: "n", E: "AQAB"}}, nil
}

func (s *fakeSigner) InvalidateCache() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.invalidated++
}

// -------------------------------------------------------- jwt key storage --

type fakeKeyRepo struct {
	mu   sync.Mutex
	keys []*entity.JwtKey
}

func newKeyRepo() *fakeKeyRepo { return &fakeKeyRepo{} }

func (r *fakeKeyRepo) Create(_ context.Context, key *entity.JwtKey) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.keys = append(r.keys, key)
	return nil
}

func (r *fakeKeyRepo) GetActive(context.Context) (*entity.JwtKey, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	for _, key := range r.keys {
		if key.Status == "ACTIVE" {
			return key, nil
		}
	}
	return nil, apperr.ErrSigningKeyMissing
}

func (r *fakeKeyRepo) ListPublishable(context.Context) ([]*entity.JwtKey, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	var publishable []*entity.JwtKey
	for _, key := range r.keys {
		if key.IsPublishable() {
			publishable = append(publishable, key)
		}
	}
	return publishable, nil
}

func (r *fakeKeyRepo) Rotate(_ context.Context, next *entity.JwtKey, now time.Time) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	for _, key := range r.keys {
		if key.Status == "ACTIVE" {
			phaseOutAt := now
			key.Status = "PHASE_OUT"
			key.PhaseOutAt = &phaseOutAt
		}
	}
	r.keys = append(r.keys, next)
	return nil
}

func (r *fakeKeyRepo) ListRetirable(_ context.Context, phasedOutBefore time.Time) ([]*entity.JwtKey, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	var retirable []*entity.JwtKey
	for _, key := range r.keys {
		if key.Status == "PHASE_OUT" && key.PhaseOutAt != nil && !key.PhaseOutAt.After(phasedOutBefore) {
			retirable = append(retirable, key)
		}
	}
	return retirable, nil
}

func (r *fakeKeyRepo) Retire(_ context.Context, kid uuid.UUID, now time.Time) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	for _, key := range r.keys {
		if key.KID == kid {
			retiredAt := now
			key.Status = "RETIRED"
			key.RetiredAt = &retiredAt
		}
	}
	return nil
}

type fakeKeyGenerator struct{ generated int }

func (g *fakeKeyGenerator) Generate() (port.KeyMaterial, error) {
	g.generated++
	return port.KeyMaterial{
		PublicKeyPEM:         "public-key-pem",
		PrivateKeyCiphertext: []byte("ciphertext"),
		PrivateKeyNonce:      []byte("nonce--12byte"),
		EncryptionKeyVersion: 1,
	}, nil
}

type fakeJwksNotifier struct{ notified int }

func (n *fakeJwksNotifier) NotifyKeysChanged(context.Context) error {
	n.notified++
	return nil
}

// ------------------------------------------------------------------- tx ----

// fakeTxManager runs the unit of work inline. The in-memory doubles cannot roll
// back, which is acceptable because the transaction boundary itself is verified
// by the integration layer, not here.
type fakeTxManager struct{}

func (fakeTxManager) WithinTx(ctx context.Context, fn func(ctx context.Context) error) error {
	return fn(ctx)
}

// Compile-time checks that the doubles still satisfy the ports.
var (
	_ port.AccountRepository      = (*fakeAccountRepo)(nil)
	_ port.RefreshTokenRepository = (*fakeRefreshRepo)(nil)
	_ port.RevocationStore        = (*fakeRevocation)(nil)
	_ port.RateLimiter            = (*fakeLimiter)(nil)
	_ port.OtpStore               = (*fakeOtpStore)(nil)
	_ port.ResetTokenStore        = (*fakeResetStore)(nil)
	_ port.DeliveryStore          = (*fakeDeliveryStore)(nil)
	_ port.EventPublisher         = (*fakePublisher)(nil)
	_ port.PasswordHasher         = (*fakeHasher)(nil)
	_ port.Signer                 = (*fakeSigner)(nil)
	_ port.JwtKeyRepository       = (*fakeKeyRepo)(nil)
	_ port.KeyGenerator           = (*fakeKeyGenerator)(nil)
	_ port.JwksNotifier           = (*fakeJwksNotifier)(nil)
	_ port.TxManager              = (*fakeTxManager)(nil)
)
