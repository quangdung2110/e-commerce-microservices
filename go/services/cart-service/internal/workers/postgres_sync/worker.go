package postgres_sync

import (
	"context"
	"fmt"
	"sync"
	"sync/atomic"
	"time"

	"github.com/redis/go-redis/v9"
	"go.uber.org/zap"
	"gorm.io/driver/postgres"
	"gorm.io/gorm"

	"github.com/ecommerce-platform/go/services/cart-service/internal/infrastructure/config"
)

// Worker is the main Postgres Sync worker
type Worker struct {
	db             *gorm.DB
	redisClient   *redis.Client
	consumer       *KafkaConsumer
	batchProcessor *BatchProcessor
	metrics       *WorkerMetrics
	logger        *zap.Logger
	config        *config.Config

	stopCh chan struct{}
	wg     sync.WaitGroup
}

// WorkerMetrics holds metrics for the worker
type WorkerMetrics struct {
	mu                  sync.RWMutex
	EventsProcessed     int64
	EventsFailed       int64
	BatchesProcessed   int64
	TransactionsSucceeded int64
	TransactionsFailed int64
	LastProcessedAt    time.Time
	AvgLatencyMs       float64
}

// NewWorker creates a new Postgres Sync worker
func NewWorker(
	cfg *config.Config,
	logger *zap.Logger,
	redisClient *redis.Client,
) (*Worker, error) {
	// Connect to PostgreSQL
	dsn := cfg.Postgres.DSN
	if dsn == "" {
		dsn = buildDSN(cfg.Postgres)
	}

	gormConfig := &gorm.Config{}

	db, err := gorm.Open(postgres.Open(dsn), gormConfig)
	if err != nil {
		return nil, fmt.Errorf("failed to connect to PostgreSQL: %w", err)
	}

	// Configure connection pool
	sqlDB, err := db.DB()
	if err != nil {
		return nil, fmt.Errorf("failed to get underlying sql.DB: %w", err)
	}

	sqlDB.SetMaxOpenConns(cfg.Postgres.MaxOpenConns)
	sqlDB.SetMaxIdleConns(cfg.Postgres.MaxIdleConns)

	if cfg.Postgres.ConnMaxLifetime != "" {
		lifetime, err := time.ParseDuration(cfg.Postgres.ConnMaxLifetime)
		if err == nil {
			sqlDB.SetConnMaxLifetime(lifetime)
		}
	}

	// Run migrations
	if err := Migrate(db); err != nil {
		return nil, fmt.Errorf("failed to run migrations: %w", err)
	}

	// Create batch processor with Redis for DLQ support
	var batchProcessor *BatchProcessor
	if redisClient != nil {
		batchProcessor = NewBatchProcessorWithRedis(db, redisClient, logger)
	} else {
		batchProcessor = NewBatchProcessor(db, logger)
	}

	// Create Kafka consumer
	consumer, err := NewKafkaConsumer(
		cfg.Kafka.Brokers,
		cfg.Kafka.Topic,
		cfg.Kafka.ConsumerGroup,
		cfg.Kafka.Consumer,
		logger,
	)
	if err != nil {
		return nil, fmt.Errorf("failed to create Kafka consumer: %w", err)
	}

	return &Worker{
		db:              db,
		redisClient:     redisClient,
		consumer:        consumer,
		batchProcessor:  batchProcessor,
		metrics:         &WorkerMetrics{},
		logger:          logger,
		config:          cfg,
		stopCh:         make(chan struct{}),
	}, nil
}

// buildDSN builds a PostgreSQL DSN from config
func buildDSN(cfg config.PostgresConfig) string {
	return fmt.Sprintf(
		"host=localhost port=5432 user=postgres password=postgres dbname=cart_service sslmode=disable",
	)
}

// Start starts the worker
func (w *Worker) Start(ctx context.Context) error {
	w.logger.Info("Starting Postgres Sync worker")

	// Set up consumer handlers
	w.consumer.SetHandlers(w)

	// Start Kafka consumer
	if err := w.consumer.Start(ctx); err != nil {
		return fmt.Errorf("failed to start consumer: %w", err)
	}

	w.logger.Info("Postgres Sync worker started successfully")
	return nil
}

// Stop stops the worker gracefully
func (w *Worker) Stop() {
	w.logger.Info("Stopping Postgres Sync worker...")

	close(w.stopCh)

	// Stop Kafka consumer
	if err := w.consumer.Stop(); err != nil {
		w.logger.Error("Failed to stop consumer", zap.Error(err))
	}

	// Close Redis connection
	if w.redisClient != nil {
		if err := w.redisClient.Close(); err != nil {
			w.logger.Error("Failed to close Redis connection", zap.Error(err))
		}
	}

	// Close database connection
	sqlDB, err := w.db.DB()
	if err == nil {
		sqlDB.Close()
	}

	w.logger.Info("Postgres Sync worker stopped")
}

// HandleBatch implements ConsumerHandlers
func (w *Worker) HandleBatch(ctx context.Context, messages []*ConsumedMessage) error {
	start := time.Now()

	// Parse events from messages
	events := make([]*CartEvent, 0, len(messages))
	for _, msg := range messages {
		event, err := ParseCartEvent(msg.Value)
		if err != nil {
			w.logger.Warn("Failed to parse event",
				zap.Int32("partition", msg.Partition),
				zap.Int64("offset", msg.Offset),
				zap.Error(err),
			)
			// Count as processed but failed
			w.metrics.IncEventsFailed()
			continue
		}
		events = append(events, event)
	}

	if len(events) == 0 {
		return nil
	}

	// Process events in batches
	results := w.batchProcessor.ProcessEvents(ctx, events)

	// Track results
	successCount := 0
	failCount := 0
	for _, result := range results {
		if result.Success {
			successCount++
			w.metrics.IncTransactionsSucceeded()
		} else {
			failCount++
			w.metrics.IncTransactionsFailed()
		}
	}

	// Update metrics
	w.metrics.AddEventsProcessed(int64(len(events)))
	w.metrics.AddEventsFailed(int64(len(messages) - len(events) - failCount))
	w.metrics.IncBatchesProcessed()
	w.metrics.SetLastProcessedAt(time.Now())

	// Update latency
	latencyMs := float64(time.Since(start).Milliseconds())
	w.metrics.UpdateAvgLatency(latencyMs)

	w.logger.Debug("Batch processed",
		zap.Int("events", len(events)),
		zap.Int("success", successCount),
		zap.Int("failed", failCount),
		zap.Float64("latency_ms", latencyMs),
	)

	return nil
}

// GetMetrics returns the worker metrics
func (w *Worker) GetMetrics() WorkerMetricsSnapshot {
	return w.metrics.Snapshot()
}

// IncEventsProcessed increments events processed counter
func (m *WorkerMetrics) IncEventsProcessed() {
	atomic.AddInt64(&m.EventsProcessed, 1)
}

// AddEventsProcessed adds to events processed counter
func (m *WorkerMetrics) AddEventsProcessed(n int64) {
	atomic.AddInt64(&m.EventsProcessed, n)
}

// IncEventsFailed increments events failed counter
func (m *WorkerMetrics) IncEventsFailed() {
	atomic.AddInt64(&m.EventsFailed, 1)
}

// AddEventsFailed adds to events failed counter
func (m *WorkerMetrics) AddEventsFailed(n int64) {
	atomic.AddInt64(&m.EventsFailed, n)
}

// IncBatchesProcessed increments batches processed counter
func (m *WorkerMetrics) IncBatchesProcessed() {
	atomic.AddInt64(&m.BatchesProcessed, 1)
}

// IncTransactionsSucceeded increments transactions succeeded counter
func (m *WorkerMetrics) IncTransactionsSucceeded() {
	atomic.AddInt64(&m.TransactionsSucceeded, 1)
}

// IncTransactionsFailed increments transactions failed counter
func (m *WorkerMetrics) IncTransactionsFailed() {
	atomic.AddInt64(&m.TransactionsFailed, 1)
}

// SetLastProcessedAt sets the last processed timestamp
func (m *WorkerMetrics) SetLastProcessedAt(t time.Time) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.LastProcessedAt = t
}

// UpdateAvgLatency updates the average latency
func (m *WorkerMetrics) UpdateAvgLatency(latencyMs float64) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.AvgLatencyMs == 0 {
		m.AvgLatencyMs = latencyMs
	} else {
		// Simple moving average
		m.AvgLatencyMs = m.AvgLatencyMs*0.9 + latencyMs*0.1
	}
}

// Snapshot returns a snapshot of the metrics
func (m *WorkerMetrics) Snapshot() WorkerMetricsSnapshot {
	return WorkerMetricsSnapshot{
		EventsProcessed:       atomic.LoadInt64(&m.EventsProcessed),
		EventsFailed:         atomic.LoadInt64(&m.EventsFailed),
		BatchesProcessed:    atomic.LoadInt64(&m.BatchesProcessed),
		TransactionsSucceeded: atomic.LoadInt64(&m.TransactionsSucceeded),
		TransactionsFailed:   atomic.LoadInt64(&m.TransactionsFailed),
		LastProcessedAt:      m.getLastProcessedAt(),
		AvgLatencyMs:        m.getAvgLatency(),
	}
}

func (m *WorkerMetrics) getLastProcessedAt() time.Time {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.LastProcessedAt
}

func (m *WorkerMetrics) getAvgLatency() float64 {
	m.mu.RLock()
	defer m.mu.RUnlock()
	return m.AvgLatencyMs
}

// WorkerMetricsSnapshot is a snapshot of worker metrics
type WorkerMetricsSnapshot struct {
	EventsProcessed        int64     `json:"events_processed"`
	EventsFailed         int64     `json:"events_failed"`
	BatchesProcessed    int64     `json:"batches_processed"`
	TransactionsSucceeded int64     `json:"transactions_succeeded"`
	TransactionsFailed  int64     `json:"transactions_failed"`
	LastProcessedAt      time.Time `json:"last_processed_at"`
	AvgLatencyMs         float64   `json:"avg_latency_ms"`
}
