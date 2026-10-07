package redis

import (
	"context"

	"github.com/redis/go-redis/v9"

	"github.com/ecommerce/auth-service/internal/common/keybuilder"
	"github.com/ecommerce/auth-service/internal/domain/port"
)

var _ port.JwksNotifier = (*JwksNotifier)(nil)

// jwksUpdatedSignal is the payload published on the invalidation channel. The
// shape is pinned by docs/gateway/runtime-contract.md section 6: the gateway
// switches on "type", and an empty "kid" means clear the whole cache. It is
// deliberately tiny: it carries no key material and is not a durable event.
const jwksUpdatedSignal = `{"type":"all"}`

// JwksNotifier tells gateway caches that the key set changed.
//
// This is an optimization only. A gateway must still refresh JWKS when it meets
// an unknown kid and when its cache entry expires, so a missed signal delays a
// refresh but never breaks verification.
type JwksNotifier struct {
	store
}

// NewJwksNotifier wires the Pub/Sub invalidation adapter.
func NewJwksNotifier(client *redis.Client, keys *keybuilder.Builder) *JwksNotifier {
	return &JwksNotifier{store{client: client, keys: keys}}
}

// NotifyKeysChanged publishes the invalidation signal.
func (s *JwksNotifier) NotifyKeysChanged(ctx context.Context) error {
	return mapError(s.client.Publish(ctx, s.keys.JwksChannel(), jwksUpdatedSignal).Err())
}
