package outbox_relay

import (
	"sync"
	"sync/atomic"
)

// Metrics holds metrics for the outbox relay worker
type Metrics struct {
	mu                     sync.RWMutex
	messagesProduced       int64
	messagesFailed        int64
	dlqCount              int64
	streamsProcessed      int64
	kafkaErrors           int64
	redisErrors           int64
	pendingCount          int64
}

// NewMetrics creates a new metrics instance
func NewMetrics() *Metrics {
	return &Metrics{}
}

// IncMessagesProduced increments the messages produced counter
func (m *Metrics) IncMessagesProduced() {
	atomic.AddInt64(&m.messagesProduced, 1)
}

// IncMessagesFailed increments the messages failed counter
func (m *Metrics) IncMessagesFailed() {
	atomic.AddInt64(&m.messagesFailed, 1)
}

// IncDLQCount increments the DLQ count
func (m *Metrics) IncDLQCount() {
	atomic.AddInt64(&m.dlqCount, 1)
}

// IncStreamsProcessed increments the streams processed counter
func (m *Metrics) IncStreamsProcessed() {
	atomic.AddInt64(&m.streamsProcessed, 1)
}

// IncKafkaErrors increments the Kafka errors counter
func (m *Metrics) IncKafkaErrors() {
	atomic.AddInt64(&m.kafkaErrors, 1)
}

// IncRedisErrors increments the Redis errors counter
func (m *Metrics) IncRedisErrors() {
	atomic.AddInt64(&m.redisErrors, 1)
}

// SetPendingCount sets the pending message count
func (m *Metrics) SetPendingCount(count int64) {
	atomic.StoreInt64(&m.pendingCount, count)
}

// GetMessagesProduced returns the messages produced count
func (m *Metrics) GetMessagesProduced() int64 {
	return atomic.LoadInt64(&m.messagesProduced)
}

// GetMessagesFailed returns the messages failed count
func (m *Metrics) GetMessagesFailed() int64 {
	return atomic.LoadInt64(&m.messagesFailed)
}

// GetDLQCount returns the DLQ count
func (m *Metrics) GetDLQCount() int64 {
	return atomic.LoadInt64(&m.dlqCount)
}

// GetStreamsProcessed returns the streams processed count
func (m *Metrics) GetStreamsProcessed() int64 {
	return atomic.LoadInt64(&m.streamsProcessed)
}

// GetKafkaErrors returns the Kafka errors count
func (m *Metrics) GetKafkaErrors() int64 {
	return atomic.LoadInt64(&m.kafkaErrors)
}

// GetRedisErrors returns the Redis errors count
func (m *Metrics) GetRedisErrors() int64 {
	return atomic.LoadInt64(&m.redisErrors)
}

// GetPendingCount returns the pending message count
func (m *Metrics) GetPendingCount() int64 {
	return atomic.LoadInt64(&m.pendingCount)
}

// Snapshot returns a snapshot of all metrics
func (m *Metrics) Snapshot() map[string]interface{} {
	m.mu.RLock()
	defer m.mu.RUnlock()

	return map[string]interface{}{
		"messages_produced":    m.GetMessagesProduced(),
		"messages_failed":     m.GetMessagesFailed(),
		"dlq_count":           m.GetDLQCount(),
		"streams_processed":   m.GetStreamsProcessed(),
		"kafka_errors":        m.GetKafkaErrors(),
		"redis_errors":        m.GetRedisErrors(),
		"pending_count":       m.GetPendingCount(),
	}
}
