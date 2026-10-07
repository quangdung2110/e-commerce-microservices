package service

import (
	"context"
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"errors"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/ecommerce/gateway-auth/internal/domain"
)

const (
	issuer   = "https://auth.ecommerce.local"
	audience = "ecommerce-api"
	kid      = "key-1"
	subject  = "0b6f5e2e-3f7a-4c4e-9a53-3c1a0f2a9e11"
	session  = "6a0e3b8d-7c55-4a8f-8d1e-2f4b5c6d7e8f"
)

var fixedNow = time.Date(2026, 9, 29, 10, 0, 0, 0, time.UTC)

type fakeKeys struct {
	keys map[string]crypto.PublicKey
	err  error
}

func (f fakeKeys) PublicKey(_ context.Context, kid string) (crypto.PublicKey, error) {
	if f.err != nil {
		return nil, f.err
	}
	if key, ok := f.keys[kid]; ok {
		return key, nil
	}
	return nil, domain.ErrUnknownKey
}

type fakeRevocations struct {
	revoked bool
	err     error
	gotIat  time.Time
}

func (f *fakeRevocations) IsRevoked(_ context.Context, _, _ string, issuedAt time.Time) (bool, error) {
	f.gotIat = issuedAt
	return f.revoked, f.err
}

type fixture struct {
	private     *rsa.PrivateKey
	keys        fakeKeys
	revocations *fakeRevocations
}

func newFixture(t *testing.T) *fixture {
	t.Helper()
	private, err := rsa.GenerateKey(rand.Reader, 2048)
	require.NoError(t, err)
	return &fixture{
		private:     private,
		keys:        fakeKeys{keys: map[string]crypto.PublicKey{kid: &private.PublicKey}},
		revocations: &fakeRevocations{},
	}
}

func (f *fixture) verifier() *Verifier {
	return NewVerifier(VerifierConfig{
		Issuer:            issuer,
		Audience:          audience,
		AllowedAlgorithms: []string{"RS256"},
		Leeway:            5 * time.Second,
	}, f.keys, f.revocations, func() time.Time { return fixedNow })
}

// validClaims mirrors auth-service TokenService exactly.
func validClaims() jwt.MapClaims {
	return jwt.MapClaims{
		"iss":   issuer,
		"aud":   audience,
		"sub":   subject,
		"jti":   "jti-123",
		"sid":   session,
		"roles": []string{"USER", "ADMIN"},
		"iat":   fixedNow.Add(-time.Minute).Unix(),
		"nbf":   fixedNow.Add(-time.Minute - 30*time.Second).Unix(),
		"exp":   fixedNow.Add(14 * time.Minute).Unix(),
	}
}

func (f *fixture) sign(t *testing.T, claims jwt.MapClaims, mutate ...func(*jwt.Token)) string {
	t.Helper()
	token := jwt.NewWithClaims(jwt.SigningMethodRS256, claims)
	token.Header["kid"] = kid
	for _, m := range mutate {
		m(token)
	}
	signed, err := token.SignedString(f.private)
	require.NoError(t, err)
	return signed
}

func TestVerify_ValidTokenYieldsContractIdentity(t *testing.T) {
	f := newFixture(t)
	identity, err := f.verifier().Verify(context.Background(), "Bearer "+f.sign(t, validClaims()))

	require.NoError(t, err)
	assert.Equal(t, Identity{Subject: subject, Roles: []string{"USER", "ADMIN"}, SessionID: session, TokenID: "jti-123"}, identity)
	assert.Equal(t, fixedNow.Add(-time.Minute).Unix(), f.revocations.gotIat.Unix())
}

func TestVerify_MissingOrMalformedAuthorization(t *testing.T) {
	f := newFixture(t)
	for _, header := range []string{"", "Bearer", "Bearer   ", "Basic dXNlcjpwYXNz", "token-without-scheme"} {
		_, err := f.verifier().Verify(context.Background(), header)
		assert.ErrorIs(t, err, domain.ErrMissingCredentials, "header %q", header)
	}
}

func TestVerify_RejectsContractViolations(t *testing.T) {
	cases := map[string]func(jwt.MapClaims){
		"wrong issuer":      func(c jwt.MapClaims) { c["iss"] = "https://evil.example" },
		"wrong audience":    func(c jwt.MapClaims) { c["aud"] = "other-api" },
		"expired":           func(c jwt.MapClaims) { c["exp"] = fixedNow.Add(-time.Minute).Unix() },
		"not yet valid":     func(c jwt.MapClaims) { c["nbf"] = fixedNow.Add(time.Minute).Unix() },
		"issued in future":  func(c jwt.MapClaims) { c["iat"] = fixedNow.Add(time.Minute).Unix() },
		"missing exp":       func(c jwt.MapClaims) { delete(c, "exp") },
		"missing sid":       func(c jwt.MapClaims) { delete(c, "sid") },
		"missing nbf":       func(c jwt.MapClaims) { delete(c, "nbf") },
		"extra email claim": func(c jwt.MapClaims) { c["email"] = "a@b.test" },
		"sub not uuid":      func(c jwt.MapClaims) { c["sub"] = "admin" },
		"roles not array":   func(c jwt.MapClaims) { c["roles"] = "ADMIN" },
		"role with comma":   func(c jwt.MapClaims) { c["roles"] = []string{"USER,ADMIN"} },
		"empty jti":         func(c jwt.MapClaims) { c["jti"] = "" },
	}
	for name, mutate := range cases {
		t.Run(name, func(t *testing.T) {
			f := newFixture(t)
			claims := validClaims()
			mutate(claims)
			_, err := f.verifier().Verify(context.Background(), "Bearer "+f.sign(t, claims))
			assert.ErrorIs(t, err, domain.ErrInvalidToken)
		})
	}
}

func TestVerify_RejectsAlgorithmConfusion(t *testing.T) {
	f := newFixture(t)

	// HS256 "signed" with the public key bytes: the classic confusion attack.
	hmac := jwt.NewWithClaims(jwt.SigningMethodHS256, validClaims())
	hmac.Header["kid"] = kid
	hmacToken, err := hmac.SignedString([]byte("public-key-bytes"))
	require.NoError(t, err)

	none := jwt.NewWithClaims(jwt.SigningMethodNone, validClaims())
	none.Header["kid"] = kid
	noneToken, err := none.SignedString(jwt.UnsafeAllowNoneSignatureType)
	require.NoError(t, err)

	for name, token := range map[string]string{"HS256": hmacToken, "none": noneToken} {
		_, err := f.verifier().Verify(context.Background(), "Bearer "+token)
		assert.ErrorIs(t, err, domain.ErrInvalidToken, name)
	}
}

func TestVerify_SignatureFromAnotherKeyIsInvalid(t *testing.T) {
	f := newFixture(t)
	other, err := rsa.GenerateKey(rand.Reader, 2048)
	require.NoError(t, err)
	token := jwt.NewWithClaims(jwt.SigningMethodRS256, validClaims())
	token.Header["kid"] = kid
	forged, err := token.SignedString(other)
	require.NoError(t, err)

	_, err = f.verifier().Verify(context.Background(), "Bearer "+forged)
	assert.ErrorIs(t, err, domain.ErrInvalidToken)
}

func TestVerify_UnknownOrMissingKidIsInvalid(t *testing.T) {
	f := newFixture(t)
	unknown := f.sign(t, validClaims(), func(tok *jwt.Token) { tok.Header["kid"] = "retired" })
	missing := f.sign(t, validClaims(), func(tok *jwt.Token) { delete(tok.Header, "kid") })

	for name, token := range map[string]string{"unknown": unknown, "missing": missing} {
		_, err := f.verifier().Verify(context.Background(), "Bearer "+token)
		assert.ErrorIs(t, err, domain.ErrInvalidToken, name)
	}
}

func TestVerify_KeySourceOutageFailsClosed(t *testing.T) {
	f := newFixture(t)
	f.keys.err = errors.Join(domain.ErrDependencyUnavailable, errors.New("jwks down"))

	_, err := f.verifier().Verify(context.Background(), "Bearer "+f.sign(t, validClaims()))
	assert.ErrorIs(t, err, domain.ErrDependencyUnavailable)
	assert.NotErrorIs(t, err, domain.ErrInvalidToken, "an outage must not log the user out")
}

func TestVerify_Revocation(t *testing.T) {
	f := newFixture(t)
	f.revocations.revoked = true
	_, err := f.verifier().Verify(context.Background(), "Bearer "+f.sign(t, validClaims()))
	assert.ErrorIs(t, err, domain.ErrRevokedToken)

	f = newFixture(t)
	f.revocations.err = errors.Join(domain.ErrDependencyUnavailable, errors.New("redis down"))
	_, err = f.verifier().Verify(context.Background(), "Bearer "+f.sign(t, validClaims()))
	assert.ErrorIs(t, err, domain.ErrDependencyUnavailable)
}
