package redis

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/ecommerce/auth-service/internal/common/keybuilder"
)

const GracePeriodTTL = 10 * time.Second

type TokenPair struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	CreatedAt    int64  `json:"created_at"`
}

type GracePeriod struct {
	client *Client
}

func NewGracePeriod(client *Client) *GracePeriod {
	return &GracePeriod{client: client}
}

func (g *GracePeriod) Set(ctx context.Context, oldTokenHash string, accessToken, refreshToken string) error {
	key := keybuilder.GracePeriod(oldTokenHash)

	pair := TokenPair{
		AccessToken:  accessToken,
		RefreshToken: refreshToken,
		CreatedAt:    time.Now().Unix(),
	}

	data, err := json.Marshal(pair)
	if err != nil {
		return fmt.Errorf("failed to marshal token pair: %w", err)
	}

	_, err = g.client.Set(ctx, key, string(data), GracePeriodTTL).Result()
	if err != nil {
		return fmt.Errorf("failed to set grace period cache: %w", err)
	}

	return nil
}

func (g *GracePeriod) Get(ctx context.Context, oldTokenHash string) (string, string, bool, error) {
	key := keybuilder.GracePeriod(oldTokenHash)

	data, err := g.client.Get(ctx, key).Result()
	if err != nil {
		if err == redis.Nil {
			return "", "", false, nil
		}
		return "", "", false, fmt.Errorf("failed to get grace period cache: %w", err)
	}

	var pair TokenPair
	if err := json.Unmarshal([]byte(data), &pair); err != nil {
		return "", "", false, fmt.Errorf("failed to unmarshal token pair: %w", err)
	}

	return pair.AccessToken, pair.RefreshToken, true, nil
}

func (g *GracePeriod) Delete(ctx context.Context, oldTokenHash string) error {
	key := keybuilder.GracePeriod(oldTokenHash)

	_, err := g.client.Del(ctx, key).Result()
	if err != nil {
		return fmt.Errorf("failed to delete grace period cache: %w", err)
	}

	return nil
}

func (g *GracePeriod) SetWithResult(ctx context.Context, oldTokenHash string, accessToken, refreshToken string) (*TokenPair, error) {
	pair := &TokenPair{
		AccessToken:  accessToken,
		RefreshToken: refreshToken,
		CreatedAt:    time.Now().Unix(),
	}

	data, err := json.Marshal(pair)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal token pair: %w", err)
	}

	key := keybuilder.GracePeriod(oldTokenHash)

	success, err := g.client.SetNX(ctx, key, string(data), GracePeriodTTL).Result()
	if err != nil {
		return nil, fmt.Errorf("failed to set grace period cache: %w", err)
	}

	if !success {
		existingData, err := g.client.Get(ctx, key).Result()
		if err != nil && err != redis.Nil {
			return nil, fmt.Errorf("failed to get existing grace period: %w", err)
		}
		if existingData != "" {
			var existingPair TokenPair
			if err := json.Unmarshal([]byte(existingData), &existingPair); err == nil {
				return &existingPair, nil
			}
		}
		return nil, nil
	}

	return pair, nil
}
