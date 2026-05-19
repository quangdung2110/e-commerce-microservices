package redis

import (
	"context"
	"fmt"
	"time"

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
