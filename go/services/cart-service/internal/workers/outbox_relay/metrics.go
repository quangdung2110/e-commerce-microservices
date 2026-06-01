package outbox_relay

import (
	"sync/atomic"
	"time"

	"go.uber.org/zap"
)

// Metrics holds counters for the outbox relay worker.
type Metrics struct {
	logger *zap.Logger

	messagesProcessed  atomic.Int64
	messagesFailed     atomic.Int64
	kafkaPublishErrors atomic.Int64
	redisReadErrors   atomic.Int64
	ackErrors         atomic.Int64
	lastProcessedAt   atomic.Int64
	lastErrorAt       atomic.Int64
}

// NewMetrics creates new outbox relay metrics.
func NewMetrics(logger *zap.Logger) *Metrics {
	return &Metrics{logger: logger}
}

// RecordProcessed increments the processed message counter.
func (m *Metrics) RecordProcessed(eventType string) {
	m.messagesProcessed.Add(1)
	m.lastProcessedAt.Store(time.Now().UnixMilli())
}

// RecordFailed increments the failed message counter.
func (m *Metrics) RecordFailed(eventType string) {
	m.messagesFailed.Add(1)
	m.lastErrorAt.Store(time.Now().UnixMilli())
}

// RecordKafkaError increments the Kafka publish error counter.
func (m *Metrics) RecordKafkaError(eventType string) {
	m.kafkaPublishErrors.Add(1)
	m.messagesFailed.Add(1)
	m.lastErrorAt.Store(time.Now().UnixMilli())
}

// RecordRedisError increments the Redis read error counter.
func (m *Metrics) RecordRedisError() {
	m.redisReadErrors.Add(1)
}

// RecordAckError increments the ACK error counter.
func (m *Metrics) RecordAckError() {
	m.ackErrors.Add(1)
}

// Snapshot returns a point-in-time snapshot of all metrics.
func (m *Metrics) Snapshot() MetricsSnapshot {
	return MetricsSnapshot{
		MessagesProcessed:  m.messagesProcessed.Load(),
		MessagesFailed:     m.messagesFailed.Load(),
		KafkaPublishErrors: m.kafkaPublishErrors.Load(),
		RedisReadErrors:   m.redisReadErrors.Load(),
		AckErrors:         m.ackErrors.Load(),
		LastProcessedAt:    m.lastProcessedAt.Load(),
		LastErrorAt:       m.lastErrorAt.Load(),
	}
}

// MetricsSnapshot holds a point-in-time snapshot of relay metrics.
type MetricsSnapshot struct {
	MessagesProcessed  int64
	MessagesFailed     int64
	KafkaPublishErrors int64
	RedisReadErrors   int64
	AckErrors         int64
	LastProcessedAt   int64
	LastErrorAt       int64
}

// Log logs the current metrics snapshot.
func (m *Metrics) Log() {
	s := m.Snapshot()
	m.logger.Info("outbox_relay metrics",
		zap.Int64("messages_processed", s.MessagesProcessed),
		zap.Int64("messages_failed", s.MessagesFailed),
		zap.Int64("kafka_errors", s.KafkaPublishErrors),
		zap.Int64("redis_errors", s.RedisReadErrors),
		zap.Int64("ack_errors", s.AckErrors),
	)
}
