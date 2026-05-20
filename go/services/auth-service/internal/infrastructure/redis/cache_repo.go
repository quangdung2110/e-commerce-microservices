package redis

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/ecommerce/auth-service/internal/common/keybuilder"
	"github.com/ecommerce/auth-service/internal/domain/port"
)

type CacheRepository struct {
	client *Client
}

func NewCacheRepository(client *Client) *CacheRepository {
	return &CacheRepository{client: client}
}

var _ port.Cache = (*CacheRepository)(nil)

func (r *CacheRepository) Get(ctx context.Context, key string) (string, error) {
	val, err := r.client.Get(ctx, key).Result()
	if err != nil {
		return "", fmt.Errorf("failed to get from cache: %w", err)
	}
	return val, nil
}

func (r *CacheRepository) Set(ctx context.Context, key string, value interface{}, ttl time.Duration) error {
	err := r.client.Set(ctx, key, value, ttl).Err()
	if err != nil {
		return fmt.Errorf("failed to set cache: %w", err)
	}
	return nil
}

func (r *CacheRepository) Delete(ctx context.Context, key string) error {
	err := r.client.Del(ctx, key).Err()
	if err != nil {
		return fmt.Errorf("failed to delete from cache: %w", err)
	}
	return nil
}

func (r *CacheRepository) Exists(ctx context.Context, key string) (bool, error) {
	result, err := r.client.Exists(ctx, key).Result()
	if err != nil {
		return false, fmt.Errorf("failed to check existence: %w", err)
	}
	return result > 0, nil
}

// TokenCache implementation
func (r *CacheRepository) AddToDenylist(ctx context.Context, jti string, remainingTTL time.Duration) error {
	key := keybuilder.Denylist(jti)
	err := r.client.Set(ctx, key, "1", remainingTTL).Err()
	if err != nil {
		return fmt.Errorf("failed to add to denylist: %w", err)
	}
	return nil
}

func (r *CacheRepository) IsDenylisted(ctx context.Context, jti string) (bool, error) {
	key := keybuilder.Denylist(jti)
	result, err := r.client.Exists(ctx, key).Result()
	if err != nil {
		return false, fmt.Errorf("failed to check denylist: %w", err)
	}
	return result > 0, nil
}

func (r *CacheRepository) SetRevokedBefore(ctx context.Context, userID string, timestamp time.Time) error {
	key := keybuilder.RevokedBefore(userID)
	err := r.client.Set(ctx, key, timestamp.Format(time.RFC3339), 0).Err()
	if err != nil {
		return fmt.Errorf("failed to set revoked_before: %w", err)
	}
	return nil
}

func (r *CacheRepository) GetRevokedBefore(ctx context.Context, userID string) (time.Time, error) {
	key := keybuilder.RevokedBefore(userID)
	result, err := r.client.Get(ctx, key).Result()
	if err != nil {
		if err == redis.Nil {
			return time.Time{}, nil
		}
		return time.Time{}, fmt.Errorf("failed to get revoked_before: %w", err)
	}
	return time.Parse(time.RFC3339, result)
}

// GracePeriodCache implementation
func (r *CacheRepository) GracePeriodSet(ctx context.Context, oldTokenHash string, accessToken, refreshToken string) error {
	key := keybuilder.GracePeriod(oldTokenHash)

	pair := struct {
		AccessToken  string `json:"access_token"`
		RefreshToken string `json:"refresh_token"`
	}{
		AccessToken:  accessToken,
		RefreshToken: refreshToken,
	}

	data, err := json.Marshal(pair)
	if err != nil {
		return fmt.Errorf("failed to marshal token pair: %w", err)
	}

	gracePeriodTTL := 10 * time.Second
	err = r.client.Set(ctx, key, string(data), gracePeriodTTL).Err()
	if err != nil {
		return fmt.Errorf("failed to set grace period: %w", err)
	}
	return nil
}

func (r *CacheRepository) GracePeriodGet(ctx context.Context, oldTokenHash string) (string, string, bool, error) {
	key := keybuilder.GracePeriod(oldTokenHash)

	data, err := r.client.Get(ctx, key).Result()
	if err != nil {
		if err == redis.Nil {
			return "", "", false, nil
		}
		return "", "", false, fmt.Errorf("failed to get grace period: %w", err)
	}

	var pair struct {
		AccessToken  string `json:"access_token"`
		RefreshToken string `json:"refresh_token"`
	}
	if err := json.Unmarshal([]byte(data), &pair); err != nil {
		return "", "", false, fmt.Errorf("failed to unmarshal token pair: %w", err)
	}

	return pair.AccessToken, pair.RefreshToken, true, nil
}

func (r *CacheRepository) GracePeriodDelete(ctx context.Context, oldTokenHash string) error {
	key := keybuilder.GracePeriod(oldTokenHash)
	err := r.client.Del(ctx, key).Err()
	if err != nil {
		return fmt.Errorf("failed to delete grace period: %w", err)
	}
	return nil
}
