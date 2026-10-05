package redis

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/redis/go-redis/v9"

	apperr "github.com/ecommerce/notification-service/internal/common/errors"
	"github.com/ecommerce/notification-service/internal/common/pii"
	"github.com/ecommerce/notification-service/internal/domain/entity"
)

// contactRecord is the stored shape. pii.Email marshals redacted on purpose,
// so the raw address is copied in explicitly here and nowhere else. The value
// is PII: it lives at most the TTL (15 min, 11-privacy.md §2), never in
// Postgres, never in a log line.
type contactRecord struct {
	UserID        string `json:"user_id"`
	Email         string `json:"email"`
	EmailVerified bool   `json:"email_verified"`
	Locale        string `json:"locale"`
}

// ContactCache holds contact:{user_id} for the Auth contact resolver. It
// satisfies the resolver's ContactCache interface structurally (Get / Set /
// Delete) without importing that package.
type ContactCache struct {
	client *redis.Client
	keys   Keys
}

// NewContactCache wires the cache.
func NewContactCache(client *redis.Client, keys Keys) *ContactCache {
	return &ContactCache{client: client, keys: keys}
}

// Get returns the cached contact or errors.ErrCacheMiss when absent. Any
// other Redis error is returned so the resolver can decide whether to call
// Auth directly (a cache outage must not fail a delivery).
func (c *ContactCache) Get(ctx context.Context, userID uuid.UUID) (*entity.Contact, error) {
	raw, err := c.client.Get(ctx, c.keys.Contact(userID)).Bytes()
	if err != nil {
		if errors.Is(err, redis.Nil) {
			return nil, apperr.ErrCacheMiss
		}
		return nil, fmt.Errorf("redis: contact get: %w", err)
	}
	var rec contactRecord
	if err := json.Unmarshal(raw, &rec); err != nil {
		// A corrupt entry is treated as a miss and dropped so the next Set
		// repairs it; the payload is never included in the error.
		_ = c.client.Del(ctx, c.keys.Contact(userID)).Err()
		return nil, apperr.ErrCacheMiss
	}
	if rec.Email == "" {
		return nil, apperr.ErrCacheMiss
	}
	return &entity.Contact{
		UserID:        userID,
		Email:         pii.MustEmail(rec.Email),
		EmailVerified: rec.EmailVerified,
		Locale:        rec.Locale,
	}, nil
}

// Set stores the contact for ttl (contact.cache_ttl). A non-positive ttl is
// refused: an address must never sit in Redis without an expiry.
func (c *ContactCache) Set(ctx context.Context, userID uuid.UUID, contact *entity.Contact, ttl time.Duration) error {
	if contact == nil || contact.Email.IsZero() {
		return errors.New("redis: contact set: empty contact")
	}
	if ttl <= 0 {
		return errors.New("redis: contact set: ttl must be positive")
	}
	payload, err := json.Marshal(contactRecord{
		UserID:        userID.String(),
		Email:         contact.Email.Raw(),
		EmailVerified: contact.EmailVerified,
		Locale:        contact.Locale,
	})
	if err != nil {
		return fmt.Errorf("redis: contact marshal: %w", err)
	}
	if err := c.client.Set(ctx, c.keys.Contact(userID), payload, ttl).Err(); err != nil {
		return fmt.Errorf("redis: contact set: %w", err)
	}
	return nil
}

// Delete drops the entry (user.deleted, or after Auth reports a changed address).
func (c *ContactCache) Delete(ctx context.Context, userID uuid.UUID) error {
	if err := c.client.Del(ctx, c.keys.Contact(userID)).Err(); err != nil {
		return fmt.Errorf("redis: contact delete: %w", err)
	}
	return nil
}
