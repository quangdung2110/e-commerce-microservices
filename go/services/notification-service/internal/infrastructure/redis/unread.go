package redis

import (
	"context"
	"errors"
	"fmt"

	"github.com/google/uuid"
	"github.com/redis/go-redis/v9"

	apperr "github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/domain/port"
)

var _ port.UnreadCounter = (*UnreadCounter)(nil)

// unreadAddScript adjusts the counter without ever going below zero. When the
// key is absent it does nothing (returns -1): incrementing from an unknown
// base would produce a confident wrong number, whereas leaving the key absent
// makes the next Get miss and recompute from inbox_item (09-data-model §7).
var unreadAddScript = redis.NewScript(`
local current = redis.call('GET', KEYS[1])
if not current then
	return -1
end
local next = tonumber(current) + tonumber(ARGV[1])
if next < 0 then
	next = 0
end
redis.call('SET', KEYS[1], next)
return next
`)

// UnreadCounter is the inbox:unread:{user_id} counter. It has no TTL: the
// value is a cache of a count the API can recompute, and user.deleted deletes
// it explicitly (11-privacy.md §2).
type UnreadCounter struct {
	client *redis.Client
	keys   Keys
}

// NewUnreadCounter wires the counter.
func NewUnreadCounter(client *redis.Client, keys Keys) *UnreadCounter {
	return &UnreadCounter{client: client, keys: keys}
}

// Get returns the cached count or errors.ErrCacheMiss when absent.
func (u *UnreadCounter) Get(ctx context.Context, userID uuid.UUID) (int64, error) {
	n, err := u.client.Get(ctx, u.keys.Unread(userID)).Int64()
	if err != nil {
		if errors.Is(err, redis.Nil) {
			return 0, apperr.ErrCacheMiss
		}
		return 0, fmt.Errorf("redis: unread get: %w", err)
	}
	return n, nil
}

// Set overwrites the count (after a recompute). Negative input is clamped.
func (u *UnreadCounter) Set(ctx context.Context, userID uuid.UUID, count int64) error {
	if count < 0 {
		count = 0
	}
	if err := u.client.Set(ctx, u.keys.Unread(userID), count, 0).Err(); err != nil {
		return fmt.Errorf("redis: unread set: %w", err)
	}
	return nil
}

// Add applies delta (positive after an inbox insert, negative on mark-read)
// to an existing counter; the result never goes below zero and an absent key
// stays absent.
func (u *UnreadCounter) Add(ctx context.Context, userID uuid.UUID, delta int64) error {
	if err := unreadAddScript.Run(ctx, u.client, []string{u.keys.Unread(userID)}, delta).Err(); err != nil {
		return fmt.Errorf("redis: unread add: %w", err)
	}
	return nil
}

// Delete drops the counter (user.deleted, or a suspected drift).
func (u *UnreadCounter) Delete(ctx context.Context, userID uuid.UUID) error {
	if err := u.client.Del(ctx, u.keys.Unread(userID)).Err(); err != nil {
		return fmt.Errorf("redis: unread delete: %w", err)
	}
	return nil
}
