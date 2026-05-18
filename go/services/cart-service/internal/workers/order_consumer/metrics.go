package order_consumer

import (
	"sync"
	"sync/atomic"
	"time"
)

// OrderConsumerMetrics holds metrics for the order event consumer
type OrderConsumerMetrics struct {
	mu                    sync.RWMutex
	MessagesReceived      int64
	MessagesProcessed     int64
	MessagesSkipped       int64
	CartsCleared          int64
	Errors                int64
	DLQMessages           int64
	LastProcessedAt       time.Time
	AvgLatencyMs          float64
}

// IncMessagesReceived increments the messages received counter
func (m *OrderConsumerMetrics) IncMessagesReceived() {
	atomic.AddInt64(&m.MessagesReceived, 1)
}

// AddMessagesReceived adds to the messages received counter
func (m *OrderConsumerMetrics) AddMessagesReceived(n int64) {
	atomic.AddInt64(&m.MessagesReceived, n)
}

// IncMessagesProcessed increments the messages processed counter
func (m *OrderConsumerMetrics) IncMessagesProcessed() {
	atomic.AddInt64(&m.MessagesProcessed, 1)
}

// AddMessagesProcessed adds to the messages processed counter
func (m *OrderConsumerMetrics) AddMessagesProcessed(n int64) {
	atomic.AddInt64(&m.MessagesProcessed, n)
}

// IncMessagesSkipped increments the messages skipped counter
func (m *OrderConsumerMetrics) IncMessagesSkipped() {
	atomic.AddInt64(&m.MessagesSkipped, 1)
}

// IncCartsCleared increments the carts cleared counter
func (m *OrderConsumerMetrics) IncCartsCleared() {
	atomic.AddInt64(&m.CartsCleared, 1)
}

// AddCartsCleared adds to the carts cleared counter
func (m *OrderConsumerMetrics) AddCartsCleared(n int64) {
	atomic.AddInt64(&m.CartsCleared, n)
}

// IncErrors increments the errors counter
func (m *OrderConsumerMetrics) IncErrors() {
	atomic.AddInt64(&m.Errors, 1)
}

// IncDLQMessages increments the DLQ messages counter
func (m *OrderConsumerMetrics) IncDLQMessages() {
	atomic.AddInt64(&m.DLQMessages, 1)
}

// AddDLQMessages adds to the DLQ messages counter
func (m *OrderConsumerMetrics) AddDLQMessages(n int64) {
	atomic.AddInt64(&m.DLQMessages, n)
}

// SetLastProcessedAt sets the last processed timestamp
func (m *OrderConsumerMetrics) SetLastProcessedAt(t time.Time) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.LastProcessedAt = t
}

// UpdateAvgLatency updates the average latency
func (m *OrderConsumerMetrics) UpdateAvgLatency(latencyMs float64) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.AvgLatencyMs == 0 {
		m.AvgLatencyMs = latencyMs
	} else {
		m.AvgLatencyMs = m.AvgLatencyMs*0.9 + latencyMs*0.1
	}
}

// Snapshot returns a snapshot of the metrics
func (m *OrderConsumerMetrics) Snapshot() OrderConsumerSnapshot {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return OrderConsumerSnapshot{
		MessagesReceived:  atomic.LoadInt64(&m.MessagesReceived),
		MessagesProcessed: atomic.LoadInt64(&m.MessagesProcessed),
		MessagesSkipped:   atomic.LoadInt64(&m.MessagesSkipped),
		CartsCleared:      atomic.LoadInt64(&m.CartsCleared),
		Errors:            atomic.LoadInt64(&m.Errors),
		DLQMessages:       atomic.LoadInt64(&m.DLQMessages),
		LastProcessedAt:   m.LastProcessedAt,
		AvgLatencyMs:      m.AvgLatencyMs,
	}
}

// OrderConsumerSnapshot is a snapshot of order consumer metrics
type OrderConsumerSnapshot struct {
	MessagesReceived  int64     `json:"messages_received"`
	MessagesProcessed int64     `json:"messages_processed"`
	MessagesSkipped   int64     `json:"messages_skipped"`
	CartsCleared      int64     `json:"carts_cleared"`
	Errors            int64     `json:"errors"`
	DLQMessages       int64     `json:"dlq_messages"`
	LastProcessedAt   time.Time `json:"last_processed_at"`
	AvgLatencyMs      float64   `json:"avg_latency_ms"`
}
