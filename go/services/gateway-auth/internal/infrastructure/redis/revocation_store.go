// Package redis reads the revocation state auth-service owns and listens for
// JWKS invalidation signals. gateway-auth never writes to these keys.
package redis

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"time"

	goredis "github.com/redis/go-redis/v9"
	"go.uber.org/zap"

	"github.com/ecommerce/gateway-auth/internal/common/keybuilder"
	"github.com/ecommerce/gateway-auth/internal/domain"
)

// RevocationStore implements port.RevocationStore.
type RevocationStore struct {
	client  goredis.UniversalClient
	keys    keybuilder.Builder
	timeout time.Duration
}

func NewRevocationStore(client goredis.UniversalClient, keys keybuilder.Builder, timeout time.Duration) *RevocationStore {
	return &RevocationStore{client: client, keys: keys, timeout: timeout}
}

// IsRevoked reads both revocation facts in one round trip. Any read failure —
// including a marker that is not a Unix timestamp — fails closed.
func (s *RevocationStore) IsRevoked(ctx context.Context, subject, jti string, issuedAt time.Time) (bool, error) {
	ctx, cancel := context.WithTimeout(ctx, s.timeout)
	defer cancel()

	pipe := s.client.Pipeline()
	denied := pipe.Exists(ctx, s.keys.Denylist(jti))
	marker := pipe.Get(ctx, s.keys.RevokedBefore(subject))
	if _, err := pipe.Exec(ctx); err != nil && !errors.Is(err, goredis.Nil) {
		return false, fmt.Errorf("%w: redis: %v", domain.ErrDependencyUnavailable, err)
	}

	if denied.Val() > 0 {
		return true, nil
	}

	raw, err := marker.Result()
	if errors.Is(err, goredis.Nil) {
		return false, nil
	}
	if err != nil {
		return false, fmt.Errorf("%w: redis: %v", domain.ErrDependencyUnavailable, err)
	}
	revokedBefore, err := strconv.ParseInt(raw, 10, 64)
	if err != nil {
		return false, fmt.Errorf("%w: malformed revoked_before marker", domain.ErrDependencyUnavailable)
	}
	// Contract: reject when iat is not newer than the marker, so a token minted
	// in the same second as the revocation does not survive it.
	return issuedAt.Unix() <= revokedBefore, nil
}

// Ping checks Redis reachability for the readiness probe.
func (s *RevocationStore) Ping(ctx context.Context) error {
	ctx, cancel := context.WithTimeout(ctx, s.timeout)
	defer cancel()
	return s.client.Ping(ctx).Err()
}

// Invalidator receives JWKS invalidation signals.
type Invalidator interface {
	Invalidate(eventType, kid string)
}

type invalidationEvent struct {
	Type      string `json:"type"`
	Kid       string `json:"kid"`
	Timestamp string `json:"timestamp"`
}

var knownEventTypes = map[string]bool{"key_rotation": true, "key_retirement": true, "all": true}

// SubscribeJWKSInvalidation forwards messages from channel to target until ctx
// ends, reconnecting after a dropped subscription. A message without a known
// `type` is logged and ignored — it is never read as "clear the cache".
func SubscribeJWKSInvalidation(ctx context.Context, client goredis.UniversalClient, channel string, target Invalidator, logger *zap.Logger) {
	for ctx.Err() == nil {
		pubsub := client.Subscribe(ctx, channel)
		for msg := range pubsub.Channel() {
			var event invalidationEvent
			if err := json.Unmarshal([]byte(msg.Payload), &event); err != nil || !knownEventTypes[event.Type] {
				logger.Warn("ignoring malformed JWKS invalidation message", zap.String("channel", channel))
				continue
			}
			target.Invalidate(event.Type, event.Kid)
			logger.Info("JWKS cache invalidated", zap.String("type", event.Type), zap.String("kid", event.Kid))
		}
		_ = pubsub.Close()
		select {
		case <-ctx.Done():
		case <-time.After(time.Second):
			logger.Warn("JWKS invalidation subscription dropped, reconnecting")
		}
	}
}
