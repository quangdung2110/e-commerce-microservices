package outbox_relay

import (
	"context"
	"strconv"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"

	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/common/keybuilder"
)

// PubSubSubscriber subscribes to Redis Pub/Sub channel for real-time stream notifications
type PubSubSubscriber struct {
	redisClient *redis.Client
	channel     chan string
	stopCh      chan struct{}
	logger      *zap.Logger
}

// NewPubSubSubscriber creates a new Pub/Sub subscriber
func NewPubSubSubscriber(
	redisClient *redis.Client,
	channel chan string,
	stopCh chan struct{},
	logger *zap.Logger,
) *PubSubSubscriber {
	return &PubSubSubscriber{
		redisClient: redisClient,
		channel:    channel,
		stopCh:     stopCh,
		logger:     logger,
	}
}

// Start subscribes to the outbox_notifications channel
func (p *PubSubSubscriber) Start(ctx context.Context) error {
	p.logger.Info("Starting Pub/Sub subscriber",
		zap.String("channel", keybuilder.OutboxNotificationsChannel),
	)

	for {
		select {
		case <-ctx.Done():
			p.logger.Info("Pub/Sub subscriber stopped due to context cancellation")
			return ctx.Err()
		case <-p.stopCh:
			p.logger.Info("Pub/Sub subscriber stopped")
			return nil
		default:
			if err := p.subscribe(ctx); err != nil {
				p.logger.Error("Pub/Sub subscription error", zap.Error(err))
				time.Sleep(time.Second) // Backoff on error
			}
		}
	}
}

// subscribe handles the actual subscription
func (p *PubSubSubscriber) subscribe(ctx context.Context) error {
	// Create a new Pub/Sub connection
	pubsub := p.redisClient.Subscribe(ctx, keybuilder.OutboxNotificationsChannel)
	defer pubsub.Close()

	// Wait for confirmation of subscription
	_, err := pubsub.Receive(ctx)
	if err != nil {
		return err
	}

	p.logger.Info("Pub/Sub subscription confirmed")

	// Channel for messages
	ch := pubsub.Channel()

	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-p.stopCh:
			return nil
		case msg := <-ch:
			if msg == nil {
				return nil
			}

			// Push stream name to channel
			select {
			case p.channel <- msg.Payload:
				p.logger.Debug("Received Pub/Sub notification",
					zap.String("stream", msg.Payload),
				)
			default:
				p.logger.Warn("Stream channel full, dropping notification",
					zap.String("stream", msg.Payload),
				)
			}
		}
	}
}

// ZSETFallbackWorker polls active_outbox_streams ZSET to catch any missed notifications
type ZSETFallbackWorker struct {
	redisClient *redis.Client
	channel     chan string
	stopCh      chan struct{}
	logger      *zap.Logger
	config      FallbackConfig

	mu sync.Mutex
}

// FallbackConfig holds configuration for fallback worker
type FallbackConfig struct {
	Interval     time.Duration
	BatchSize    int
	StaleTimeout time.Duration
}

// NewZSETFallbackWorker creates a new ZSET Fallback worker
func NewZSETFallbackWorker(
	redisClient *redis.Client,
	channel chan string,
	stopCh chan struct{},
	logger *zap.Logger,
	cfg interface{},
) *ZSETFallbackWorker {
	// Parse config with defaults
	interval := 30 * time.Second
	batchSize := 1000
	staleTimeout := 30 * time.Second

	if c, ok := cfg.(struct {
		Interval         string
		BatchSize        int
		PendingTimeoutMs int
	}); ok {
		interval = 30 * time.Second
		batchSize = c.BatchSize
		if c.PendingTimeoutMs > 0 {
			staleTimeout = time.Duration(c.PendingTimeoutMs) * time.Millisecond
		}
	}

	return &ZSETFallbackWorker{
		redisClient: redisClient,
		channel:    channel,
		stopCh:     stopCh,
		logger:     logger,
		config: FallbackConfig{
			Interval:     interval,
			BatchSize:    batchSize,
			StaleTimeout: staleTimeout,
		},
	}
}

// Start begins the fallback worker loop
func (w *ZSETFallbackWorker) Start(ctx context.Context) {
	w.logger.Info("Starting ZSET Fallback worker",
		zap.Duration("interval", w.config.Interval),
		zap.Int("batch_size", w.config.BatchSize),
	)

	ticker := time.NewTicker(w.config.Interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			w.logger.Info("ZSET Fallback worker stopped due to context cancellation")
			return
		case <-w.stopCh:
			w.logger.Info("ZSET Fallback worker stopped")
			return
		case <-ticker.C:
			w.pollActiveStreams(ctx)
		}
	}
}

// pollActiveStreams scans active_outbox_streams for stale entries
func (w *ZSETFallbackWorker) pollActiveStreams(ctx context.Context) {
	w.mu.Lock()
	defer w.mu.Unlock()

	nowMs := time.Now().UnixMilli()
	staleThreshold := nowMs - int64(w.config.StaleTimeout.Milliseconds())

	// Get streams that need processing (score < now - 30s)
	// Use ZRANGEBYSCORE with string representation of score
	streams, err := w.redisClient.ZRangeByScore(
		ctx,
		keybuilder.ActiveOutboxStreamsKey,
		&redis.ZRangeBy{
			Min: "-inf",
			Max: formatScore(staleThreshold),
		},
	).Result()

	if err != nil {
		w.logger.Error("Failed to poll active streams", zap.Error(err))
		return
	}

	for _, streamKey := range streams {
		// Push to channel
		select {
		case w.channel <- streamKey:
			w.logger.Debug("Fallback: pushed stale stream",
				zap.String("stream", streamKey),
			)

			// Update the score to prevent reprocessing in next cycle
			w.redisClient.ZAdd(ctx, keybuilder.ActiveOutboxStreamsKey, redis.Z{
				Score:  float64(nowMs),
				Member: streamKey,
			})
		default:
			w.logger.Warn("Stream channel full during fallback",
				zap.String("stream", streamKey),
			)
		}
	}
}

// formatScore converts a score to string for Redis
func formatScore(score int64) string {
	return strconv.FormatInt(score, 10)
}

// IntentRecoveryWorker recovers intents that were not completed (App crashed after Lua, before Publish)
type IntentRecoveryWorker struct {
	redisClient *redis.Client
	channel     chan string
	stopCh      chan struct{}
	logger      *zap.Logger
	config      IntentRecoveryConfig
}

// IntentRecoveryConfig holds configuration for intent recovery
type IntentRecoveryConfig struct {
	Interval  time.Duration
	BatchSize int
}

// NewIntentRecoveryWorker creates a new Intent Recovery worker
func NewIntentRecoveryWorker(
	redisClient *redis.Client,
	channel chan string,
	stopCh chan struct{},
	logger *zap.Logger,
	cfg interface{},
) *IntentRecoveryWorker {
	interval := 30 * time.Second
	batchSize := 1000

	return &IntentRecoveryWorker{
		redisClient: redisClient,
		channel:    channel,
		stopCh:     stopCh,
		logger:     logger,
		config: IntentRecoveryConfig{
			Interval:  interval,
			BatchSize: batchSize,
		},
	}
}

// Start begins the intent recovery worker loop
func (w *IntentRecoveryWorker) Start(ctx context.Context) {
	w.logger.Info("Starting Intent Recovery worker",
		zap.Duration("interval", w.config.Interval),
		zap.Int("batch_size", w.config.BatchSize),
	)

	ticker := time.NewTicker(w.config.Interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			w.logger.Info("Intent Recovery worker stopped due to context cancellation")
			return
		case <-w.stopCh:
			w.logger.Info("Intent Recovery worker stopped")
			return
		case <-ticker.C:
			w.recoverIntents(ctx)
		}
	}
}

// recoverIntents scans outbox_publish_intents for stale intents
func (w *IntentRecoveryWorker) recoverIntents(ctx context.Context) {
	nowMs := time.Now().UnixMilli()
	// Look for intents older than 5 seconds
	staleThreshold := nowMs - 5000

	// Get stale intents
	intents, err := w.redisClient.ZRangeByScore(
		ctx,
		keybuilder.OutboxPublishIntentsKey,
		&redis.ZRangeBy{
			Min: "-inf",
			Max: formatScore(staleThreshold),
		},
	).Result()

	if err != nil {
		w.logger.Error("Failed to get stale intents", zap.Error(err))
		return
	}

	for _, streamKey := range intents {
		// Check if stream has messages (XLEN > 0)
		xlen, err := w.redisClient.XLen(ctx, streamKey).Result()
		if err != nil {
			w.logger.Error("Failed to check stream length",
				zap.String("stream", streamKey),
				zap.Error(err),
			)
			continue
		}

		if xlen > 0 {
			// Stream has messages - need to publish
			w.logger.Info("Intent Recovery: found stream with messages",
				zap.String("stream", streamKey),
				zap.Int64("message_count", xlen),
			)

			// Execute publish path
			nowMs := time.Now().UnixMilli()
			w.redisClient.ZAdd(ctx, keybuilder.ActiveOutboxStreamsKey, redis.Z{
				Score:  float64(nowMs),
				Member: streamKey,
			})
			w.redisClient.Publish(ctx, keybuilder.OutboxNotificationsChannel, streamKey)

			// Push to channel
			select {
			case w.channel <- streamKey:
			default:
				w.logger.Warn("Stream channel full during intent recovery",
					zap.String("stream", streamKey),
				)
			}
		}

		// Remove intent (even if stream is empty - it's a stale intent)
		w.redisClient.ZRem(ctx, keybuilder.OutboxPublishIntentsKey, streamKey)
		w.logger.Debug("Intent Recovery: removed intent",
			zap.String("stream", streamKey),
			zap.Int64("xlen", xlen),
		)
	}
}
