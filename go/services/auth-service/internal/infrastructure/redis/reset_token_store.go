package redis

import (
	"context"
	"errors"
	"time"

	"github.com/google/uuid"
	"github.com/redis/go-redis/v9"

	apperr "github.com/ecommerce/auth-service/internal/common/errors"
	"github.com/ecommerce/auth-service/internal/common/keybuilder"
	"github.com/ecommerce/auth-service/internal/domain/port"
)

var _ port.ResetTokenStore = (*ResetTokenStore)(nil)

// issueResetScript stores the token and its reverse index together, dropping any
// token issued earlier for the same account so only the newest link works.
var issueResetScript = redis.NewScript(`
local previous = redis.call('GET', KEYS[2])
if previous then
	redis.call('DEL', ARGV[3] .. previous)
end
redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
redis.call('SET', KEYS[2], ARGV[4], 'PX', ARGV[2])
return 1
`)

// consumeResetScript burns the token and its reverse index atomically, so the
// same link can never complete two resets.
var consumeResetScript = redis.NewScript(`
local account = redis.call('GET', KEYS[1])
if not account then
	return nil
end
redis.call('DEL', KEYS[1])
redis.call('DEL', ARGV[1] .. account)
return account
`)

// ResetTokenStore maps the digest of an opaque reset token to an account.
type ResetTokenStore struct {
	store
}

// NewResetTokenStore wires the reset-token adapter.
func NewResetTokenStore(client *redis.Client, keys *keybuilder.Builder) *ResetTokenStore {
	return &ResetTokenStore{store{client: client, keys: keys}}
}

// resetAccountKey is the reverse index used to invalidate a pending token when
// the password changes through another path.
func (s *ResetTokenStore) resetAccountKey(accountID string) string {
	return s.keys.ResetToken("account:" + accountID)
}

// resetTokenPrefix is the prefix a Lua script needs to rebuild a token key from
// a digest it just read.
func (s *ResetTokenStore) resetTokenPrefix() string {
	return s.keys.ResetToken("")
}

// Issue stores the digest with its TTL.
func (s *ResetTokenStore) Issue(ctx context.Context, tokenHash string, accountID uuid.UUID, ttl time.Duration) error {
	keys := []string{
		s.keys.ResetToken(tokenHash),
		s.resetAccountKey(accountID.String()),
	}
	args := []any{
		accountID.String(),
		ttl.Milliseconds(),
		s.resetTokenPrefix(),
		tokenHash,
	}
	return mapError(issueResetScript.Run(ctx, s.client, keys, args...).Err())
}

// Consume burns the token and returns its account.
func (s *ResetTokenStore) Consume(ctx context.Context, tokenHash string) (uuid.UUID, error) {
	raw, err := consumeResetScript.Run(ctx, s.client,
		[]string{s.keys.ResetToken(tokenHash)}, s.keys.ResetToken("account:")).Text()
	if err != nil {
		if errors.Is(err, redis.Nil) {
			return uuid.Nil, apperr.ErrResetTokenInvalid
		}
		return uuid.Nil, mapError(err)
	}

	accountID, err := uuid.Parse(raw)
	if err != nil {
		return uuid.Nil, apperr.ErrResetTokenInvalid.WithCause(err)
	}
	return accountID, nil
}

// InvalidateForAccount drops a pending reset token after the password changed
// through another path, so an old link cannot be used afterwards.
func (s *ResetTokenStore) InvalidateForAccount(ctx context.Context, accountID uuid.UUID) error {
	accountKey := s.resetAccountKey(accountID.String())

	tokenHash, err := s.client.GetDel(ctx, accountKey).Result()
	if err != nil {
		if errors.Is(err, redis.Nil) {
			return nil
		}
		return mapError(err)
	}
	return mapError(s.client.Del(ctx, s.keys.ResetToken(tokenHash)).Err())
}
