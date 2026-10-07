//go:build integration

package integration_test

import (
	"context"
	"crypto/rsa"
	"encoding/base64"
	"math/big"
	"strings"
	"testing"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
	"github.com/stretchr/testify/require"

	"github.com/ecommerce/auth-service/internal/common/clock"
	"github.com/ecommerce/auth-service/internal/common/config"
	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/port"
	"github.com/ecommerce/auth-service/internal/domain/service"
	"github.com/ecommerce/auth-service/internal/infrastructure/crypto"
	"github.com/ecommerce/auth-service/internal/infrastructure/postgres"
)

// testMasterKey is a fixed 32-byte development key. Production supplies one
// through a secret.
func testMasterKey() config.KeysConfig {
	return config.KeysConfig{
		MasterKeyBase64:  base64.StdEncoding.EncodeToString([]byte("0123456789abcdef0123456789abcdef")),
		MasterKeyVersion: 1,
	}
}

func TestArgon2HashingRoundTrip(t *testing.T) {
	hasher := crypto.NewArgon2Hasher(config.Argon2Config{
		Memory: 16384, Iterations: 1, Parallelism: 1, SaltLength: 16, KeyLength: 32,
	})

	hash, err := hasher.Hash("correct horse battery")
	require.NoError(t, err)
	require.True(t, strings.HasPrefix(hash, "$argon2id$v=19$"), "the stored form is a PHC string")
	require.NotContains(t, hash, "correct horse battery")

	ok, err := hasher.Verify(hash, "correct horse battery")
	require.NoError(t, err)
	require.True(t, ok)

	ok, err = hasher.Verify(hash, "wrong password entirely")
	require.NoError(t, err)
	require.False(t, ok)

	// The salt makes every hash of the same password unique.
	other, err := hasher.Hash("correct horse battery")
	require.NoError(t, err)
	require.NotEqual(t, hash, other)

	// A hash the service cannot read is an error, not a silent mismatch.
	_, err = hasher.Verify("not-a-phc-string", "anything")
	require.Error(t, err)

	// An empty stored hash simply fails to match; a deleted account has none.
	ok, err = hasher.Verify("", "anything")
	require.NoError(t, err)
	require.False(t, ok)
}

func TestKeyCipherRoundTripAndVersioning(t *testing.T) {
	cipher, err := crypto.NewKeyCipher(testMasterKey())
	require.NoError(t, err)

	ciphertext, nonce, err := cipher.Encrypt([]byte("private key material"))
	require.NoError(t, err)
	require.Len(t, nonce, 12, "the schema constrains the nonce to 12 bytes")
	require.NotContains(t, string(ciphertext), "private key material")

	plaintext, err := cipher.Decrypt(ciphertext, nonce, cipher.Version())
	require.NoError(t, err)
	require.Equal(t, "private key material", string(plaintext))

	// A row written under a master key that is no longer configured must fail
	// loudly rather than produce garbage.
	_, err = cipher.Decrypt(ciphertext, nonce, 7)
	require.Error(t, err)

	// Tampered ciphertext is rejected by the AEAD tag.
	tampered := append([]byte(nil), ciphertext...)
	tampered[0] ^= 0xff
	_, err = cipher.Decrypt(tampered, nonce, cipher.Version())
	require.Error(t, err)

	// A master key of the wrong size is a configuration error.
	_, err = crypto.NewKeyCipher(config.KeysConfig{MasterKeyBase64: base64.StdEncoding.EncodeToString([]byte("too-short")), MasterKeyVersion: 1})
	require.Error(t, err)
}

// The full issuing path: generate a key, encrypt it, store it, sign a token, and
// verify that token with the JWKS document a gateway would fetch.
func TestSigningPathEndToEnd(t *testing.T) {
	resetState(t)
	ctx := context.Background()

	cipher, err := crypto.NewKeyCipher(testMasterKey())
	require.NoError(t, err)

	repo := postgres.NewJwtKeyRepository(testPool)
	generator := crypto.NewRSAKeyGenerator(2048, cipher)
	signer := crypto.NewSigner(repo, cipher, clock.System{}, time.Minute)

	// Without a key the service fails closed instead of issuing a token nobody
	// can verify.
	_, _, err = signer.Sign(ctx, map[string]any{"sub": uuid.NewString()})
	require.ErrorIs(t, err, apperr.ErrDependencyUnavailable)

	_, err = signer.PublicKeys(ctx)
	require.ErrorIs(t, err, apperr.ErrDependencyUnavailable)

	material, err := generator.Generate()
	require.NoError(t, err)
	require.Contains(t, material.PublicKeyPEM, "BEGIN PUBLIC KEY")
	require.NotContains(t, material.PublicKeyPEM, "PRIVATE")

	key := entity.NewJwtKey(material.PublicKeyPEM, material.PrivateKeyCiphertext,
		material.PrivateKeyNonce, material.EncryptionKeyVersion, time.Now())
	require.NoError(t, repo.Create(ctx, key))
	signer.InvalidateCache()

	issuer := service.NewTokenService(signer, clock.System{}, service.TokenConfig{
		Issuer:    "https://auth.test",
		Audience:  "ecommerce-api",
		AccessTTL: 15 * time.Minute,
		ClockSkew: 30 * time.Second,
	})

	account := newAccount(t, "owner@example.com", time.Now().UTC())
	require.NoError(t, account.Activate(time.Now().UTC()))
	sessionID := uuid.New()

	access, err := issuer.Issue(ctx, account, sessionID)
	require.NoError(t, err)
	require.Equal(t, key.KID.String(), access.KeyID)
	require.Equal(t, 900, access.ExpiresIn())

	jwks, err := signer.PublicKeys(ctx)
	require.NoError(t, err)
	require.Len(t, jwks, 1)
	require.Equal(t, "RSA", jwks[0].Kty)
	require.Equal(t, "sig", jwks[0].Use)
	require.Equal(t, "RS256", jwks[0].Alg)
	require.Equal(t, key.KID.String(), jwks[0].Kid)

	// A gateway rebuilds the public key from the JWK and validates the token
	// exactly as the runtime contract describes.
	claims := jwt.MapClaims{}
	parsed, err := jwt.ParseWithClaims(access.Token, claims, func(token *jwt.Token) (any, error) {
		require.Equal(t, "RS256", token.Method.Alg())
		require.Equal(t, key.KID.String(), token.Header["kid"], "the kid selects the verification key")
		return publicKeyOf(t, jwks[0]), nil
	},
		jwt.WithIssuer("https://auth.test"),
		jwt.WithAudience("ecommerce-api"),
		jwt.WithValidMethods([]string{"RS256"}),
	)
	require.NoError(t, err)
	require.True(t, parsed.Valid)

	require.Equal(t, account.ID.String(), claims["sub"])
	require.Equal(t, sessionID.String(), claims["sid"])
	require.Equal(t, access.JTI, claims["jti"])
	require.Contains(t, claims, "iat")
	require.Contains(t, claims, "nbf")
	require.Contains(t, claims, "exp")
	require.Equal(t, []any{"USER"}, claims["roles"])

	// A token signed by a key that is no longer published must not verify with
	// the current key.
	rotatedAt := time.Now().Add(time.Hour)
	nextMaterial, err := generator.Generate()
	require.NoError(t, err)
	nextKey := entity.NewJwtKey(nextMaterial.PublicKeyPEM, nextMaterial.PrivateKeyCiphertext,
		nextMaterial.PrivateKeyNonce, nextMaterial.EncryptionKeyVersion, rotatedAt)
	require.NoError(t, repo.Rotate(ctx, nextKey, rotatedAt))
	signer.InvalidateCache()

	rotatedJWKS, err := signer.PublicKeys(ctx)
	require.NoError(t, err)
	require.Len(t, rotatedJWKS, 2, "the phase-out key stays published during the overlap")

	// New tokens are signed by the new key.
	newAccess, err := issuer.Issue(ctx, account, sessionID)
	require.NoError(t, err)
	require.Equal(t, nextKey.KID.String(), newAccess.KeyID)
}

// The key lifecycle service is what the workers drive.
func TestKeyLifecycleAgainstPostgres(t *testing.T) {
	resetState(t)
	ctx := context.Background()

	cipher, err := crypto.NewKeyCipher(testMasterKey())
	require.NoError(t, err)

	repo := postgres.NewJwtKeyRepository(testPool)
	signer := crypto.NewSigner(repo, cipher, clock.System{}, time.Minute)
	notifier := &countingNotifier{}
	testClock := clock.NewFixed(time.Now().UTC())

	lifecycle := service.NewKeyLifecycleService(repo, crypto.NewRSAKeyGenerator(2048, cipher),
		signer, notifier, testClock,
		service.KeyPolicy{RotationInterval: 30 * 24 * time.Hour, RetirementDelay: 24 * time.Hour},
		testLogger())

	require.NoError(t, lifecycle.EnsureActiveKey(ctx))
	require.Positive(t, notifier.count)

	// A restart must not create a second key.
	require.NoError(t, lifecycle.EnsureActiveKey(ctx))
	publishable, err := repo.ListPublishable(ctx)
	require.NoError(t, err)
	require.Len(t, publishable, 1)

	rotated, err := lifecycle.RotateIfDue(ctx)
	require.NoError(t, err)
	require.False(t, rotated, "the key has not reached its rotation age")

	testClock.Advance(31 * 24 * time.Hour)
	rotated, err = lifecycle.RotateIfDue(ctx)
	require.NoError(t, err)
	require.True(t, rotated)

	retired, err := lifecycle.RetireDue(ctx)
	require.NoError(t, err)
	require.Zero(t, retired, "the overlap window must elapse first")

	testClock.Advance(25 * time.Hour)
	retired, err = lifecycle.RetireDue(ctx)
	require.NoError(t, err)
	require.Equal(t, 1, retired)

	publishable, err = repo.ListPublishable(ctx)
	require.NoError(t, err)
	require.Len(t, publishable, 1)
}

type countingNotifier struct{ count int }

func (n *countingNotifier) NotifyKeysChanged(context.Context) error {
	n.count++
	return nil
}

func publicKeyOf(t *testing.T, jwk port.JWK) *rsa.PublicKey {
	t.Helper()

	modulus, err := base64.RawURLEncoding.DecodeString(jwk.N)
	require.NoError(t, err)
	exponent, err := base64.RawURLEncoding.DecodeString(jwk.E)
	require.NoError(t, err)

	return &rsa.PublicKey{
		N: new(big.Int).SetBytes(modulus),
		E: int(new(big.Int).SetBytes(exponent).Int64()),
	}
}
