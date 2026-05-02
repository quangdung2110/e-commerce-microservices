package redis_pubsub

import (
	"context"
	"encoding/json"
	"sync"

	"github.com/redis/go-redis/v9"
	"github.com/yourusername/spring-event-sourcing/go/services/auth-service/internal/domain"
)

// Subscriber handles Redis Pub/Sub subscriptions for JWKS updates
type Subscriber struct {
	client  *redis.Client
	channel string
	handler func(*JwksUpdateMessage)
	mu      sync.RWMutex
	stopCh  chan struct{}
}

// NewSubscriber creates a new Redis Pub/Sub subscriber
func NewSubscriber(client *redis.Client) *Subscriber {
	return &Subscriber{
		client:  client,
		channel: domain.RedisChannelJwksUpdate,
		stopCh:  make(chan struct{}),
	}
}

// SetHandler sets the callback handler for JWKS update messages
func (s *Subscriber) SetHandler(handler func(*JwksUpdateMessage)) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.handler = handler
}

// Start starts listening for messages on the channel
func (s *Subscriber) Start(ctx context.Context) error {
	pubsub := s.client.Subscribe(ctx, s.channel)

	// Wait for confirmation that subscription is active
	_, err := pubsub.Receive(ctx)
	if err != nil {
		return err
	}

	go func() {
		defer pubsub.Close()

		ch := pubsub.Channel()
		for {
			select {
			case <-s.stopCh:
				return
			case <-ctx.Done():
				return
			case msg := <-ch:
				if msg == nil {
					continue
				}

				var updateMsg JwksUpdateMessage
				if err := json.Unmarshal([]byte(msg.Payload), &updateMsg); err != nil {
					continue
				}

				s.mu.RLock()
				handler := s.handler
				s.mu.RUnlock()

				if handler != nil {
					handler(&updateMsg)
				}
			}
		}
	}()

	return nil
}

// Stop stops the subscriber
func (s *Subscriber) Stop() {
	close(s.stopCh)
}
