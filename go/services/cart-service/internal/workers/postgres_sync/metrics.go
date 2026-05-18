package postgres_sync

import (
	"sync/atomic"
)

// PostgresSyncMetrics holds metrics for the postgres sync module
type PostgresSyncMetrics struct {
	// Events
	EventsReceived      int64
	EventsProcessed     int64
	EventsFailed       int64
	EventsSkipped      int64

	// Transactions
	TransactionsTotal      int64
	TransactionsSucceeded  int64
	TransactionsFailed     int64

	// Cart Items
	ItemsUpserted      int64
	ItemsDeleted       int64

	// Carts
	CartsActivated     int64
	CartsCleared      int64
	CartsCheckedOut   int64
	CartsAbandoned   int64

	// Kafka
	KafkaMessagesConsumed int64
	KafkaOffsetsCommitted int64

	// DLQ
	MessagesPushedToDLQ int64

	// Latency (nanoseconds stored, convert to ms when reporting)
	TotalProcessingTimeNs int64
	BatchCount          int64
}

// NewPostgresSyncMetrics creates a new metrics instance
func NewPostgresSyncMetrics() *PostgresSyncMetrics {
	return &PostgresSyncMetrics{}
}

// IncEventsReceived increments the events received counter
func (m *PostgresSyncMetrics) IncEventsReceived() {
	atomic.AddInt64(&m.EventsReceived, 1)
}

// AddEventsReceived adds to the events received counter
func (m *PostgresSyncMetrics) AddEventsReceived(n int64) {
	atomic.AddInt64(&m.EventsReceived, n)
}

// IncEventsProcessed increments the events processed counter
func (m *PostgresSyncMetrics) IncEventsProcessed() {
	atomic.AddInt64(&m.EventsProcessed, 1)
}

// AddEventsProcessed adds to the events processed counter
func (m *PostgresSyncMetrics) AddEventsProcessed(n int64) {
	atomic.AddInt64(&m.EventsProcessed, n)
}

// IncEventsFailed increments the events failed counter
func (m *PostgresSyncMetrics) IncEventsFailed() {
	atomic.AddInt64(&m.EventsFailed, 1)
}

// AddEventsFailed adds to the events failed counter
func (m *PostgresSyncMetrics) AddEventsFailed(n int64) {
	atomic.AddInt64(&m.EventsFailed, n)
}

// IncEventsSkipped increments the events skipped counter
func (m *PostgresSyncMetrics) IncEventsSkipped() {
	atomic.AddInt64(&m.EventsSkipped, 1)
}

// IncTransactionsSucceeded increments the transactions succeeded counter
func (m *PostgresSyncMetrics) IncTransactionsSucceeded() {
	atomic.AddInt64(&m.TransactionsTotal, 1)
	atomic.AddInt64(&m.TransactionsSucceeded, 1)
}

// IncTransactionsFailed increments the transactions failed counter
func (m *PostgresSyncMetrics) IncTransactionsFailed() {
	atomic.AddInt64(&m.TransactionsTotal, 1)
	atomic.AddInt64(&m.TransactionsFailed, 1)
}

// IncItemsUpserted increments the items upserted counter
func (m *PostgresSyncMetrics) IncItemsUpserted() {
	atomic.AddInt64(&m.ItemsUpserted, 1)
}

// AddItemsUpserted adds to the items upserted counter
func (m *PostgresSyncMetrics) AddItemsUpserted(n int64) {
	atomic.AddInt64(&m.ItemsUpserted, n)
}

// IncItemsDeleted increments the items deleted counter
func (m *PostgresSyncMetrics) IncItemsDeleted() {
	atomic.AddInt64(&m.ItemsDeleted, 1)
}

// AddItemsDeleted adds to the items deleted counter
func (m *PostgresSyncMetrics) AddItemsDeleted(n int64) {
	atomic.AddInt64(&m.ItemsDeleted, n)
}

// IncCartsActivated increments the carts activated counter
func (m *PostgresSyncMetrics) IncCartsActivated() {
	atomic.AddInt64(&m.CartsActivated, 1)
}

// IncCartsCleared increments the carts cleared counter
func (m *PostgresSyncMetrics) IncCartsCleared() {
	atomic.AddInt64(&m.CartsCleared, 1)
}

// IncCartsCheckedOut increments the carts checked out counter
func (m *PostgresSyncMetrics) IncCartsCheckedOut() {
	atomic.AddInt64(&m.CartsCheckedOut, 1)
}

// IncCartsAbandoned increments the carts abandoned counter
func (m *PostgresSyncMetrics) IncCartsAbandoned() {
	atomic.AddInt64(&m.CartsAbandoned, 1)
}

// IncKafkaMessagesConsumed increments the Kafka messages consumed counter
func (m *PostgresSyncMetrics) IncKafkaMessagesConsumed() {
	atomic.AddInt64(&m.KafkaMessagesConsumed, 1)
}

// AddKafkaMessagesConsumed adds to the Kafka messages consumed counter
func (m *PostgresSyncMetrics) AddKafkaMessagesConsumed(n int64) {
	atomic.AddInt64(&m.KafkaMessagesConsumed, n)
}

// IncKafkaOffsetsCommitted increments the Kafka offsets committed counter
func (m *PostgresSyncMetrics) IncKafkaOffsetsCommitted() {
	atomic.AddInt64(&m.KafkaOffsetsCommitted, 1)
}

// IncMessagesPushedToDLQ increments the messages pushed to DLQ counter
func (m *PostgresSyncMetrics) IncMessagesPushedToDLQ() {
	atomic.AddInt64(&m.MessagesPushedToDLQ, 1)
}

// AddProcessingTime adds to the total processing time
func (m *PostgresSyncMetrics) AddProcessingTime(ns int64) {
	atomic.AddInt64(&m.TotalProcessingTimeNs, ns)
}

// IncBatchCount increments the batch count
func (m *PostgresSyncMetrics) IncBatchCount() {
	atomic.AddInt64(&m.BatchCount, 1)
}

// GetMetrics returns a snapshot of all metrics
func (m *PostgresSyncMetrics) GetMetrics() PostgresSyncMetricsSnapshot {
	return PostgresSyncMetricsSnapshot{
		EventsReceived:       atomic.LoadInt64(&m.EventsReceived),
		EventsProcessed:      atomic.LoadInt64(&m.EventsProcessed),
		EventsFailed:        atomic.LoadInt64(&m.EventsFailed),
		EventsSkipped:       atomic.LoadInt64(&m.EventsSkipped),
		TransactionsTotal:   atomic.LoadInt64(&m.TransactionsTotal),
		TransactionsSucceeded: atomic.LoadInt64(&m.TransactionsSucceeded),
		TransactionsFailed:  atomic.LoadInt64(&m.TransactionsFailed),
		ItemsUpserted:      atomic.LoadInt64(&m.ItemsUpserted),
		ItemsDeleted:       atomic.LoadInt64(&m.ItemsDeleted),
		CartsActivated:     atomic.LoadInt64(&m.CartsActivated),
		CartsCleared:      atomic.LoadInt64(&m.CartsCleared),
		CartsCheckedOut:   atomic.LoadInt64(&m.CartsCheckedOut),
		CartsAbandoned:    atomic.LoadInt64(&m.CartsAbandoned),
		KafkaMessagesConsumed: atomic.LoadInt64(&m.KafkaMessagesConsumed),
		KafkaOffsetsCommitted: atomic.LoadInt64(&m.KafkaOffsetsCommitted),
		MessagesPushedToDLQ: atomic.LoadInt64(&m.MessagesPushedToDLQ),
		TotalProcessingTimeNs: atomic.LoadInt64(&m.TotalProcessingTimeNs),
		BatchCount:         atomic.LoadInt64(&m.BatchCount),
	}
}

// PostgresSyncMetricsSnapshot is a snapshot of all metrics
type PostgresSyncMetricsSnapshot struct {
	EventsReceived        int64 `json:"events_received"`
	EventsProcessed       int64 `json:"events_processed"`
	EventsFailed         int64 `json:"events_failed"`
	EventsSkipped        int64 `json:"events_skipped"`
	TransactionsTotal     int64 `json:"transactions_total"`
	TransactionsSucceeded int64 `json:"transactions_succeeded"`
	TransactionsFailed   int64 `json:"transactions_failed"`
	ItemsUpserted        int64 `json:"items_upserted"`
	ItemsDeleted         int64 `json:"items_deleted"`
	CartsActivated       int64 `json:"carts_activated"`
	CartsCleared        int64 `json:"carts_cleared"`
	CartsCheckedOut     int64 `json:"carts_checked_out"`
	CartsAbandoned     int64 `json:"carts_abandoned"`
	KafkaMessagesConsumed int64 `json:"kafka_messages_consumed"`
	KafkaOffsetsCommitted int64 `json:"kafka_offsets_committed"`
	MessagesPushedToDLQ  int64 `json:"messages_pushed_to_dlq"`
	TotalProcessingTimeNs int64 `json:"total_processing_time_ns"`
	BatchCount          int64 `json:"batch_count"`
}

// GetAvgProcessingTimeMs returns the average processing time in milliseconds
func (s *PostgresSyncMetricsSnapshot) GetAvgProcessingTimeMs() float64 {
	if s.BatchCount == 0 {
		return 0
	}
	return float64(s.TotalProcessingTimeNs) / float64(s.BatchCount) / 1e6
}
