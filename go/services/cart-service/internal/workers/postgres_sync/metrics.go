package postgres_sync

import (
	"sync/atomic"
	"time"

	"go.uber.org/zap"
)

// Metrics holds counters for the postgres sync worker.
type Metrics struct {
	logger *zap.Logger

	eventsProcessed   atomic.Int64
	eventsFailed     atomic.Int64
	itemsUpserted    atomic.Int64
	itemsDeleted     atomic.Int64
	statusUpdated    atomic.Int64
	dbErrors         atomic.Int64
	kafkaPollErrors atomic.Int64
	lastProcessedAt  atomic.Int64
	lastErrorAt      atomic.Int64
}

// NewMetrics creates new postgres sync metrics.
func NewMetrics(logger *zap.Logger) *Metrics {
	return &Metrics{logger: logger}
}

// RecordProcessed increments the processed event counter.
func (m *Metrics) RecordProcessed(eventType string) {
	m.eventsProcessed.Add(1)
	m.lastProcessedAt.Store(time.Now().UnixMilli())
}

// RecordFailed increments the failed event counter.
func (m *Metrics) RecordFailed(eventType string) {
	m.eventsFailed.Add(1)
	m.lastErrorAt.Store(time.Now().UnixMilli())
}

// RecordItemUpserted increments the item upsert counter.
func (m *Metrics) RecordItemUpserted() {
	m.itemsUpserted.Add(1)
}

// RecordItemDeleted increments the item delete counter.
func (m *Metrics) RecordItemDeleted() {
	m.itemsDeleted.Add(1)
}

// RecordStatusUpdated increments the status update counter.
func (m *Metrics) RecordStatusUpdated() {
	m.statusUpdated.Add(1)
}

// RecordDBError increments the DB error counter.
func (m *Metrics) RecordDBError() {
	m.dbErrors.Add(1)
}

// RecordKafkaPollError increments the Kafka poll error counter.
func (m *Metrics) RecordKafkaPollError() {
	m.kafkaPollErrors.Add(1)
}

// Snapshot returns a point-in-time snapshot of all metrics.
func (m *Metrics) Snapshot() MetricsSnapshot {
	return MetricsSnapshot{
		EventsProcessed:  m.eventsProcessed.Load(),
		EventsFailed:    m.eventsFailed.Load(),
		ItemsUpserted:   m.itemsUpserted.Load(),
		ItemsDeleted:    m.itemsDeleted.Load(),
		StatusUpdated:   m.statusUpdated.Load(),
		DBErrors:       m.dbErrors.Load(),
		KafkaPollErrors: m.kafkaPollErrors.Load(),
		LastProcessedAt: m.lastProcessedAt.Load(),
		LastErrorAt:     m.lastErrorAt.Load(),
	}
}

// MetricsSnapshot holds a point-in-time snapshot.
type MetricsSnapshot struct {
	EventsProcessed  int64
	EventsFailed    int64
	ItemsUpserted   int64
	ItemsDeleted    int64
	StatusUpdated   int64
	DBErrors       int64
	KafkaPollErrors int64
	LastProcessedAt int64
	LastErrorAt     int64
}

// Log logs the current metrics snapshot.
func (m *Metrics) Log() {
	s := m.Snapshot()
	m.logger.Info("postgres_sync metrics",
		zap.Int64("events_processed", s.EventsProcessed),
		zap.Int64("events_failed", s.EventsFailed),
		zap.Int64("items_upserted", s.ItemsUpserted),
		zap.Int64("items_deleted", s.ItemsDeleted),
		zap.Int64("status_updated", s.StatusUpdated),
		zap.Int64("db_errors", s.DBErrors),
		zap.Int64("kafka_poll_errors", s.KafkaPollErrors),
	)
}
