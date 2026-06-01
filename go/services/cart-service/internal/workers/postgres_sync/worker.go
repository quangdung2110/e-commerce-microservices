package postgres_sync

import (
	"context"
	"fmt"
	"sync"
	"time"

	"go.uber.org/zap"

	"cart-service/internal/domain/entity"
	"cart-service/internal/domain/event"
	"cart-service/internal/domain/port"
	"cart-service/internal/infrastructure/config"
	"cart-service/internal/infrastructure/kafka"
)

// Worker represents the Postgres Sync consumer worker.
// It consumes cart events from Kafka and persists them to PostgreSQL cold storage.
type Worker struct {
	consumer  *kafka.Consumer
	cartRepo  port.CartRepository
	cfg       config.PostgresSyncCfg
	logger    *zap.Logger
	metrics   *Metrics
	wg        sync.WaitGroup
	stopCh    chan struct{}
	batchSize int
	timeout   time.Duration
}

// NewWorker creates a new Postgres Sync worker.
func NewWorker(
	consumer *kafka.Consumer,
	cartRepo port.CartRepository,
	cfg config.PostgresSyncCfg,
	logger *zap.Logger,
) *Worker {
	return &Worker{
		consumer:  consumer,
		cartRepo:  cartRepo,
		cfg:       cfg,
		logger:    logger,
		metrics:   NewMetrics(logger),
		stopCh:    make(chan struct{}),
		batchSize: cfg.BatchSize,
		timeout:   5 * time.Second,
	}
}

// NewWorkerWithTimeouts creates a new Postgres Sync worker with custom timeouts.
func NewWorkerWithTimeouts(
	consumer *kafka.Consumer,
	cartRepo port.CartRepository,
	cfg config.PostgresSyncCfg,
	logger *zap.Logger,
	batchSize int,
	timeout time.Duration,
) *Worker {
	return &Worker{
		consumer:  consumer,
		cartRepo:  cartRepo,
		cfg:       cfg,
		logger:    logger,
		metrics:   NewMetrics(logger),
		stopCh:    make(chan struct{}),
		batchSize: batchSize,
		timeout:   timeout,
	}
}

// Start begins consuming Kafka messages.
func (w *Worker) Start(ctx context.Context) error {
	w.wg.Add(1)
	go w.consumeLoop(ctx)
	w.logger.Info("postgres sync worker started",
		zap.Int("batch_size", w.batchSize),
		zap.Duration("timeout", w.timeout),
	)
	return nil
}

// Stop gracefully stops the worker.
func (w *Worker) Stop() {
	close(w.stopCh)
	w.wg.Wait()
	w.logger.Info("postgres sync worker stopped")
}

func (w *Worker) consumeLoop(ctx context.Context) {
	defer w.wg.Done()

	for {
		select {
		case <-ctx.Done():
			return
		case <-w.stopCh:
			return
		default:
			w.processBatch(ctx)
		}
	}
}

func (w *Worker) processBatch(ctx context.Context) {
	batch, err := w.consumer.PollBatch(ctx, w.batchSize, w.timeout)
	if err != nil {
		if err == context.DeadlineExceeded || err == context.Canceled {
			return
		}
		w.logger.Warn("poll error", zap.Error(err))
		return
	}

	if batch.Count() == 0 {
		return
	}

	cartBatches := groupByCartID(batch)

	for cartID, events := range cartBatches {
		w.processCartBatch(ctx, cartID, events)
	}

	batch.Commit()
}

func groupByCartID(batch *kafka.MessageBatch) map[string][]*event.CartEventEnvelope {
	result := make(map[string][]*event.CartEventEnvelope)
	for _, msg := range batch.Messages() {
		env, err := kafka.ParseCartEvent(msg)
		if err != nil {
			continue
		}
		cartID := env.AggregateID
		result[cartID] = append(result[cartID], env)
	}
	return result
}

func (w *Worker) processCartBatch(ctx context.Context, cartID string, events []*event.CartEventEnvelope) {
	for _, env := range events {
		if err := w.processEvent(ctx, env); err != nil {
			w.logger.Error("failed to process event",
				zap.String("cart_id", cartID),
				zap.String("event_type", env.EventType),
				zap.String("stream_id", env.StreamID),
				zap.Error(err),
			)
			w.metrics.RecordFailed(env.EventType)
			continue
		}
		w.metrics.RecordProcessed(env.EventType)
	}
}

func (w *Worker) processEvent(ctx context.Context, env *event.CartEventEnvelope) error {
	switch env.EventType {
	case event.EventTypeCartItemAdded, event.EventTypeCartItemUpdated:
		return w.handleItemUpsert(ctx, env)
	case event.EventTypeCartItemDeleted:
		return w.handleItemDelete(ctx, env)
	case event.EventTypeCartCleared:
		return w.handleCartCleared(ctx, env)
	case event.EventTypeCartCheckedOut:
		return w.handleCartCheckedOut(ctx, env)
	case event.EventTypeCartAbandoned:
		return w.handleCartAbandoned(ctx, env)
	default:
		return nil
	}
}

func (w *Worker) handleItemUpsert(ctx context.Context, env *event.CartEventEnvelope) error {
	payload, err := ParseItemPayloadFromEnvelope(env)
	if err != nil {
		return fmt.Errorf("parse item payload: %w", err)
	}

	item := entity.RestoreCartItem(
		payload.CartID+":"+payload.SkuID,
		payload.CartID,
		payload.ProductID,
		payload.SkuID,
		payload.Quantity,
		payload.UnitPriceAtAdd,
		entity.ProductSnapshot{
			ProductID:   payload.ProductID,
			ProductName: payload.ProductName,
			VariantInfo: payload.VariantInfo,
			ImageURL:    payload.ImageURL,
			IsAvailable: payload.IsAvailable,
			AddedAt:     payload.AddedAt,
			UpdatedAt:   payload.UpdatedAt,
		},
		env.StreamID,
		time.Now(), time.Now(),
	)

	if err := w.cartRepo.UpsertCartItem(ctx, item, env.StreamID); err != nil {
		return fmt.Errorf("upsert cart item: %w", err)
	}

	cart := entity.RestoreCart(
		payload.CartID, payload.UserID,
		entity.CartStatusActive, env.StreamID,
		time.Now(), time.Now(),
	)
	return w.cartRepo.UpsertCartMaster(ctx, cart, env.StreamID)
}

func (w *Worker) handleItemDelete(ctx context.Context, env *event.CartEventEnvelope) error {
	payload, err := ParseDeletedPayloadFromEnvelope(env)
	if err != nil {
		return fmt.Errorf("parse delete payload: %w", err)
	}

	itemID := payload.CartID + ":" + payload.SkuID
	_, err = w.cartRepo.DeleteCartItem(ctx, itemID, env.StreamID)
	return err
}

func (w *Worker) handleCartCleared(ctx context.Context, env *event.CartEventEnvelope) error {
	payload, err := ParseItemPayloadFromEnvelope(env)
	if err != nil {
		return fmt.Errorf("parse cleared payload: %w", err)
	}
	_, err = w.cartRepo.UpdateCartStatus(ctx, payload.CartID, entity.CartStatusDeleted, env.StreamID)
	return err
}

func (w *Worker) handleCartCheckedOut(ctx context.Context, env *event.CartEventEnvelope) error {
	payload, err := ParseItemPayloadFromEnvelope(env)
	if err != nil {
		return fmt.Errorf("parse checked_out payload: %w", err)
	}
	_, err = w.cartRepo.UpdateCartStatus(ctx, payload.CartID, entity.CartStatusCheckedOut, env.StreamID)
	return err
}

func (w *Worker) handleCartAbandoned(ctx context.Context, env *event.CartEventEnvelope) error {
	payload, err := ParseAbandonedPayloadFromEnvelope(env)
	if err != nil {
		return fmt.Errorf("parse abandoned payload: %w", err)
	}
	_, err = w.cartRepo.UpdateCartStatus(ctx, payload.CartID, entity.CartStatusAbandoned, env.StreamID)
	return err
}

// Consumer returns the underlying Kafka consumer.
func (w *Worker) Consumer() *kafka.Consumer {
	return w.consumer
}

// Metrics returns the worker's metrics.
func (w *Worker) Metrics() *Metrics {
	return w.metrics
}

// BatchProcessor handles batch processing logic for postgres_sync.
type BatchProcessor struct {
	cartRepo port.CartRepository
	logger   *zap.Logger
	metrics  *Metrics
}

// NewBatchProcessor creates a new batch processor.
func NewBatchProcessor(cartRepo port.CartRepository, logger *zap.Logger) *BatchProcessor {
	return &BatchProcessor{
		cartRepo: cartRepo,
		logger:   logger,
		metrics:  NewMetrics(logger),
	}
}

// ProcessCartEvents processes all events for a single cart in a transaction.
func (bp *BatchProcessor) ProcessCartEvents(
	ctx context.Context,
	cartID string,
	events []*event.CartEventEnvelope,
) error {
	for _, env := range events {
		if err := bp.processEvent(ctx, cartID, env); err != nil {
			bp.logger.Error("failed to process event",
				zap.String("cart_id", cartID),
				zap.String("event_type", env.EventType),
				zap.Error(err),
			)
			bp.metrics.RecordFailed(env.EventType)
			continue
		}
		bp.metrics.RecordProcessed(env.EventType)
	}
	return nil
}

func (bp *BatchProcessor) processEvent(ctx context.Context, cartID string, env *event.CartEventEnvelope) error {
	switch env.EventType {
	case event.EventTypeCartItemAdded, event.EventTypeCartItemUpdated:
		return bp.handleItemUpsert(ctx, env)
	case event.EventTypeCartItemDeleted:
		return bp.handleItemDelete(ctx, env)
	case event.EventTypeCartCleared:
		return bp.handleCartCleared(ctx, env)
	case event.EventTypeCartCheckedOut:
		return bp.handleCartCheckedOut(ctx, env)
	case event.EventTypeCartAbandoned:
		return bp.handleCartAbandoned(ctx, env)
	default:
		return nil
	}
}

func (bp *BatchProcessor) handleItemUpsert(ctx context.Context, env *event.CartEventEnvelope) error {
	payload, err := ParseItemPayloadFromEnvelope(env)
	if err != nil {
		return err
	}

	item := entity.RestoreCartItem(
		payload.CartID+":"+payload.SkuID,
		payload.CartID,
		payload.ProductID,
		payload.SkuID,
		payload.Quantity,
		payload.UnitPriceAtAdd,
		entity.ProductSnapshot{
			ProductID:   payload.ProductID,
			ProductName: payload.ProductName,
			VariantInfo: payload.VariantInfo,
			ImageURL:    payload.ImageURL,
			IsAvailable: payload.IsAvailable,
			AddedAt:     payload.AddedAt,
			UpdatedAt:   payload.UpdatedAt,
		},
		env.StreamID,
		time.Now(), time.Now(),
	)

	if err := bp.cartRepo.UpsertCartItem(ctx, item, env.StreamID); err != nil {
		return err
	}

	cart := entity.RestoreCart(
		payload.CartID, payload.UserID,
		entity.CartStatusActive, env.StreamID,
		time.Now(), time.Now(),
	)
	return bp.cartRepo.UpsertCartMaster(ctx, cart, env.StreamID)
}

func (bp *BatchProcessor) handleItemDelete(ctx context.Context, env *event.CartEventEnvelope) error {
	payload, err := ParseDeletedPayloadFromEnvelope(env)
	if err != nil {
		return err
	}
	itemID := payload.CartID + ":" + payload.SkuID
	_, err = bp.cartRepo.DeleteCartItem(ctx, itemID, env.StreamID)
	return err
}

func (bp *BatchProcessor) handleCartCleared(ctx context.Context, env *event.CartEventEnvelope) error {
	payload, err := ParseItemPayloadFromEnvelope(env)
	if err != nil {
		return err
	}
	_, err = bp.cartRepo.UpdateCartStatus(ctx, payload.CartID, entity.CartStatusDeleted, env.StreamID)
	return err
}

func (bp *BatchProcessor) handleCartCheckedOut(ctx context.Context, env *event.CartEventEnvelope) error {
	payload, err := ParseItemPayloadFromEnvelope(env)
	if err != nil {
		return err
	}
	_, err = bp.cartRepo.UpdateCartStatus(ctx, payload.CartID, entity.CartStatusCheckedOut, env.StreamID)
	return err
}

func (bp *BatchProcessor) handleCartAbandoned(ctx context.Context, env *event.CartEventEnvelope) error {
	payload, err := ParseAbandonedPayloadFromEnvelope(env)
	if err != nil {
		return err
	}
	_, err = bp.cartRepo.UpdateCartStatus(ctx, payload.CartID, entity.CartStatusAbandoned, env.StreamID)
	return err
}
