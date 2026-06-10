package postgres_sync

import (
	"context"
	"fmt"
	"time"

	"go.uber.org/zap"

	"cart-service/internal/domain/entity"
	"cart-service/internal/domain/event"
	"cart-service/internal/domain/port"
)

// CartEventBatch holds a batch of cart events to be processed together.
type CartEventBatch struct {
	CartID  string
	Events  []*event.CartEventEnvelope
	Metrics *BatchMetrics
}

// BatchMetrics holds metrics for a single batch processing run.
type BatchMetrics struct {
	ItemsUpserted int
	ItemsDeleted  int
	StatusUpdated int
	Errors        int
}

// NewCartEventBatch creates a new batch for a cart.
func NewCartEventBatch(cartID string) *CartEventBatch {
	return &CartEventBatch{
		CartID:  cartID,
		Events:  make([]*event.CartEventEnvelope, 0),
		Metrics: &BatchMetrics{},
	}
}

// Add adds an event to the batch.
func (b *CartEventBatch) Add(env *event.CartEventEnvelope) {
	b.Events = append(b.Events, env)
}

// ProcessBatch processes all events in a cart batch using per-cart transactions.
type ProcessBatch struct {
	cartRepo port.CartRepository
	logger   *zap.Logger
}

// NewProcessBatch creates a new batch processor.
func NewProcessBatch(cartRepo port.CartRepository, logger *zap.Logger) *ProcessBatch {
	return &ProcessBatch{
		cartRepo: cartRepo,
		logger:   logger,
	}
}

// Process processes a batch of events for a single cart.
func (p *ProcessBatch) Process(ctx context.Context, batch *CartEventBatch) error {
	for _, env := range batch.Events {
		if err := p.processEvent(ctx, env); err != nil {
			batch.Metrics.Errors++
			p.logger.Error("failed to process event",
				zap.String("cart_id", batch.CartID),
				zap.String("event_type", env.EventType),
				zap.String("stream_id", env.StreamID),
				zap.Error(err),
			)
			continue
		}
	}
	return nil
}

func (p *ProcessBatch) processEvent(ctx context.Context, env *event.CartEventEnvelope) error {
	switch env.EventType {
	case event.EventTypeCartItemAdded, event.EventTypeCartItemUpdated:
		return p.handleItemUpsert(ctx, env)
	case event.EventTypeCartItemDeleted:
		return p.handleItemDelete(ctx, env)
	case event.EventTypeCartCleared:
		return p.handleCartCleared(ctx, env)
	case event.EventTypeCartCheckedOut:
		return p.handleCartCheckedOut(ctx, env)
	case event.EventTypeCartAbandoned:
		return p.handleCartAbandoned(ctx, env)
	default:
		return nil
	}
}

func (p *ProcessBatch) handleItemUpsert(ctx context.Context, env *event.CartEventEnvelope) error {
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

	if err := p.cartRepo.UpsertCartItem(ctx, item, env.StreamID); err != nil {
		return fmt.Errorf("upsert cart item: %w", err)
	}

	cart := entity.RestoreCart(
		payload.CartID, payload.UserID,
		entity.CartStatusActive, env.StreamID,
		time.Now(), time.Now(),
	)
	return p.cartRepo.UpsertCartMaster(ctx, cart, env.StreamID)
}

func (p *ProcessBatch) handleItemDelete(ctx context.Context, env *event.CartEventEnvelope) error {
	payload, err := ParseDeletedPayloadFromEnvelope(env)
	if err != nil {
		return err
	}
	itemID := payload.CartID + ":" + payload.SkuID
	_, err = p.cartRepo.DeleteCartItem(ctx, itemID, env.StreamID)
	return err
}

func (p *ProcessBatch) handleCartCleared(ctx context.Context, env *event.CartEventEnvelope) error {
	payload, err := ParseClearedPayloadFromEnvelope(env)
	if err != nil {
		return err
	}
	_, err = p.cartRepo.UpdateCartStatus(ctx, payload.CartID, entity.CartStatusDeleted, env.StreamID)
	return err
}

func (p *ProcessBatch) handleCartCheckedOut(ctx context.Context, env *event.CartEventEnvelope) error {
	payload, err := ParseCheckedOutPayloadFromEnvelope(env)
	if err != nil {
		return err
	}
	_, err = p.cartRepo.UpdateCartStatus(ctx, payload.CartID, entity.CartStatusCheckedOut, env.StreamID)
	return err
}

func (p *ProcessBatch) handleCartAbandoned(ctx context.Context, env *event.CartEventEnvelope) error {
	payload, err := ParseAbandonedPayloadFromEnvelope(env)
	if err != nil {
		return err
	}
	_, err = p.cartRepo.UpdateCartStatus(ctx, payload.CartID, entity.CartStatusAbandoned, env.StreamID)
	return err
}
