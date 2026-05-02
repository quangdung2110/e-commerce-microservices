package redis_pubsub

import (
	"context"
	"encoding/json"
	"time"

	"github.com/redis/go-redis/v9"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain"
)

// Publisher handles Redis Pub/Sub publishing for JWKS updates
type Publisher struct {
	client  *redis.Client
	channel string
}

// JwksUpdateMessage represents a JWKS update notification
type JwksUpdateMessage struct {
	Event     string `json:"event"`      // "JWKS_UPDATED"
	Timestamp int64  `json:"timestamp"`  // Unix timestamp
	Kid       string `json:"kid,omitempty"` // New key ID (optional)
	Reason    string `json:"reason,omitempty"` // Reason for update
}

// NewPublisher creates a new Redis Pub/Sub publisher
func NewPublisher(client *redis.Client) *Publisher {
	return &Publisher{
		client:  client,
		channel: domain.RedisChannelJwksUpdate,
	}
}

// PublishJwksUpdate publishes a JWKS update notification
func (p *Publisher) PublishJwksUpdate(ctx context.Context, kid string, reason string) error {
	msg := JwksUpdateMessage{
		Event:     "JWKS_UPDATED",
		Timestamp: time.Now().Unix(),
		Kid:       kid,
		Reason:    reason,
	}

	data, err := json.Marshal(msg)
	if err != nil {
		return err
	}

	return p.client.Publish(ctx, p.channel, data).Err()
}

// PublishJwksInvalidation publishes a JWKS cache invalidation notification
func (p *Publisher) PublishJwksInvalidation(ctx context.Context, kid string) error {
	msg := JwksUpdateMessage{
		Event:     "JWKS_INVALIDATED",
		Timestamp: time.Now().Unix(),
		Kid:       kid,
	}

	data, err := json.Marshal(msg)
	if err != nil {
		return err
	}

	return p.client.Publish(ctx, p.channel, data).Err()
}

// GetChannel returns the channel name
func (p *Publisher) GetChannel() string {
	return p.channel
}
