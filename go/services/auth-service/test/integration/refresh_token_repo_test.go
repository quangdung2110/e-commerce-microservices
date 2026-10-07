//go:build integration

package integration_test

import (
	"context"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"

	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/common/secret"
	"github.com/ecommerce/auth-service/internal/domain/entity"
	"github.com/ecommerce/auth-service/internal/domain/port"
	"github.com/ecommerce/auth-service/internal/infrastructure/postgres"
)

// openSession creates an account with one session and returns both, plus the
// plaintext refresh token.
func openSession(t *testing.T, address string, now time.Time) (*entity.Account, *entity.RefreshToken, string) {
	t.Helper()
	ctx := context.Background()

	accounts := postgres.NewAccountRepository(testPool)
	tokens := postgres.NewRefreshTokenRepository(testPool)

	account := newAccount(t, address, now)
	require.NoError(t, accounts.Create(ctx, account))
	require.NoError(t, account.Activate(now))
	require.NoError(t, accounts.Update(ctx, account))

	plaintext, err := secret.NewOpaqueToken(32)
	require.NoError(t, err)

	session := entity.NewSession(account.ID, secret.Hash(plaintext),
		7*24*time.Hour, 30*24*time.Hour,
		entity.SessionContext{DeviceName: "Laptop", UserAgent: "agent", IPAddress: "203.0.113.7"}, now)
	require.NoError(t, tokens.Create(ctx, session))

	return account, session, plaintext
}

func TestRefreshTokenRoundTrip(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	tokens := postgres.NewRefreshTokenRepository(testPool)
	now := time.Now().UTC().Truncate(time.Microsecond)

	_, session, plaintext := openSession(t, "owner@example.com", now)

	loaded, err := tokens.GetByHash(ctx, secret.Hash(plaintext))
	require.NoError(t, err)
	require.Equal(t, session.ID, loaded.ID)
	require.Equal(t, session.FamilyID, loaded.FamilyID)
	require.Equal(t, "Laptop", loaded.DeviceName)
	require.Equal(t, "203.0.113.7", loaded.IPAddress)
	require.Nil(t, loaded.RevokedAt)

	// An unknown digest is indistinguishable from a revoked one.
	_, err = tokens.GetByHash(ctx, secret.Hash("not-a-token"))
	require.ErrorIs(t, err, apperr.ErrRefreshTokenInvalid)
}

// ADR-002: rotation consumes the old token and installs the successor in the
// same family, and the consumed row is linked to it.
func TestRefreshTokenRotation(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	tokens := postgres.NewRefreshTokenRepository(testPool)
	now := time.Now().UTC().Truncate(time.Microsecond)

	account, session, plaintext := openSession(t, "owner@example.com", now)

	next, err := secret.NewOpaqueToken(32)
	require.NoError(t, err)
	rotated, err := tokens.Rotate(ctx, secret.Hash(plaintext), secret.Hash(next), 7*24*time.Hour,
		entity.SessionContext{UserAgent: "agent-2", IPAddress: "203.0.113.8"}, now.Add(time.Minute))
	require.NoError(t, err)

	require.Equal(t, account.ID, rotated.AccountID)
	require.Equal(t, session.FamilyID, rotated.FamilyID, "rotation stays inside the session")
	require.NotEqual(t, session.ID, rotated.ID)
	require.Equal(t, "203.0.113.8", rotated.IPAddress, "the newest client attributes are recorded")
	// The device name of the session is carried over rather than cleared.
	require.Equal(t, "Laptop", rotated.DeviceName)

	consumed, err := tokens.GetByHash(ctx, secret.Hash(plaintext))
	require.NoError(t, err)
	require.NotNil(t, consumed.RevokedAt)
	require.NotNil(t, consumed.ReplacedBy)
	require.Equal(t, rotated.ID, *consumed.ReplacedBy, "the chain records what replaced the token")
}

// AUTH-BR-002: a replay produces nothing, and it must not damage the token that
// legitimately replaced it.
func TestRefreshTokenReplayIsRejected(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	tokens := postgres.NewRefreshTokenRepository(testPool)
	now := time.Now().UTC()

	_, _, plaintext := openSession(t, "owner@example.com", now)

	next, err := secret.NewOpaqueToken(32)
	require.NoError(t, err)
	_, err = tokens.Rotate(ctx, secret.Hash(plaintext), secret.Hash(next), 7*24*time.Hour,
		entity.SessionContext{}, now.Add(time.Minute))
	require.NoError(t, err)

	replay, err := secret.NewOpaqueToken(32)
	require.NoError(t, err)
	_, err = tokens.Rotate(ctx, secret.Hash(plaintext), secret.Hash(replay), 7*24*time.Hour,
		entity.SessionContext{}, now.Add(2*time.Minute))
	require.ErrorIs(t, err, apperr.ErrRefreshTokenInvalid)

	// The replay created no row at all.
	_, err = tokens.GetByHash(ctx, secret.Hash(replay))
	require.ErrorIs(t, err, apperr.ErrRefreshTokenInvalid)

	// The successor still works.
	third, err := secret.NewOpaqueToken(32)
	require.NoError(t, err)
	_, err = tokens.Rotate(ctx, secret.Hash(next), secret.Hash(third), 7*24*time.Hour,
		entity.SessionContext{}, now.Add(3*time.Minute))
	require.NoError(t, err)
}

// Concurrent refreshes of the same token must produce exactly one new pair. This
// is the invariant the single-statement rotation exists for, and it can only be
// proven against a real database.
func TestConcurrentRotationYieldsOneWinner(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	tokens := postgres.NewRefreshTokenRepository(testPool)
	now := time.Now().UTC()

	_, _, plaintext := openSession(t, "owner@example.com", now)

	const racers = 8
	var (
		wg       sync.WaitGroup
		mu       sync.Mutex
		accepted int
		rejected int
	)

	start := make(chan struct{})
	for i := 0; i < racers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()

			candidate, err := secret.NewOpaqueToken(32)
			if err != nil {
				return
			}
			<-start

			_, rotateErr := tokens.Rotate(ctx, secret.Hash(plaintext), secret.Hash(candidate),
				7*24*time.Hour, entity.SessionContext{}, time.Now().UTC())

			mu.Lock()
			defer mu.Unlock()
			switch {
			case rotateErr == nil:
				accepted++
			case apperr.From(rotateErr).Code == apperr.CodeRefreshTokenInvalid:
				rejected++
			default:
				t.Errorf("unexpected rotation error: %v", rotateErr)
			}
		}()
	}

	close(start)
	wg.Wait()

	require.Equal(t, 1, accepted, "exactly one concurrent refresh may succeed")
	require.Equal(t, racers-1, rejected)

	// The family holds the original token plus its single successor.
	var rows int
	require.NoError(t, testPool.QueryRow(ctx,
		"SELECT count(*) FROM auth.refresh_tokens").Scan(&rows))
	require.Equal(t, 2, rows)
}

// The sliding expiry is extended on every rotation but never beyond the absolute
// expiry of the session.
func TestRotationRespectsTheAbsoluteExpiry(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	tokens := postgres.NewRefreshTokenRepository(testPool)
	now := time.Now().UTC()

	_, session, plaintext := openSession(t, "owner@example.com", now)

	next, err := secret.NewOpaqueToken(32)
	require.NoError(t, err)
	rotated, err := tokens.Rotate(ctx, secret.Hash(plaintext), secret.Hash(next),
		400*24*time.Hour, entity.SessionContext{}, now.Add(time.Minute))
	require.NoError(t, err)

	require.Equal(t, session.AbsoluteExpiresAt.Unix(), rotated.AbsoluteExpiresAt.Unix())
	require.False(t, rotated.ExpiresAt.After(rotated.AbsoluteExpiresAt))
	require.Equal(t, rotated.AbsoluteExpiresAt.Unix(), rotated.ExpiresAt.Unix(),
		"a long sliding window is clamped to the absolute expiry")
}

func TestRotationRejectsAnExpiredToken(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	tokens := postgres.NewRefreshTokenRepository(testPool)
	now := time.Now().UTC()

	_, _, plaintext := openSession(t, "owner@example.com", now)

	next, err := secret.NewOpaqueToken(32)
	require.NoError(t, err)
	_, err = tokens.Rotate(ctx, secret.Hash(plaintext), secret.Hash(next), 7*24*time.Hour,
		entity.SessionContext{}, now.Add(8*24*time.Hour))
	require.ErrorIs(t, err, apperr.ErrRefreshTokenInvalid)
}

func TestSessionListingAndPaging(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	accounts := postgres.NewAccountRepository(testPool)
	tokens := postgres.NewRefreshTokenRepository(testPool)
	now := time.Now().UTC().Truncate(time.Microsecond)

	account, first, _ := openSession(t, "owner@example.com", now)

	// A second and third device, with different sliding windows so the ordering
	// is deterministic.
	for i, ttl := range []time.Duration{6 * 24 * time.Hour, 5 * 24 * time.Hour} {
		plaintext, err := secret.NewOpaqueToken(32)
		require.NoError(t, err)
		session := entity.NewSession(account.ID, secret.Hash(plaintext), ttl, 30*24*time.Hour,
			entity.SessionContext{DeviceName: "Device", IPAddress: "203.0.113.20"}, now.Add(time.Duration(i)*time.Second))
		require.NoError(t, tokens.Create(ctx, session))
	}
	require.NotNil(t, accounts)

	page, cursor, err := tokens.ListSessions(ctx, account.ID, nil, 2)
	require.NoError(t, err)
	require.Len(t, page, 2)
	require.NotNil(t, cursor, "a third session remains")
	require.Equal(t, first.FamilyID, page[0].ID, "the longest-lived session comes first")
	require.Equal(t, "Laptop", page[0].DeviceName)
	require.Equal(t, "203.0.113.7", page[0].IPAddress)

	rest, next, err := tokens.ListSessions(ctx, account.ID, cursor, 2)
	require.NoError(t, err)
	require.Len(t, rest, 1)
	require.Nil(t, next)

	// The cursor must not repeat a session across pages.
	require.NotEqual(t, page[0].ID, rest[0].ID)
	require.NotEqual(t, page[1].ID, rest[0].ID)

	// A revoked session disappears from the listing.
	revoked, err := tokens.RevokeFamily(ctx, account.ID, first.FamilyID, now)
	require.NoError(t, err)
	require.Equal(t, int64(1), revoked)

	remaining, _, err := tokens.ListSessions(ctx, account.ID, nil, 10)
	require.NoError(t, err)
	require.Len(t, remaining, 2)
}

func TestSessionListingCarriesTheSessionStart(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	tokens := postgres.NewRefreshTokenRepository(testPool)
	now := time.Now().UTC().Truncate(time.Microsecond)

	account, session, plaintext := openSession(t, "owner@example.com", now)

	next, err := secret.NewOpaqueToken(32)
	require.NoError(t, err)
	_, err = tokens.Rotate(ctx, secret.Hash(plaintext), secret.Hash(next), 7*24*time.Hour,
		entity.SessionContext{}, now.Add(time.Hour))
	require.NoError(t, err)

	page, _, err := tokens.ListSessions(ctx, account.ID, nil, 10)
	require.NoError(t, err)
	require.Len(t, page, 1)
	require.Equal(t, session.FamilyID, page[0].ID)
	// created_at is when the session started, not when the current token was
	// issued; last_used_at moves with the rotation.
	require.Equal(t, session.CreatedAt.Unix(), page[0].CreatedAt.Unix())
	require.Equal(t, now.Add(time.Hour).Unix(), page[0].LastUsedAt.Unix())
}

func TestFamilyOwnershipAndRevocation(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	tokens := postgres.NewRefreshTokenRepository(testPool)
	now := time.Now().UTC()

	account, session, _ := openSession(t, "owner@example.com", now)
	other, otherSession, _ := openSession(t, "other@example.com", now)

	found, err := tokens.FamilyExists(ctx, account.ID, session.FamilyID)
	require.NoError(t, err)
	require.True(t, found)

	// A family of another account must not be visible.
	found, err = tokens.FamilyExists(ctx, account.ID, otherSession.FamilyID)
	require.NoError(t, err)
	require.False(t, found)
	require.NotEqual(t, account.ID, other.ID)

	// Revoking twice is idempotent: the second pass simply finds nothing active.
	revoked, err := tokens.RevokeFamily(ctx, account.ID, session.FamilyID, now)
	require.NoError(t, err)
	require.Equal(t, int64(1), revoked)

	revoked, err = tokens.RevokeFamily(ctx, account.ID, session.FamilyID, now)
	require.NoError(t, err)
	require.Zero(t, revoked)

	// The family is still known, which is what keeps revocation idempotent at
	// the API level rather than reporting 404.
	found, err = tokens.FamilyExists(ctx, account.ID, session.FamilyID)
	require.NoError(t, err)
	require.True(t, found)

	// The other account is untouched.
	all, err := tokens.RevokeAllForAccount(ctx, other.ID, now)
	require.NoError(t, err)
	require.Equal(t, int64(1), all)
}

func TestCleanupRemovesOnlyStaleRows(t *testing.T) {
	resetState(t)
	ctx := context.Background()
	tokens := postgres.NewRefreshTokenRepository(testPool)
	now := time.Now().UTC()

	account, session, plaintext := openSession(t, "owner@example.com", now)

	// Rotate once so the family holds one revoked row and one active row.
	next, err := secret.NewOpaqueToken(32)
	require.NoError(t, err)
	_, err = tokens.Rotate(ctx, secret.Hash(plaintext), secret.Hash(next), 7*24*time.Hour,
		entity.SessionContext{}, now)
	require.NoError(t, err)

	// Nothing is stale yet.
	deleted, err := tokens.DeleteExpiredBefore(ctx, now.Add(-time.Hour), 100)
	require.NoError(t, err)
	require.Zero(t, deleted)

	deleted, err = tokens.DeleteExpiredBefore(ctx, now.Add(time.Hour), 100)
	require.NoError(t, err)
	require.Equal(t, int64(1), deleted, "only the revoked row is past retention")

	page, _, err := tokens.ListSessions(ctx, account.ID, nil, 10)
	require.NoError(t, err)
	require.Len(t, page, 1)
	require.Equal(t, session.FamilyID, page[0].ID)
}

func TestSessionCursorRoundTrip(t *testing.T) {
	original := port.SessionCursor{
		ExpiresAt: time.Now().UTC().Truncate(time.Nanosecond),
		FamilyID:  uuid.New(),
	}

	decoded, err := port.DecodeSessionCursor(original.Encode())
	require.NoError(t, err)
	require.Equal(t, original.FamilyID, decoded.FamilyID)
	require.True(t, original.ExpiresAt.Equal(decoded.ExpiresAt))

	_, err = port.DecodeSessionCursor("not-a-cursor")
	require.Error(t, err)
}
