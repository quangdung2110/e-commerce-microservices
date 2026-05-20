package pubsub

import (
	"context"
	"encoding/json"
	"sync"

	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"

	"github.com/ecommerce/auth-service/internal/common/keybuilder"
)

type JWKSUpdateHandler interface {
	OnJWKSUpdate(ctx context.Context, message JWKSUpdateMessage)
}

type Subscriber struct {
	redisClient *redis.Client
	logger      *zap.Logger
	handler     JWKSUpdateHandler
	stopCh      chan struct{}
	wg          sync.WaitGroup
}

func NewSubscriber(redisClient *redis.Client, logger *zap.Logger, handler JWKSUpdateHandler) *Subscriber {
	return &Subscriber{
		redisClient: redisClient,
		logger:      logger.With(zap.String("component", "pubsub_subscriber")),
		handler:     handler,
		stopCh:      make(chan struct{}),
	}
}

func (s *Subscriber) Subscribe(ctx context.Context) {
	channel := keybuilder.JWKSUpdateChannel()

	pubsub := s.redisClient.Subscribe(ctx, channel)

	s.logger.Info("Subscribed to Redis Pub/Sub channel",
		zap.String("channel", channel),
	)

	ch := pubsub.Channel()

	s.wg.Add(1)
	go func() {
		defer s.wg.Done()
		defer pubsub.Close()

		for {
			select {
			case <-s.stopCh:
				s.logger.Info("Stopping Redis Pub/Sub subscriber")
				return
			case <-ctx.Done():
				s.logger.Info("Context cancelled, stopping Redis Pub/Sub subscriber")
				return
			case msg := <-ch:
				if msg == nil {
					continue
				}
				s.handleMessage(ctx, msg)
			}
		}
	}()
}

func (s *Subscriber) handleMessage(ctx context.Context, msg *redis.Message) {
	var update JWKSUpdateMessage
	if err := json.Unmarshal([]byte(msg.Payload), &update); err != nil {
		s.logger.Error("Failed to unmarshal JWKS update message",
			zap.String("payload", msg.Payload),
			zap.Error(err),
		)
		return
	}

	s.logger.Info("Received JWKS update notification",
		zap.String("event", update.Event),
		zap.Int64("timestamp", update.Timestamp),
		zap.String("kid", update.KID),
	)

	if s.handler != nil {
		s.handler.OnJWKSUpdate(ctx, update)
	}
}

func (s *Subscriber) Stop() {
	s.logger.Info("Stopping Redis Pub/Sub subscriber")
	close(s.stopCh)
	s.wg.Wait()
	s.logger.Info("Redis Pub/Sub subscriber stopped")
}
