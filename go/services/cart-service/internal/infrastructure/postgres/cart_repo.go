package postgres

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"gorm.io/gorm"
	"gorm.io/gorm/clause"

	"cart-service/internal/domain/entity"
	"cart-service/internal/domain/port"
)

// CartRepositoryImpl implements port.CartRepository using GORM.
// Ref: docs/cart/cart_db_schema.md, Module 07
type CartRepositoryImpl struct {
	db *gorm.DB
}

// NewCartRepository creates a new CartRepository.
func NewCartRepository(db *gorm.DB) *CartRepositoryImpl {
	return &CartRepositoryImpl{db: db}
}

var _ port.CartRepository = (*CartRepositoryImpl)(nil)

// GetCartStatus implements port.CartRepository.GetCartStatus.
func (r *CartRepositoryImpl) GetCartStatus(ctx context.Context, cartID string) (entity.CartStatus, error) {
	var model CartModel
	if err := r.db.WithContext(ctx).
		Select("status").
		First(&model, "cart_id = ?", cartID).Error; err != nil {
		if err == gorm.ErrRecordNotFound {
			return "", entity.ErrCartNotFound
		}
		return "", fmt.Errorf("GetCartStatus: %w", err)
	}
	return entity.CartStatus(model.Status), nil
}

// ListCartsForAbandonedMark implements port.CartRepository.ListCartsForAbandonedMark.
func (r *CartRepositoryImpl) ListCartsForAbandonedMark(
	ctx context.Context, inactiveDays, limit int,
) ([]*entity.Cart, error) {
	var models []CartModel
	threshold := time.Now().UTC().AddDate(0, 0, -inactiveDays)

	if err := r.db.WithContext(ctx).
		Where("status = ? AND updated_at < ?", entity.CartStatusActive, threshold).
		Order("updated_at ASC").
		Limit(limit).
		Find(&models).Error; err != nil {
		return nil, fmt.Errorf("ListCartsForAbandonedMark: %w", err)
	}

	carts := make([]*entity.Cart, len(models))
	for i := range models {
		carts[i] = models[i].ToEntity()
	}
	return carts, nil
}

// UpdateCartStatus implements port.CartRepository.UpdateCartStatus.
// Uses stream_id guard: only updates if new stream_id > current.
func (r *CartRepositoryImpl) UpdateCartStatus(
	ctx context.Context, cartID string, status entity.CartStatus, streamID string,
) (int64, error) {
	result := r.db.WithContext(ctx).
		Model(&CartModel{}).
		Where("cart_id = ? AND (last_event_stream_id IS NULL OR last_event_stream_id < ?)", cartID, streamID).
		Updates(map[string]interface{}{
			"status":               status,
			"last_event_stream_id": streamID,
			"updated_at":           time.Now().UTC(),
		})

	return result.RowsAffected, result.Error
}

// UpsertCartItem implements port.CartRepository.UpsertCartItem.
// Uses ON CONFLICT DO UPDATE WHERE redis_stream_id < newStreamID.
func (r *CartRepositoryImpl) UpsertCartItem(
	ctx context.Context, item *entity.CartItem, streamID string,
) error {
	model := ToCartItemModel(item)
	model.RedisStreamID = toNullString(streamID)

	return r.db.WithContext(ctx).Clauses(
		clause.OnConflict{
			Columns:   []clause.Column{{Name: "item_id"}},
			DoUpdates: clause.Assignments(map[string]interface{}{
				"quantity":         gorm.Expr("EXCLUDED.quantity"),
				"product_snapshot": gorm.Expr("EXCLUDED.product_snapshot"),
				"redis_stream_id":  gorm.Expr("EXCLUDED.redis_stream_id"),
				"updated_at":       gorm.Expr("NOW()"),
			}),
			Where: clause.Where{
				Exprs: []clause.Expression{
					clause.Expr{
						SQL:  "cart_items.redis_stream_id IS NULL OR cart_items.redis_stream_id < ?",
						Vars: []interface{}{streamID},
					},
				},
			},
		},
	).Create(model).Error
}

// BatchUpsertCartItems implements port.CartRepository.BatchUpsertCartItems.
// Groups items by cart_id and executes per-cart transactions.
func (r *CartRepositoryImpl) BatchUpsertCartItems(
	ctx context.Context, cartID string, items []*entity.CartItem, streamIDs []string,
) error {
	return r.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		for i, item := range items {
			model := ToCartItemModel(item)
			sid := streamIDs[i]
			if sid == "" {
				sid = item.StreamID()
			}
			model.RedisStreamID = toNullString(sid)

			err := tx.Clauses(
				clause.OnConflict{
					Columns:   []clause.Column{{Name: "item_id"}},
					DoUpdates: clause.Assignments(map[string]interface{}{
						"quantity":         gorm.Expr("EXCLUDED.quantity"),
						"product_snapshot": gorm.Expr("EXCLUDED.product_snapshot"),
						"redis_stream_id":  gorm.Expr("EXCLUDED.redis_stream_id"),
						"updated_at":       gorm.Expr("NOW()"),
					}),
					Where: clause.Where{
						Exprs: []clause.Expression{
							clause.Expr{
								SQL:  "cart_items.redis_stream_id IS NULL OR cart_items.redis_stream_id < ?",
								Vars: []interface{}{sid},
							},
						},
					},
				},
			).Create(model).Error

			if err != nil {
				return fmt.Errorf("upsert item %s: %w", item.ItemID(), err)
			}
		}

		if len(items) == 0 {
			return nil
		}

		// Upsert cart master with ACTIVE status
		err := tx.Exec(`
			INSERT INTO carts (cart_id, user_id, status, last_event_stream_id, created_at, updated_at)
			VALUES (?, ?, 'ACTIVE', ?, NOW(), NOW())
			ON CONFLICT (cart_id) DO UPDATE SET
				status = 'ACTIVE',
				last_event_stream_id = EXCLUDED.last_event_stream_id,
				updated_at = NOW()
			WHERE carts.last_event_stream_id IS NULL OR carts.last_event_stream_id < EXCLUDED.last_event_stream_id
		`, cartID, items[0].CartID(), streamIDs[len(streamIDs)-1]).Error

		return err
	})
}

// DeleteCartItem implements port.CartRepository.DeleteCartItem.
// Hard delete with stream_id guard.
func (r *CartRepositoryImpl) DeleteCartItem(
	ctx context.Context, itemID, streamID string,
) (int64, error) {
	result := r.db.WithContext(ctx).
		Exec(`
			DELETE FROM cart_items
			WHERE item_id = ?
			  AND (redis_stream_id IS NULL OR redis_stream_id < ?)
		`, itemID, streamID)

	return result.RowsAffected, result.Error
}

// BatchDeleteCartItems implements port.CartRepository.BatchDeleteCartItems.
func (r *CartRepositoryImpl) BatchDeleteCartItems(
	ctx context.Context, itemIDs []string, streamID string,
) (int64, error) {
	if len(itemIDs) == 0 {
		return 0, nil
	}

	placeholders := make([]string, len(itemIDs))
	args := make([]interface{}, len(itemIDs)+1)
	for i, id := range itemIDs {
		placeholders[i] = "?"
		args[i] = id
	}
	args[len(itemIDs)] = streamID

	query := fmt.Sprintf(`
		DELETE FROM cart_items
		WHERE item_id IN (%s)
		  AND (redis_stream_id IS NULL OR redis_stream_id < ?)
	`, strings.Join(placeholders, ","))

	result := r.db.WithContext(ctx).Exec(query, args...)
	return result.RowsAffected, result.Error
}

// UpsertCartMaster implements port.CartRepository.UpsertCartMaster.
func (r *CartRepositoryImpl) UpsertCartMaster(
	ctx context.Context, cart *entity.Cart, streamID string,
) error {
	return r.db.WithContext(ctx).Exec(`
		INSERT INTO carts (cart_id, user_id, status, last_event_stream_id, created_at, updated_at)
		VALUES (?, ?, ?, ?, NOW(), NOW())
		ON CONFLICT (cart_id) DO UPDATE SET
			status = EXCLUDED.status,
			last_event_stream_id = EXCLUDED.last_event_stream_id,
			updated_at = NOW()
		WHERE carts.last_event_stream_id IS NULL OR carts.last_event_stream_id < EXCLUDED.last_event_stream_id
	`, cart.CartID(), cart.UserID(), cart.Status(), streamID).Error
}

// ListCartsForCleanup implements port.CartRepository.ListCartsForCleanup.
func (r *CartRepositoryImpl) ListCartsForCleanup(
	ctx context.Context, olderThan time.Time, statuses []entity.CartStatus, limit int,
) ([]string, error) {
	statusStrings := make([]string, len(statuses))
	for i, s := range statuses {
		statusStrings[i] = string(s)
	}

	var cartIDs []string
	err := r.db.WithContext(ctx).
		Model(&CartModel{}).
		Select("cart_id").
		Where("updated_at < ? AND status IN ?", olderThan, statusStrings).
		Limit(limit).
		Pluck("cart_id", &cartIDs).Error

	return cartIDs, err
}

// HardDeleteCart implements port.CartRepository.HardDeleteCart.
// Deletes cart_items FIRST, then carts (within a transaction).
func (r *CartRepositoryImpl) HardDeleteCart(ctx context.Context, cartID string) error {
	return r.db.WithContext(ctx).Transaction(func(tx *gorm.DB) error {
		if err := tx.Exec("DELETE FROM cart_items WHERE cart_id = ?", cartID).Error; err != nil {
			return fmt.Errorf("delete cart_items: %w", err)
		}
		if err := tx.Exec("DELETE FROM carts WHERE cart_id = ?", cartID).Error; err != nil {
			return fmt.Errorf("delete cart: %w", err)
		}
		return nil
	})
}

// ListPendingAbandonedEvents implements port.CartRepository.ListPendingAbandonedEvents.
func (r *CartRepositoryImpl) ListPendingAbandonedEvents(
	ctx context.Context, olderThan time.Time,
) ([]*entity.Cart, error) {
	var models []CartModel
	if err := r.db.WithContext(ctx).
		Where("status = ? AND abandoned_event_published_at IS NULL AND updated_at >= ?",
			entity.CartStatusAbandoned, olderThan).
		Find(&models).Error; err != nil {
		return nil, err
	}

	carts := make([]*entity.Cart, len(models))
	for i := range models {
		carts[i] = models[i].ToEntity()
	}
	return carts, nil
}

// MarkAbandonedEventPublished implements port.CartRepository.MarkAbandonedEventPublished.
func (r *CartRepositoryImpl) MarkAbandonedEventPublished(ctx context.Context, cartID string) error {
	return r.db.WithContext(ctx).
		Model(&CartModel{}).
		Where("cart_id = ?", cartID).
		Update("abandoned_event_published_at", time.Now().UTC()).Error
}

// GetCartItems implements port.CartRepository.GetCartItems.
func (r *CartRepositoryImpl) GetCartItems(ctx context.Context, cartID string) ([]*entity.CartItem, error) {
	var models []CartItemModel
	if err := r.db.WithContext(ctx).
		Where("cart_id = ?", cartID).
		Find(&models).Error; err != nil {
		return nil, fmt.Errorf("GetCartItems: %w", err)
	}

	items := make([]*entity.CartItem, len(models))
	for i := range models {
		items[i] = models[i].ToEntity()
	}
	return items, nil
}

// UpsertCartItemWithPayload implements port.CartRepository.UpsertCartItemWithPayload.
// Used by Postgres Sync Worker when processing Kafka events with raw JSON payload.
func (r *CartRepositoryImpl) UpsertCartItemWithPayload(
	ctx context.Context, cartID string, payload map[string]interface{}, streamID string,
) error {
	skuID, ok := payload["sku_id"].(string)
	if !ok || skuID == "" {
		return fmt.Errorf("UpsertCartItemWithPayload: missing or invalid sku_id")
	}
	productID, _ := payload["product_id"].(string)
	quantity, _ := toInt(payload["quantity"])
	unitPrice, _ := toFloat64(payload["unit_price_at_add"])

	var snapshot entity.ProductSnapshot
	if snapData, ok := payload["product_snapshot"].(map[string]interface{}); ok {
		snapshot.ProductID, _ = snapData["product_id"].(string)
		snapshot.ProductName, _ = snapData["product_name"].(string)
		snapshot.VariantInfo, _ = snapData["variant_info"].(string)
		snapshot.ImageURL, _ = snapData["image_url"].(string)
		if v, ok := snapData["is_available"].(bool); ok {
			snapshot.IsAvailable = v
		}
		snapshot.AddedAt, _ = snapData["added_at"].(string)
		snapshot.UpdatedAt, _ = snapData["updated_at"].(string)
	}

	itemID := cartID + ":" + skuID
	now := time.Now().UTC()
	item := entity.RestoreCartItem(
		itemID, cartID, productID, skuID,
		quantity, unitPrice,
		snapshot, streamID,
		now, now,
	)
	return r.UpsertCartItem(ctx, item, streamID)
}

// UpsertCartMasterWithStatus implements port.CartRepository.UpsertCartMasterWithStatus.
// Used by Postgres Sync Worker for ADD/UPDATE/CLEARED/CHECKED_OUT events via Kafka payload.
func (r *CartRepositoryImpl) UpsertCartMasterWithStatus(
	ctx context.Context, userID string, status entity.CartStatus, streamID string, updatedAt time.Time,
) error {
	return r.db.WithContext(ctx).Exec(`
		INSERT INTO carts (cart_id, user_id, status, last_event_stream_id, created_at, updated_at)
		VALUES (?, ?, ?, ?, NOW(), ?)
		ON CONFLICT (cart_id) DO UPDATE SET
			status = EXCLUDED.status,
			last_event_stream_id = EXCLUDED.last_event_stream_id,
			updated_at = EXCLUDED.updated_at
		WHERE carts.last_event_stream_id IS NULL OR carts.last_event_stream_id < EXCLUDED.last_event_stream_id
	`, userID, userID, status, streamID, updatedAt).Error
}

// toInt safely converts an interface{} to int.
func toInt(v interface{}) (int, bool) {
	switch n := v.(type) {
	case int:
		return n, true
	case float64:
		return int(n), true
	case json.Number:
		i, err := n.Int64()
		return int(i), err == nil
	default:
		return 0, false
	}
}

// toFloat64 safely converts an interface{} to float64.
func toFloat64(v interface{}) (float64, bool) {
	switch n := v.(type) {
	case float64:
		return n, true
	case int:
		return float64(n), true
	case json.Number:
		f, err := n.Float64()
		return f, err == nil
	default:
		return 0.0, false
	}
}

// Compile-time interface compliance checks.
var (
	_ port.CartRepository = (*CartRepositoryImpl)(nil)
)
