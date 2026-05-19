package redis

import (
	"context"
	"fmt"
	"strconv"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/ecommerce/auth-service/internal/common/keybuilder"
)

type Denylist struct {
	client *Client
}

func NewDenylist(client *Client) *Denylist {
	return &Denylist{client: client}
}

func (d *Denylist) AddToDenylist(ctx context.Context, jti string, remainingTTL time.Duration) error {
	if remainingTTL <= 0 {
		return nil
	}

	key := keybuilder.Denylist(jti)
	_, err := d.client.SetEX(ctx, key, "1", remainingTTL).Result()
	if err != nil {
		return fmt.Errorf("failed to add token to denylist: %w", err)
	}

	return nil
}

func (d *Denylist) IsDenylisted(ctx context.Context, jti string) (bool, error) {
	key := keybuilder.Denylist(jti)

	exists, err := d.client.Exists(ctx, key).Result()
	if err != nil {
		return false, fmt.Errorf("failed to check denylist: %w", err)
	}

	return exists > 0, nil
}

func (d *Denylist) SetRevokedBefore(ctx context.Context, userID string, timestamp time.Time) error {
	key := keybuilder.RevokedBefore(userID)
	ttl := 16 * time.Minute
	timestampStr := strconv.FormatInt(timestamp.Unix(), 10)

	_, err := d.client.SetEX(ctx, key, timestampStr, ttl).Result()
	if err != nil {
		return fmt.Errorf("failed to set revoked_before: %w", err)
	}

	return nil
}

func (d *Denylist) GetRevokedBefore(ctx context.Context, userID string) (time.Time, error) {
	key := keybuilder.RevokedBefore(userID)

	val, err := d.client.Get(ctx, key).Result()
	if err != nil {
		if err == redis.Nil {
			return time.Time{}, nil
		}
		return time.Time{}, fmt.Errorf("failed to get revoked_before: %w", err)
	}

	timestamp, err := strconv.ParseInt(val, 10, 64)
	if err != nil {
		return time.Time{}, fmt.Errorf("failed to parse timestamp: %w", err)
	}

	return time.Unix(timestamp, 0), nil
}

func (d *Denylist) BatchAddToDenylist(ctx context.Context, jtis []string, ttl time.Duration) error {
	if len(jtis) == 0 {
		return nil
	}

	pipe := d.client.Pipeline()

	for _, jti := range jtis {
		if ttl > 0 {
			key := keybuilder.Denylist(jti)
			pipe.SetEx(ctx, key, "1", ttl)
		}
	}

	_, err := pipe.Exec(ctx)
	if err != nil {
		return fmt.Errorf("failed to batch add to denylist: %w", err)
	}

	return nil
}
