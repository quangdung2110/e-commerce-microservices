package pubsub

import (
	"context"
	"encoding/json"
	"fmt"
	"time"

	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/common/keybuilder"
	"github.com/ecommerce/auth-service/internal/infrastructure/redis"
)

const JWKSUpdateChannelName = "auth:jwks:update"

type JWKSUpdateMessage struct {
	Event     string `json:"event"`
	Timestamp int64  `json:"timestamp"`
	KID       string `json:"kid,omitempty"`
}

type Publisher struct {
	redisClient *redis.Client
	logger      *zap.Logger
}

func NewPublisher(redisClient *redis.Client, logger *zap.Logger) *Publisher {
	return &Publisher{
		redisClient: redisClient,
		logger:      logger.With(zap.String("component", "pubsub_publisher")),
	}
}

func (p *Publisher) PublishJWKSUpdate(ctx context.Context) error {
	return p.PublishJWKSUpdateWithKID(ctx, "")
}

func (p *Publisher) PublishJWKSUpdateWithKID(ctx context.Context, kid string) error {
	channel := keybuilder.JWKSUpdateChannel()

	message := JWKSUpdateMessage{
		Event:     "JWKS_UPDATED",
		Timestamp: time.Now().Unix(),
		KID:       kid,
	}

	payload, err := json.Marshal(message)
	if err != nil {
		return fmt.Errorf("failed to marshal JWKS update message: %w", err)
	}

	result, err := p.redisClient.Publish(ctx, channel, string(payload)).Result()
	if err != nil {
		return fmt.Errorf("failed to publish JWKS update to channel %s: %w", channel, err)
	}

	p.logger.Info("JWKS update published",
		zap.String("channel", channel),
		zap.Int64("subscribers_count", result),
		zap.String("kid", kid),
	)

	return nil
}

func (p *Publisher) Publish(ctx context.Context, channel string, message interface{}) error {
	payload, err := json.Marshal(message)
	if err != nil {
		return fmt.Errorf("failed to marshal message: %w", err)
	}

	result, err := p.redisClient.Publish(ctx, channel, string(payload)).Result()
	if err != nil {
		return fmt.Errorf("failed to publish message to channel %s: %w", channel, err)
	}

	p.logger.Debug("Message published",
		zap.String("channel", channel),
		zap.Int64("subscribers_count", result),
	)

	return nil
}
