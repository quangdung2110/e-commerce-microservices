package redis

import (
	"context"
	"strconv"
	"time"

	"github.com/google/uuid"
	"github.com/redis/go-redis/v9"

	"github.com/ecommerce/auth-service/internal/common/keybuilder"
	"github.com/ecommerce/auth-service/internal/domain/port"
)

var _ port.RevocationStore = (*RevocationStore)(nil)

// RevocationStore writes the two signals gateway-auth reads on every protected
// request. Auth owns the write side of the auth:revocation namespace; the
// gateway has read-only access to it.
type RevocationStore struct {
	store
}

// NewRevocationStore wires the revocation adapter.
func NewRevocationStore(client *redis.Client, keys *keybuilder.Builder) *RevocationStore {
	return &RevocationStore{store{client: client, keys: keys}}
}

// DenyAccessToken blocks one access token by its jti. The TTL must not outlive
// the token itself, so the caller passes the maximum access-token lifetime.
func (s *RevocationStore) DenyAccessToken(ctx context.Context, jti string, ttl time.Duration) error {
	if ttl <= 0 {
		return nil
	}
	return mapError(s.client.Set(ctx, s.keys.Denylist(jti), "1", ttl).Err())
}

// RevokeIssuedBefore stores the user-wide revocation marker as Unix seconds in
// UTC. gateway-auth rejects any JWT whose iat is not newer than this value, so
// every token issued up to now stops working immediately.
func (s *RevocationStore) RevokeIssuedBefore(ctx context.Context, accountID uuid.UUID, issuedBefore time.Time, ttl time.Duration) error {
	if ttl <= 0 {
		return nil
	}
	value := strconv.FormatInt(issuedBefore.UTC().Unix(), 10)
	return mapError(s.client.Set(ctx, s.keys.RevokedBefore(accountID.String()), value, ttl).Err())
}
