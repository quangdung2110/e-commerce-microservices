package redis

import (
	"context"
	"strconv"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	goredis "github.com/redis/go-redis/v9"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/ecommerce/gateway-auth/internal/common/keybuilder"
	"github.com/ecommerce/gateway-auth/internal/domain"
)

const subject = "0b6f5e2e-3f7a-4c4e-9a53-3c1a0f2a9e11"

func newStore(t *testing.T) (*RevocationStore, *miniredis.Miniredis) {
	t.Helper()
	server := miniredis.RunT(t)
	client := goredis.NewClient(&goredis.Options{Addr: server.Addr()})
	t.Cleanup(func() { _ = client.Close() })
	return NewRevocationStore(client, keybuilder.New("auth:revocation"), time.Second), server
}

func TestIsRevoked(t *testing.T) {
	store, server := newStore(t)
	issuedAt := time.Unix(1_800_000_000, 0)
	ctx := context.Background()

	revoked, err := store.IsRevoked(ctx, subject, "jti-1", issuedAt)
	require.NoError(t, err)
	assert.False(t, revoked, "no revocation state")

	// Keys written by auth-service (same format as its keybuilder).
	require.NoError(t, server.Set("auth:revocation:denylist:jti-1", "1"))
	revoked, err = store.IsRevoked(ctx, subject, "jti-1", issuedAt)
	require.NoError(t, err)
	assert.True(t, revoked, "denylisted jti")

	marker := "auth:revocation:user:" + subject + ":revoked_before"
	require.NoError(t, server.Set(marker, strconv.FormatInt(issuedAt.Unix(), 10)))
	revoked, err = store.IsRevoked(ctx, subject, "jti-2", issuedAt)
	require.NoError(t, err)
	assert.True(t, revoked, "iat equal to the marker is revoked")

	revoked, err = store.IsRevoked(ctx, subject, "jti-2", issuedAt.Add(time.Second))
	require.NoError(t, err)
	assert.False(t, revoked, "token issued after the marker survives")
}

func TestIsRevoked_FailsClosed(t *testing.T) {
	store, server := newStore(t)

	require.NoError(t, server.Set("auth:revocation:user:"+subject+":revoked_before", "not-a-number"))
	_, err := store.IsRevoked(context.Background(), subject, "jti", time.Now())
	assert.ErrorIs(t, err, domain.ErrDependencyUnavailable)

	server.Close()
	_, err = store.IsRevoked(context.Background(), subject, "jti", time.Now())
	assert.ErrorIs(t, err, domain.ErrDependencyUnavailable)
}
