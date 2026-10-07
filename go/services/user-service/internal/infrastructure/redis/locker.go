package redis

import (
	"context"
	"time"

	"github.com/google/uuid"
	"github.com/redis/go-redis/v9"

	"github.com/ecommerce/user-service/internal/common/keybuilder"
	"github.com/ecommerce/user-service/internal/domain/port"
)

var _ port.Locker = (*Locker)(nil)

// releaseLockScript deletes the key only when this holder still owns it, so a
// run that overran its TTL cannot release the lock a different replica took.
var releaseLockScript = redis.NewScript(`
if redis.call('GET', KEYS[1]) == ARGV[1] then
	return redis.call('DEL', KEYS[1])
end
return 0
`)

// Locker keeps a scheduled run singleton across replicas, which is what makes
// the retention purge claim each record once and the reconciliation sweep run
// only once a day service-wide (docs/user/operations.md §5).
type Locker struct {
	store
}

// NewLocker wires the distributed lock adapter.
func NewLocker(client *redis.Client, keys *keybuilder.Builder) *Locker {
	return &Locker{store{client: client, keys: keys}}
}

// Acquire takes the lock. It returns a nil lock, without an error, when another
// replica already holds it: a skipped run is normal, not a failure.
func (s *Locker) Acquire(ctx context.Context, name string, ttl time.Duration) (port.Lock, error) {
	key := s.keys.WorkerLock(name)
	token := uuid.NewString()

	acquired, err := s.client.SetNX(ctx, key, token, ttl).Result()
	if err != nil {
		return nil, mapError(err)
	}
	if !acquired {
		return nil, nil
	}
	return &redisLock{client: s.client, key: key, token: token}, nil
}

type redisLock struct {
	client *redis.Client
	key    string
	token  string
}

// Release gives the lock back. It is safe to call after the TTL elapsed.
func (l *redisLock) Release(ctx context.Context) error {
	return mapError(releaseLockScript.Run(ctx, l.client, []string{l.key}, l.token).Err())
}
