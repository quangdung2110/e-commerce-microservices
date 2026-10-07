//go:build integration

package integration_test

import (
	"context"
	"strconv"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"

	"github.com/ecommerce/auth-service/internal/common/clock"
	"github.com/ecommerce/auth-service/internal/common/constants"
	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/common/keybuilder"
	"github.com/ecommerce/auth-service/internal/common/secret"
	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/port"
	redisadapter "github.com/ecommerce/auth-service/internal/infrastructure/redis"
)

func testKeys() *keybuilder.Builder {
	return keybuilder.New(testRedisConf.RevocationPrefix, testRedisConf.StatePrefix)
}

// gateway-auth reads these exact keys, so the names are part of the contract.
func TestRevocationStoreWritesTheGatewayContractKeys(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	store := redisadapter.NewRevocationStore(testRedis, testKeys())

	jti := uuid.NewString()
	accountID := uuid.New()
	issuedBefore := time.Now().UTC().Truncate(time.Second)

	require.NoError(t, store.DenyAccessToken(ctx, jti, time.Minute))
	require.NoError(t, store.RevokeIssuedBefore(ctx, accountID, issuedBefore, time.Minute))

	denylistKey := "auth:revocation:denylist:" + jti
	markerKey := "auth:revocation:user:" + accountID.String() + ":revoked_before"

	require.Equal(t, int64(1), testRedis.Exists(ctx, denylistKey).Val())
	require.Positive(t, testRedis.TTL(ctx, denylistKey).Val())

	// The marker is Unix seconds, which is what the gateway compares to iat.
	stored, err := testRedis.Get(ctx, markerKey).Result()
	require.NoError(t, err)
	require.Equal(t, strconv.FormatInt(issuedBefore.Unix(), 10), stored)
	require.Positive(t, testRedis.TTL(ctx, markerKey).Val())
}

func TestRateLimiterCountsInsideTheWindow(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	limiter := redisadapter.NewRateLimiter(testRedis, testKeys())

	rule := port.RateLimitRule{Limit: 2, Window: time.Minute}
	subject := uuid.NewString()

	first, err := limiter.Allow(ctx, "test", subject, rule)
	require.NoError(t, err)
	require.True(t, first.Allowed)
	require.Equal(t, 1, first.Remaining)
	require.Positive(t, first.RetryAfter, "the window is armed on the first hit")

	second, err := limiter.Allow(ctx, "test", subject, rule)
	require.NoError(t, err)
	require.True(t, second.Allowed)
	require.Zero(t, second.Remaining)

	third, err := limiter.Allow(ctx, "test", subject, rule)
	require.NoError(t, err)
	require.False(t, third.Allowed)

	// A different subject has its own budget.
	other, err := limiter.Allow(ctx, "test", uuid.NewString(), rule)
	require.NoError(t, err)
	require.True(t, other.Allowed)

	// A disabled rule never blocks.
	disabled, err := limiter.Allow(ctx, "test", subject, port.RateLimitRule{})
	require.NoError(t, err)
	require.True(t, disabled.Allowed)
}

// AUTH-BR-005: a code is single use, bound to its subject, and limited in
// attempts. All of it happens inside one Lua script, so it holds under
// concurrency too.
func TestOtpStoreConsumesExactlyOnce(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	store := redisadapter.NewOtpStore(testRedis, testKeys())

	accountID := uuid.New()
	code := "123456"
	challenge := port.OtpChallenge{
		AccountID:   accountID,
		Purpose:     constants.OtpPurposeRegistration,
		CodeHash:    secret.HashHex(code),
		Subject:     "owner@example.com",
		ExpiresAt:   time.Now().Add(5 * time.Minute),
		MaxAttempts: 3,
	}
	require.NoError(t, store.Issue(ctx, challenge, 5*time.Minute))

	require.NoError(t, store.Consume(ctx, challenge.Purpose, accountID, secret.HashHex(code), "owner@example.com"))

	// The replay of a consumed code fails.
	require.ErrorIs(t,
		store.Consume(ctx, challenge.Purpose, accountID, secret.HashHex(code), "owner@example.com"),
		apperr.ErrOtpInvalid)
}

func TestOtpStoreRejectsAWrongSubject(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	store := redisadapter.NewOtpStore(testRedis, testKeys())

	accountID := uuid.New()
	code := "654321"
	require.NoError(t, store.Issue(ctx, port.OtpChallenge{
		AccountID:   accountID,
		Purpose:     constants.OtpPurposeEmailChange,
		CodeHash:    secret.HashHex(code),
		Subject:     "new.owner@example.com",
		ExpiresAt:   time.Now().Add(5 * time.Minute),
		MaxAttempts: 3,
	}, 5*time.Minute))

	// A code issued for one address cannot confirm another.
	require.ErrorIs(t,
		store.Consume(ctx, constants.OtpPurposeEmailChange, accountID, secret.HashHex(code), "someone.else@example.com"),
		apperr.ErrOtpInvalid)

	// The bound address still works, so a mismatch does not destroy the code.
	require.NoError(t,
		store.Consume(ctx, constants.OtpPurposeEmailChange, accountID, secret.HashHex(code), "new.owner@example.com"))
}

func TestOtpStoreEnforcesTheAttemptBudget(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	store := redisadapter.NewOtpStore(testRedis, testKeys())

	accountID := uuid.New()
	code := "111111"
	require.NoError(t, store.Issue(ctx, port.OtpChallenge{
		AccountID:   accountID,
		Purpose:     constants.OtpPurposeRegistration,
		CodeHash:    secret.HashHex(code),
		Subject:     "owner@example.com",
		ExpiresAt:   time.Now().Add(5 * time.Minute),
		MaxAttempts: 2,
	}, 5*time.Minute))

	for i := 0; i < 2; i++ {
		require.ErrorIs(t,
			store.Consume(ctx, constants.OtpPurposeRegistration, accountID, secret.HashHex("000000"), "owner@example.com"),
			apperr.ErrOtpInvalid)
	}

	// The budget is spent, so even the right code no longer works.
	require.ErrorIs(t,
		store.Consume(ctx, constants.OtpPurposeRegistration, accountID, secret.HashHex(code), "owner@example.com"),
		apperr.ErrOtpInvalid)
}

func TestOtpStoreIssueReplacesThePendingCode(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	store := redisadapter.NewOtpStore(testRedis, testKeys())

	accountID := uuid.New()
	challenge := func(code string) port.OtpChallenge {
		return port.OtpChallenge{
			AccountID:   accountID,
			Purpose:     constants.OtpPurposeRegistration,
			CodeHash:    secret.HashHex(code),
			Subject:     "owner@example.com",
			ExpiresAt:   time.Now().Add(5 * time.Minute),
			MaxAttempts: 3,
		}
	}

	require.NoError(t, store.Issue(ctx, challenge("111111"), 5*time.Minute))
	require.NoError(t, store.Issue(ctx, challenge("222222"), 5*time.Minute))

	// Only the newest code is valid, and the older one costs an attempt.
	require.ErrorIs(t,
		store.Consume(ctx, constants.OtpPurposeRegistration, accountID, secret.HashHex("111111"), "owner@example.com"),
		apperr.ErrOtpInvalid)
	require.NoError(t,
		store.Consume(ctx, constants.OtpPurposeRegistration, accountID, secret.HashHex("222222"), "owner@example.com"))
}

func TestOtpStoreCountsResends(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	store := redisadapter.NewOtpStore(testRedis, testKeys())
	accountID := uuid.New()

	for expected := 1; expected <= 3; expected++ {
		count, err := store.RegisterResend(ctx, constants.OtpPurposeRegistration, accountID, time.Minute)
		require.NoError(t, err)
		require.Equal(t, expected, count)
	}
}

func TestResetTokenStoreIsSingleUse(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	store := redisadapter.NewResetTokenStore(testRedis, testKeys())

	accountID := uuid.New()
	token, err := secret.NewOpaqueToken(constants.ResetTokenBytes)
	require.NoError(t, err)
	require.Len(t, token, 43, "the contract requires at least 43 characters")

	require.NoError(t, store.Issue(ctx, secret.HashHex(token), accountID, 15*time.Minute))

	owner, err := store.Consume(ctx, secret.HashHex(token))
	require.NoError(t, err)
	require.Equal(t, accountID, owner)

	_, err = store.Consume(ctx, secret.HashHex(token))
	require.ErrorIs(t, err, apperr.ErrResetTokenInvalid)
}

func TestResetTokenStoreKeepsOnlyTheNewestLink(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	store := redisadapter.NewResetTokenStore(testRedis, testKeys())

	accountID := uuid.New()
	older, err := secret.NewOpaqueToken(constants.ResetTokenBytes)
	require.NoError(t, err)
	newer, err := secret.NewOpaqueToken(constants.ResetTokenBytes)
	require.NoError(t, err)

	require.NoError(t, store.Issue(ctx, secret.HashHex(older), accountID, 15*time.Minute))
	require.NoError(t, store.Issue(ctx, secret.HashHex(newer), accountID, 15*time.Minute))

	_, err = store.Consume(ctx, secret.HashHex(older))
	require.ErrorIs(t, err, apperr.ErrResetTokenInvalid, "requesting a new link invalidates the previous one")

	owner, err := store.Consume(ctx, secret.HashHex(newer))
	require.NoError(t, err)
	require.Equal(t, accountID, owner)
}

func TestResetTokenStoreInvalidationIsIdempotent(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	store := redisadapter.NewResetTokenStore(testRedis, testKeys())

	accountID := uuid.New()
	token, err := secret.NewOpaqueToken(constants.ResetTokenBytes)
	require.NoError(t, err)
	require.NoError(t, store.Issue(ctx, secret.HashHex(token), accountID, 15*time.Minute))

	// A password change through another path drops the pending link.
	require.NoError(t, store.InvalidateForAccount(ctx, accountID))
	_, err = store.Consume(ctx, secret.HashHex(token))
	require.ErrorIs(t, err, apperr.ErrResetTokenInvalid)

	require.NoError(t, store.InvalidateForAccount(ctx, accountID))
	require.NoError(t, store.InvalidateForAccount(ctx, uuid.New()))
}

func TestDeliveryStoreResolvesIdempotently(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	store := redisadapter.NewDeliveryStore(testRedis, testKeys(), clock.System{})

	accountID := uuid.New()
	delivery := entity.NewOtpDelivery(accountID, "owner@example.com",
		constants.TemplateRegistrationVerification, "123456", time.Now().Add(10*time.Minute))
	require.NoError(t, store.Put(ctx, delivery, 10*time.Minute))

	// Notification may retry: resolving twice returns the same content and never
	// consumes the secret.
	for i := 0; i < 2; i++ {
		resolved, err := store.Get(ctx, delivery.DeliveryID)
		require.NoError(t, err)
		require.Equal(t, "owner@example.com", resolved.RecipientEmail)
		require.Equal(t, constants.TemplateRegistrationVerification, resolved.Template)
		require.Equal(t, "123456", resolved.TemplateData["otp"])
		require.Equal(t, accountID, resolved.AccountID)
	}

	_, err := store.Get(ctx, uuid.New())
	require.ErrorIs(t, err, apperr.ErrDeliveryNotFound)
}

func TestDeliveryStoreRefusesAnExpiredDelivery(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	store := redisadapter.NewDeliveryStore(testRedis, testKeys(), clock.System{})

	// The key TTL and the embedded expiry can drift; the embedded value wins so
	// an old secret is never handed out again.
	expired := entity.NewOtpDelivery(uuid.New(), "owner@example.com",
		constants.TemplateRegistrationVerification, "123456", time.Now().Add(-time.Minute))
	require.NoError(t, store.Put(ctx, expired, 10*time.Minute))

	_, err := store.Get(ctx, expired.DeliveryID)
	require.ErrorIs(t, err, apperr.ErrDeliveryExpired)
}

// A scheduled run must be singleton across replicas.
func TestLockerKeepsARunSingleton(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	locker := redisadapter.NewLocker(testRedis, testKeys())

	held, err := locker.Acquire(ctx, "worker", time.Minute)
	require.NoError(t, err)
	require.NotNil(t, held)

	// A second replica skips the run: no lock, and no error either.
	busy, err := locker.Acquire(ctx, "worker", time.Minute)
	require.NoError(t, err)
	require.Nil(t, busy)

	require.NoError(t, held.Release(ctx))

	free, err := locker.Acquire(ctx, "worker", time.Minute)
	require.NoError(t, err)
	require.NotNil(t, free)

	// Releasing a lock this holder no longer owns must not free someone else's.
	require.NoError(t, held.Release(ctx))
	require.Equal(t, int64(1), testRedis.Exists(ctx, "auth:lock:worker").Val())
	require.NoError(t, free.Release(ctx))
}

func TestJwksNotifierPublishesTheSignal(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	notifier := redisadapter.NewJwksNotifier(testRedis, testKeys())

	sub := testRedis.Subscribe(ctx, "auth:jwks:update")
	defer sub.Close()
	_, err := sub.Receive(ctx)
	require.NoError(t, err)

	require.NoError(t, notifier.NotifyKeysChanged(ctx))

	select {
	case message := <-sub.Channel():
		require.Equal(t, "auth:jwks:update", message.Channel)
		// The gateway switches on "type"; a payload without it is ignored there.
		require.JSONEq(t, `{"type":"all"}`, message.Payload)
	case <-time.After(5 * time.Second):
		t.Fatal("the JWKS invalidation signal was not delivered")
	}
}
