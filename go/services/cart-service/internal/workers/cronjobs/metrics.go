package cronjobs

import (
	"sync"
	"sync/atomic"
	"time"
)

// AbandonedMarkerMetrics holds metrics for the abandoned cart marker
type AbandonedMarkerMetrics struct {
	mu                     sync.RWMutex
	CartsScanned          int64
	CartsMarkedAbandoned  int64
	CartsReconciled       int64
	EventsPublished       int64
	Errors                int64
	LastRunAt             time.Time
	AvgLatencyMs          float64
}

// IncCartsScanned increments the carts scanned counter
func (m *AbandonedMarkerMetrics) IncCartsScanned() {
	atomic.AddInt64(&m.CartsScanned, 1)
}

// AddCartsScanned adds to the carts scanned counter
func (m *AbandonedMarkerMetrics) AddCartsScanned(n int64) {
	atomic.AddInt64(&m.CartsScanned, n)
}

// IncCartsMarkedAbandoned increments the carts marked abandoned counter
func (m *AbandonedMarkerMetrics) IncCartsMarkedAbandoned() {
	atomic.AddInt64(&m.CartsMarkedAbandoned, 1)
}

// AddCartsMarkedAbandoned adds to the carts marked abandoned counter
func (m *AbandonedMarkerMetrics) AddCartsMarkedAbandoned(n int64) {
	atomic.AddInt64(&m.CartsMarkedAbandoned, n)
}

// IncCartsReconciled increments the carts reconciled counter
func (m *AbandonedMarkerMetrics) IncCartsReconciled() {
	atomic.AddInt64(&m.CartsReconciled, 1)
}

// AddCartsReconciled adds to the carts reconciled counter
func (m *AbandonedMarkerMetrics) AddCartsReconciled(n int64) {
	atomic.AddInt64(&m.CartsReconciled, n)
}

// IncEventsPublished increments the events published counter
func (m *AbandonedMarkerMetrics) IncEventsPublished() {
	atomic.AddInt64(&m.EventsPublished, 1)
}

// AddEventsPublished adds to the events published counter
func (m *AbandonedMarkerMetrics) AddEventsPublished(n int64) {
	atomic.AddInt64(&m.EventsPublished, n)
}

// IncErrors increments the errors counter
func (m *AbandonedMarkerMetrics) IncErrors() {
	atomic.AddInt64(&m.Errors, 1)
}

// SetLastRunAt sets the last run timestamp
func (m *AbandonedMarkerMetrics) SetLastRunAt(t time.Time) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.LastRunAt = t
}

// UpdateAvgLatency updates the average latency
func (m *AbandonedMarkerMetrics) UpdateAvgLatency(latencyMs float64) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.AvgLatencyMs == 0 {
		m.AvgLatencyMs = latencyMs
	} else {
		m.AvgLatencyMs = m.AvgLatencyMs*0.9 + latencyMs*0.1
	}
}

// Snapshot returns a snapshot of the metrics
func (m *AbandonedMarkerMetrics) Snapshot() AbandonedMarkerSnapshot {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return AbandonedMarkerSnapshot{
		CartsScanned:         atomic.LoadInt64(&m.CartsScanned),
		CartsMarkedAbandoned: atomic.LoadInt64(&m.CartsMarkedAbandoned),
		CartsReconciled:      atomic.LoadInt64(&m.CartsReconciled),
		EventsPublished:      atomic.LoadInt64(&m.EventsPublished),
		Errors:               atomic.LoadInt64(&m.Errors),
		LastRunAt:            m.LastRunAt,
		AvgLatencyMs:         m.AvgLatencyMs,
	}
}

// AbandonedMarkerSnapshot is a snapshot of abandoned marker metrics
type AbandonedMarkerSnapshot struct {
	CartsScanned         int64     `json:"carts_scanned"`
	CartsMarkedAbandoned int64     `json:"carts_marked_abandoned"`
	CartsReconciled      int64     `json:"carts_reconciled"`
	EventsPublished      int64     `json:"events_published"`
	Errors               int64     `json:"errors"`
	LastRunAt            time.Time `json:"last_run_at"`
	AvgLatencyMs         float64   `json:"avg_latency_ms"`
}

// ArchivalCleanerMetrics holds metrics for the archival cleaner
type ArchivalCleanerMetrics struct {
	mu                    sync.RWMutex
	CartsScanned         int64
	CartsDeleted         int64
	ItemsDeleted         int64
	Errors               int64
	LastRunAt            time.Time
	AvgLatencyMs         float64
}

// IncCartsScanned increments the carts scanned counter
func (m *ArchivalCleanerMetrics) IncCartsScanned() {
	atomic.AddInt64(&m.CartsScanned, 1)
}

// AddCartsScanned adds to the carts scanned counter
func (m *ArchivalCleanerMetrics) AddCartsScanned(n int64) {
	atomic.AddInt64(&m.CartsScanned, n)
}

// IncCartsDeleted increments the carts deleted counter
func (m *ArchivalCleanerMetrics) IncCartsDeleted() {
	atomic.AddInt64(&m.CartsDeleted, 1)
}

// AddCartsDeleted adds to the carts deleted counter
func (m *ArchivalCleanerMetrics) AddCartsDeleted(n int64) {
	atomic.AddInt64(&m.CartsDeleted, n)
}

// AddItemsDeleted adds to the items deleted counter
func (m *ArchivalCleanerMetrics) AddItemsDeleted(n int64) {
	atomic.AddInt64(&m.ItemsDeleted, n)
}

// IncErrors increments the errors counter
func (m *ArchivalCleanerMetrics) IncErrors() {
	atomic.AddInt64(&m.Errors, 1)
}

// SetLastRunAt sets the last run timestamp
func (m *ArchivalCleanerMetrics) SetLastRunAt(t time.Time) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.LastRunAt = t
}

// UpdateAvgLatency updates the average latency
func (m *ArchivalCleanerMetrics) UpdateAvgLatency(latencyMs float64) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.AvgLatencyMs == 0 {
		m.AvgLatencyMs = latencyMs
	} else {
		m.AvgLatencyMs = m.AvgLatencyMs*0.9 + latencyMs*0.1
	}
}

// Snapshot returns a snapshot of the metrics
func (m *ArchivalCleanerMetrics) Snapshot() ArchivalCleanerSnapshot {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return ArchivalCleanerSnapshot{
		CartsScanned:  atomic.LoadInt64(&m.CartsScanned),
		CartsDeleted:  atomic.LoadInt64(&m.CartsDeleted),
		ItemsDeleted:  atomic.LoadInt64(&m.ItemsDeleted),
		Errors:        atomic.LoadInt64(&m.Errors),
		LastRunAt:     m.LastRunAt,
		AvgLatencyMs:  m.AvgLatencyMs,
	}
}

// ArchivalCleanerSnapshot is a snapshot of archival cleaner metrics
type ArchivalCleanerSnapshot struct {
	CartsScanned int64     `json:"carts_scanned"`
	CartsDeleted int64     `json:"carts_deleted"`
	ItemsDeleted int64     `json:"items_deleted"`
	Errors       int64     `json:"errors"`
	LastRunAt    time.Time `json:"last_run_at"`
	AvgLatencyMs float64   `json:"avg_latency_ms"`
}

// StreamTrimmerMetrics holds metrics for the stream trimmer
type StreamTrimmerMetrics struct {
	mu                   sync.RWMutex
	StreamsScanned      int64
	StreamsTrimmed      int64
	StreamsDeleted      int64
	MessagesRemoved     int64
	StreamsSkippedNoGroup int64
	Errors              int64
	LastRunAt           time.Time
	AvgLatencyMs        float64
}

// IncStreamsScanned increments the streams scanned counter
func (m *StreamTrimmerMetrics) IncStreamsScanned() {
	atomic.AddInt64(&m.StreamsScanned, 1)
}

// AddStreamsScanned adds to the streams scanned counter
func (m *StreamTrimmerMetrics) AddStreamsScanned(n int64) {
	atomic.AddInt64(&m.StreamsScanned, n)
}

// IncStreamsTrimmed increments the streams trimmed counter
func (m *StreamTrimmerMetrics) IncStreamsTrimmed() {
	atomic.AddInt64(&m.StreamsTrimmed, 1)
}

// AddStreamsTrimmed adds to the streams trimmed counter
func (m *StreamTrimmerMetrics) AddStreamsTrimmed(n int64) {
	atomic.AddInt64(&m.StreamsTrimmed, n)
}

// IncStreamsDeleted increments the streams deleted counter
func (m *StreamTrimmerMetrics) IncStreamsDeleted() {
	atomic.AddInt64(&m.StreamsDeleted, 1)
}

// AddStreamsDeleted adds to the streams deleted counter
func (m *StreamTrimmerMetrics) AddStreamsDeleted(n int64) {
	atomic.AddInt64(&m.StreamsDeleted, n)
}

// AddMessagesRemoved adds to the messages removed counter
func (m *StreamTrimmerMetrics) AddMessagesRemoved(n int64) {
	atomic.AddInt64(&m.MessagesRemoved, n)
}

// IncStreamsSkippedNoGroup increments the streams skipped counter
func (m *StreamTrimmerMetrics) IncStreamsSkippedNoGroup() {
	atomic.AddInt64(&m.StreamsSkippedNoGroup, 1)
}

// AddStreamsSkippedNoGroup adds to the streams skipped counter
func (m *StreamTrimmerMetrics) AddStreamsSkippedNoGroup(n int64) {
	atomic.AddInt64(&m.StreamsSkippedNoGroup, n)
}

// IncErrors increments the errors counter
func (m *StreamTrimmerMetrics) IncErrors() {
	atomic.AddInt64(&m.Errors, 1)
}

// SetLastRunAt sets the last run timestamp
func (m *StreamTrimmerMetrics) SetLastRunAt(t time.Time) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.LastRunAt = t
}

// UpdateAvgLatency updates the average latency
func (m *StreamTrimmerMetrics) UpdateAvgLatency(latencyMs float64) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.AvgLatencyMs == 0 {
		m.AvgLatencyMs = latencyMs
	} else {
		m.AvgLatencyMs = m.AvgLatencyMs*0.9 + latencyMs*0.1
	}
}

// Snapshot returns a snapshot of the metrics
func (m *StreamTrimmerMetrics) Snapshot() StreamTrimmerSnapshot {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return StreamTrimmerSnapshot{
		StreamsScanned:       atomic.LoadInt64(&m.StreamsScanned),
		StreamsTrimmed:      atomic.LoadInt64(&m.StreamsTrimmed),
		StreamsDeleted:      atomic.LoadInt64(&m.StreamsDeleted),
		MessagesRemoved:     atomic.LoadInt64(&m.MessagesRemoved),
		StreamsSkippedNoGroup: atomic.LoadInt64(&m.StreamsSkippedNoGroup),
		Errors:              atomic.LoadInt64(&m.Errors),
		LastRunAt:           m.LastRunAt,
		AvgLatencyMs:        m.AvgLatencyMs,
	}
}

// StreamTrimmerSnapshot is a snapshot of stream trimmer metrics
type StreamTrimmerSnapshot struct {
	StreamsScanned         int64     `json:"streams_scanned"`
	StreamsTrimmed         int64     `json:"streams_trimmed"`
	StreamsDeleted         int64     `json:"streams_deleted"`
	MessagesRemoved        int64     `json:"messages_removed"`
	StreamsSkippedNoGroup  int64     `json:"streams_skipped_no_group"`
	Errors                 int64     `json:"errors"`
	LastRunAt              time.Time `json:"last_run_at"`
	AvgLatencyMs           float64   `json:"avg_latency_ms"`
}

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
		MessagesSkipped:  atomic.LoadInt64(&m.MessagesSkipped),
		CartsCleared:    atomic.LoadInt64(&m.CartsCleared),
		Errors:          atomic.LoadInt64(&m.Errors),
		DLQMessages:     atomic.LoadInt64(&m.DLQMessages),
		LastProcessedAt: m.LastProcessedAt,
		AvgLatencyMs:    m.AvgLatencyMs,
	}
}

// OrderConsumerSnapshot is a snapshot of order consumer metrics
type OrderConsumerSnapshot struct {
	MessagesReceived  int64     `json:"messages_received"`
	MessagesProcessed int64     `json:"messages_processed"`
	MessagesSkipped  int64     `json:"messages_skipped"`
	CartsCleared     int64     `json:"carts_cleared"`
	Errors           int64     `json:"errors"`
	DLQMessages      int64     `json:"dlq_messages"`
	LastProcessedAt  time.Time `json:"last_processed_at"`
	AvgLatencyMs     float64   `json:"avg_latency_ms"`
}

// DLQHandlerMetrics holds metrics for the DLQ handler
type DLQHandlerMetrics struct {
	mu                    sync.RWMutex
	MessagesProcessed     int64
	MessagesReplayed      int64
	MessagesQuarantined   int64
	MessagesTrimmed       int64
	Errors                int64
	LastRunAt             time.Time
	CurrentDLQLength      int64
	AvgLatencyMs          float64
}

// IncMessagesProcessed increments the messages processed counter
func (m *DLQHandlerMetrics) IncMessagesProcessed() {
	atomic.AddInt64(&m.MessagesProcessed, 1)
}

// AddMessagesProcessed adds to the messages processed counter
func (m *DLQHandlerMetrics) AddMessagesProcessed(n int64) {
	atomic.AddInt64(&m.MessagesProcessed, n)
}

// IncMessagesReplayed increments the messages replayed counter
func (m *DLQHandlerMetrics) IncMessagesReplayed() {
	atomic.AddInt64(&m.MessagesReplayed, 1)
}

// IncMessagesQuarantined increments the messages quarantined counter
func (m *DLQHandlerMetrics) IncMessagesQuarantined() {
	atomic.AddInt64(&m.MessagesQuarantined, 1)
}

// AddMessagesQuarantined adds to the messages quarantined counter
func (m *DLQHandlerMetrics) AddMessagesQuarantined(n int64) {
	atomic.AddInt64(&m.MessagesQuarantined, n)
}

// IncMessagesTrimmed increments the messages trimmed counter
func (m *DLQHandlerMetrics) IncMessagesTrimmed() {
	atomic.AddInt64(&m.MessagesTrimmed, 1)
}

// AddMessagesTrimmed adds to the messages trimmed counter
func (m *DLQHandlerMetrics) AddMessagesTrimmed(n int64) {
	atomic.AddInt64(&m.MessagesTrimmed, n)
}

// IncErrors increments the errors counter
func (m *DLQHandlerMetrics) IncErrors() {
	atomic.AddInt64(&m.Errors, 1)
}

// SetLastRunAt sets the last run timestamp
func (m *DLQHandlerMetrics) SetLastRunAt(t time.Time) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.LastRunAt = t
}

// SetCurrentDLQLength sets the current DLQ length
func (m *DLQHandlerMetrics) SetCurrentDLQLength(length int64) {
	atomic.StoreInt64(&m.CurrentDLQLength, length)
}

// UpdateAvgLatency updates the average latency
func (m *DLQHandlerMetrics) UpdateAvgLatency(latencyMs float64) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.AvgLatencyMs == 0 {
		m.AvgLatencyMs = latencyMs
	} else {
		m.AvgLatencyMs = m.AvgLatencyMs*0.9 + latencyMs*0.1
	}
}

// Snapshot returns a snapshot of the metrics
func (m *DLQHandlerMetrics) Snapshot() DLQHandlerSnapshot {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return DLQHandlerSnapshot{
		MessagesProcessed:   atomic.LoadInt64(&m.MessagesProcessed),
		MessagesReplayed:   atomic.LoadInt64(&m.MessagesReplayed),
		MessagesQuarantined: atomic.LoadInt64(&m.MessagesQuarantined),
		MessagesTrimmed:    atomic.LoadInt64(&m.MessagesTrimmed),
		Errors:             atomic.LoadInt64(&m.Errors),
		LastRunAt:          m.LastRunAt,
		CurrentDLQLength:   atomic.LoadInt64(&m.CurrentDLQLength),
		AvgLatencyMs:       m.AvgLatencyMs,
	}
}

// DLQHandlerSnapshot is a snapshot of DLQ handler metrics
type DLQHandlerSnapshot struct {
	MessagesProcessed    int64     `json:"messages_processed"`
	MessagesReplayed    int64     `json:"messages_replayed"`
	MessagesQuarantined int64     `json:"messages_quarantined"`
	MessagesTrimmed     int64     `json:"messages_trimmed"`
	Errors              int64     `json:"errors"`
	LastRunAt           time.Time `json:"last_run_at"`
	CurrentDLQLength    int64     `json:"current_dlq_length"`
	AvgLatencyMs        float64   `json:"avg_latency_ms"`
}
