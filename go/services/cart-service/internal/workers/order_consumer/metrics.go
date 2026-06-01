package order_consumer

import (
	"sync/atomic"
	"time"

	"go.uber.org/zap"
)

// Metrics holds counters for the order consumer worker.
type Metrics struct {
	logger *zap.Logger

	ordersProcessed atomic.Int64
	ordersFailed   atomic.Int64
	errors         atomic.Int64
	lastProcessedAt atomic.Int64
	lastErrorAt     atomic.Int64
}

// NewMetrics creates new order consumer metrics.
func NewMetrics(logger *zap.Logger) *Metrics {
	return &Metrics{logger: logger}
}

// RecordSuccess increments the success counter.
func (m *Metrics) RecordSuccess() {
	m.ordersProcessed.Add(1)
	m.lastProcessedAt.Store(time.Now().UnixMilli())
}

// RecordFailure increments the failure counter.
func (m *Metrics) RecordFailure() {
	m.ordersFailed.Add(1)
	m.lastErrorAt.Store(time.Now().UnixMilli())
}

// RecordErrors increments the error counter.
func (m *Metrics) RecordErrors() {
	m.errors.Add(1)
	m.ordersFailed.Add(1)
	m.lastErrorAt.Store(time.Now().UnixMilli())
}

// Snapshot returns a point-in-time snapshot of all metrics.
func (m *Metrics) Snapshot() MetricsSnapshot {
	return MetricsSnapshot{
		OrdersProcessed:  m.ordersProcessed.Load(),
		OrdersFailed:    m.ordersFailed.Load(),
		Errors:         m.errors.Load(),
		LastProcessedAt: m.lastProcessedAt.Load(),
		LastErrorAt:     m.lastErrorAt.Load(),
	}
}

// MetricsSnapshot holds a point-in-time snapshot.
type MetricsSnapshot struct {
	OrdersProcessed int64
	OrdersFailed  int64
	Errors       int64
	LastProcessedAt int64
	LastErrorAt     int64
}

// Log logs the current metrics snapshot.
func (m *Metrics) Log() {
	s := m.Snapshot()
	m.logger.Info("order_consumer metrics",
		zap.Int64("orders_processed", s.OrdersProcessed),
		zap.Int64("orders_failed", s.OrdersFailed),
		zap.Int64("errors", s.Errors),
	)
}
