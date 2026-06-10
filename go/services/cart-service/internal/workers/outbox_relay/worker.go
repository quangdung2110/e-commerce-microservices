package outbox_relay

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"

	"cart-service/internal/common/keybuilder"
	"cart-service/internal/domain/event"
	"cart-service/internal/domain/port"
	"cart-service/internal/infrastructure/config"
)

// Worker represents the Outbox Relay worker for one shard.
// It reads events from a Redis Stream shard and forwards them to Kafka.
type Worker struct {
	shardID       int
	streamKey     string
	redisClient   redis.UniversalClient
	kafkaProducer port.KafkaPublisher
	consumerGroup string
	workerID      string
	blockTimeout  time.Duration
	batchSize     int
	logger        *zap.Logger
	stopCh        chan struct{}
	wg            sync.WaitGroup
}

// NewWorker creates a new Outbox Relay worker for a shard.
func NewWorker(
	shardID int,
	redisClient redis.UniversalClient,
	kafkaProducer port.KafkaPublisher,
	cfg config.OutboxRelayCfg,
	logger *zap.Logger,
) *Worker {
	kb := keybuilder.New(0) // zero because we're using explicit shard
	_ = kb
	return &Worker{
		shardID:       shardID,
		streamKey:     fmt.Sprintf("{cart}:events:%d", shardID),
		redisClient:   redisClient,
		kafkaProducer: kafkaProducer,
		consumerGroup: fmt.Sprintf("cart_relay_group:%d", shardID),
		workerID:      fmt.Sprintf("worker-%d-%d", shardID, time.Now().UnixNano()),
		blockTimeout:  parseDuration(cfg.BlockTimeout),
		batchSize:     cfg.BatchSize,
		logger:        logger.With(zap.Int("shard", shardID)),
		stopCh:        make(chan struct{}),
	}
}

// NewWorkerWithKB creates a new Outbox Relay worker with an explicit KeyBuilder.
func NewWorkerWithKB(
	shardID int,
	redisClient redis.UniversalClient,
	kafkaProducer port.KafkaPublisher,
	cfg config.OutboxRelayCfg,
	logger *zap.Logger,
	kb *keybuilder.KeyBuilder,
) *Worker {
	streamKey := kb.StreamShard(uint32(shardID))
	return &Worker{
		shardID:       shardID,
		streamKey:     streamKey,
		redisClient:   redisClient,
		kafkaProducer: kafkaProducer,
		consumerGroup: fmt.Sprintf("cart_relay_group:%d", shardID),
		workerID:      fmt.Sprintf("worker-%d-%d", shardID, time.Now().UnixNano()),
		blockTimeout:  parseDuration(cfg.BlockTimeout),
		batchSize:     cfg.BatchSize,
		logger:        logger.With(zap.Int("shard", shardID)),
		stopCh:        make(chan struct{}),
	}
}

// StreamShardKey returns the Redis Stream key for this worker's shard.
func (w *Worker) StreamShardKey() string {
	return w.streamKey
}

// Start begins the relay loop.
func (w *Worker) Start(ctx context.Context) error {
	w.wg.Add(1)
	go w.run(ctx)
	w.logger.Info("outbox relay worker started")
	return nil
}

// Stop gracefully stops the worker.
func (w *Worker) Stop() {
	close(w.stopCh)
	w.wg.Wait()
	w.logger.Info("outbox relay worker stopped")
}

func (w *Worker) run(ctx context.Context) {
	defer w.wg.Done()

	if err := w.ensureConsumerGroup(ctx); err != nil {
		w.logger.Error("failed to create consumer group", zap.Error(err))
		return
	}

	for {
		select {
		case <-ctx.Done():
			return
		case <-w.stopCh:
			return
		default:
			w.readAndForward(ctx)
		}
	}
}

func (w *Worker) ensureConsumerGroup(ctx context.Context) error {
	err := w.redisClient.XGroupCreateMkStream(
		ctx, w.streamKey, w.consumerGroup, "0",
	).Err()
	if err != nil && !isConsumerGroupExistsError(err) {
		return err
	}
	return nil
}

func isConsumerGroupExistsError(err error) bool {
	return err != nil && (err.Error() == "BUSYGROUP Consumer Group name already exists" ||
		containsString(err.Error(), "BUSYGROUP Consumer Group name already exists"))
}

func containsString(s, substr string) bool {
	return len(s) >= len(substr) && (s == substr || len(s) > 0 && containsSubstring(s, substr))
}

func containsSubstring(s, substr string) bool {
	for i := 0; i <= len(s)-len(substr); i++ {
		if s[i:i+len(substr)] == substr {
			return true
		}
	}
	return false
}

func (w *Worker) readAndForward(ctx context.Context) {
	result, err := w.redisClient.XReadGroup(ctx, &redis.XReadGroupArgs{
		Group:    w.consumerGroup,
		Consumer: w.workerID,
		Streams:  []string{w.streamKey, ">"},
		Count:    int64(w.batchSize),
		Block:    w.blockTimeout,
	}).Result()

	if err != nil {
		if err == redis.Nil {
			return
		}
		w.logger.Warn("XREADGROUP error", zap.Error(err))
		time.Sleep(time.Second)
		return
	}

	for _, stream := range result {
		for _, msg := range stream.Messages {
			w.processMessage(ctx, &msg)
		}
	}
}

func (w *Worker) processMessage(ctx context.Context, msg *redis.XMessage) {
	eventType, hasType := msg.Values["event_type"].(string)
	payloadJSON, hasPayload := msg.Values["payload"].(string)
	if !hasType || !hasPayload {
		w.logger.Warn("malformed stream message", zap.String("id", msg.ID))
		w.ack(ctx, msg.ID)
		return
	}

	streamTs, streamSeq := event.ParseStreamID(msg.ID)

	payloadMap := extractPayloadMap(payloadJSON)
	cartID := extractCartIDFromPayloadMap(payloadMap)
	_ = extractUserIDFromPayloadMap(payloadMap) // extracted for future use
	correlationID := extractCorrelationIDFromPayloadMap(payloadMap)

	envelope := &event.CartEventEnvelope{
		EventID:            deterministicEventID(msg.ID, cartID),
		EventType:          eventType,
		AggregateType:      event.AggregateTypeCart,
		AggregateID:        cartID,
		CorrelationID:      correlationID,
		Timestamp:          time.Now().UTC().Format(time.RFC3339),
		Source:             "cart-service",
		StreamID:           msg.ID,
		StreamTimestampMs:  streamTs,
		EventSequence:      streamSeq,
		Payload:            payloadMap,
	}

	kafkaMsg := &port.CartEventMessage{
		EventID:            envelope.EventID,
		EventType:          envelope.EventType,
		AggregateType:      envelope.AggregateType,
		AggregateID:        envelope.AggregateID,
		CorrelationID:      envelope.CorrelationID,
		Timestamp:          envelope.Timestamp,
		Source:             envelope.Source,
		StreamID:           envelope.StreamID,
		StreamTimestampMs:  envelope.StreamTimestampMs,
		EventSequence:      envelope.EventSequence,
		Payload:            envelope.Payload,
	}

	if err := w.kafkaProducer.Publish(ctx, kafkaMsg); err != nil {
		w.logger.Error("Kafka publish failed",
			zap.String("stream_id", msg.ID),
			zap.String("event_type", eventType),
			zap.String("cart_id", cartID),
			zap.Error(err),
		)
		incrementRelayErrors(w.logger, eventType)
		return
	}

	w.ack(ctx, msg.ID)
	incrementRelaySuccess(w.logger, eventType)
}

func (w *Worker) ack(ctx context.Context, messageID string) {
	ackCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	if err := w.redisClient.XAck(ackCtx, w.streamKey, w.consumerGroup, messageID).Err(); err != nil {
		w.logger.Warn("XACK failed", zap.String("id", messageID), zap.Error(err))
	}
}

// deterministicEventID generates a stable event ID from stream ID and cart ID.
func deterministicEventID(streamID, cartID string) string {
	h := sha256.New()
	h.Write([]byte(streamID + cartID))
	return hex.EncodeToString(h.Sum(nil))[:32]
}

// extractCartIDFromPayload extracts cart_id from a JSON payload string.
func extractCartIDFromPayload(payloadJSON string) string {
	var m map[string]interface{}
	if err := json.Unmarshal([]byte(payloadJSON), &m); err != nil {
		return ""
	}
	if cartID, ok := m["cart_id"].(string); ok {
		return cartID
	}
	return ""
}

// extractUserIDFromPayload extracts user_id from a JSON payload string.
func extractUserIDFromPayload(payloadJSON string) string {
	var m map[string]interface{}
	if err := json.Unmarshal([]byte(payloadJSON), &m); err != nil {
		return ""
	}
	if userID, ok := m["user_id"].(string); ok {
		return userID
	}
	return ""
}

// extractCorrelationID extracts correlation_id from a JSON payload string.
func extractCorrelationID(payloadJSON string) string {
	var m map[string]interface{}
	if err := json.Unmarshal([]byte(payloadJSON), &m); err != nil {
		return ""
	}
	if cid, ok := m["correlation_id"].(string); ok {
		return cid
	}
	return ""
}

// extractPayloadMap parses a JSON payload string into a map.
func extractPayloadMap(payloadJSON string) map[string]interface{} {
	var m map[string]interface{}
	if err := json.Unmarshal([]byte(payloadJSON), &m); err != nil {
		return make(map[string]interface{})
	}
	return m
}

// extractCartIDFromPayloadMap extracts cart_id from a payload map.
func extractCartIDFromPayloadMap(m map[string]interface{}) string {
	if cartID, ok := m["cart_id"].(string); ok {
		return cartID
	}
	return ""
}

// extractUserIDFromPayloadMap extracts user_id from a payload map.
func extractUserIDFromPayloadMap(m map[string]interface{}) string {
	if userID, ok := m["user_id"].(string); ok {
		return userID
	}
	return ""
}

// extractCorrelationIDFromPayloadMap extracts correlation_id from a payload map.
func extractCorrelationIDFromPayloadMap(m map[string]interface{}) string {
	if cid, ok := m["correlation_id"].(string); ok {
		return cid
	}
	return ""
}

// parseDuration parses a duration string with a default.
func parseDuration(s string) time.Duration {
	if s == "" {
		return 5 * time.Second
	}
	if d, err := time.ParseDuration(s); err == nil {
		return d
	}
	return 5 * time.Second
}

// ShardKeyForUserID returns the stream shard key for a given user.
func ShardKeyForUserID(userID string, numShards int, kb *keybuilder.KeyBuilder) string {
	shardID := kb.StreamShardByUserID(userID)
	return kb.StreamShard(shardID)
}

// StreamShardKeyWithKB generates a stream shard key using the KeyBuilder.
func StreamShardKeyWithKB(shardID int, kb *keybuilder.KeyBuilder) string {
	return kb.StreamShard(uint32(shardID))
}

// WorkerPool manages multiple outbox relay workers across all shards.
type WorkerPool struct {
	workers  []*Worker
	stopCh   chan struct{}
	wg       sync.WaitGroup
	logger   *zap.Logger
}

// NewWorkerPool creates a pool of outbox relay workers, one per shard.
func NewWorkerPool(
	redisClient redis.UniversalClient,
	kafkaProducer port.KafkaPublisher,
	cfg config.OutboxRelayCfg,
	numShards int,
	logger *zap.Logger,
) *WorkerPool {
	kb := keybuilder.New(uint32(numShards))
	workers := make([]*Worker, numShards)
	for i := 0; i < numShards; i++ {
		workers[i] = NewWorkerWithKB(i, redisClient, kafkaProducer, cfg, logger, kb)
	}
	return &WorkerPool{
		workers: workers,
		stopCh:  make(chan struct{}),
		logger:  logger,
	}
}

// Start starts all workers in the pool.
func (p *WorkerPool) Start(ctx context.Context) {
	p.wg.Add(len(p.workers))
	for _, w := range p.workers {
		go func(worker *Worker) {
			defer p.wg.Done()
			worker.run(ctx)
		}(w)
	}
	p.logger.Info("outbox relay worker pool started",
		zap.Int("num_workers", len(p.workers)))
}

// Stop stops all workers in the pool.
func (p *WorkerPool) Stop() {
	close(p.stopCh)
	for _, w := range p.workers {
		w.Stop()
	}
}

// WorkerCount returns the number of workers in the pool.
func (p *WorkerPool) WorkerCount() int {
	return len(p.workers)
}

// relayMetrics holds counters for relay operations.
var (
	relaySuccessCount int64
	relayErrorCount   int64
)

func incrementRelaySuccess(logger *zap.Logger, eventType string) {
	logger.Debug("event relayed",
		zap.String("event_type", eventType),
		zap.String("direction", "redis_stream->kafka"))
}

func incrementRelayErrors(logger *zap.Logger, eventType string) {
	logger.Warn("event relay failed",
		zap.String("event_type", eventType),
		zap.String("direction", "redis_stream->kafka"))
}

// HealthCheck performs a health check on the outbox relay worker.
func (w *Worker) HealthCheck(ctx context.Context) error {
	if w.redisClient == nil {
		return fmt.Errorf("redis client is nil")
	}
	if w.kafkaProducer == nil {
		return fmt.Errorf("kafka producer is nil")
	}
	return nil
}

// RedisStreamKey returns the stream key for this worker.
func (w *Worker) RedisStreamKey() string {
	return w.streamKey
}

// ConsumerGroupName returns the consumer group name for this worker.
func (w *Worker) ConsumerGroupName() string {
	return w.consumerGroup
}
