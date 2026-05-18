package infrastructure

import (
	"context"
	"database/sql"
	"fmt"
	"strings"
	"time"

	_ "github.com/lib/pq"

	"github.com/ecommerce-platform/go/services/cart-service/internal/domain/port"
)

// PostgresDB implements port.PostgresDB using database/sql
type PostgresDB struct {
	db *sql.DB
}

// NewPostgresDB creates a new PostgresDB instance
func NewPostgresDB(db *sql.DB) *PostgresDB {
	return &PostgresDB{db: db}
}

// GetCart retrieves a cart by ID
func (p *PostgresDB) GetCart(ctx context.Context, cartID string) (*port.CartRecord, error) {
	query := `
		SELECT cart_id, user_id, status, last_event_stream_id, 
		       abandoned_event_published_at, created_at, updated_at
		FROM carts
		WHERE cart_id = $1
	`

	var cart port.CartRecord
	var abandonedAt sql.NullTime
	var lastEventID sql.NullString

	err := p.db.QueryRowContext(ctx, query, cartID).Scan(
		&cart.CartID,
		&cart.UserID,
		&cart.Status,
		&lastEventID,
		&abandonedAt,
		&cart.CreatedAt,
		&cart.UpdatedAt,
	)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("failed to get cart: %w", err)
	}

	if abandonedAt.Valid {
		cart.AbandonedEventPublishedAt = &abandonedAt.Time
	}
	if lastEventID.Valid {
		cart.LastEventStreamID = lastEventID.String
	}

	return &cart, nil
}

// GetCartStatus retrieves the status of a cart
func (p *PostgresDB) GetCartStatus(ctx context.Context, cartID string) (string, error) {
	query := `SELECT status FROM carts WHERE cart_id = $1`

	var status string
	err := p.db.QueryRowContext(ctx, query, cartID).Scan(&status)
	if err == sql.ErrNoRows {
		return "", nil
	}
	if err != nil {
		return "", fmt.Errorf("failed to get cart status: %w", err)
	}

	return status, nil
}

// GetCartItems retrieves all items for a cart
func (p *PostgresDB) GetCartItems(ctx context.Context, cartID string) ([]*port.CartItemRecord, error) {
	query := `
		SELECT item_id, cart_id, product_id, sku_id, quantity, unit_price_at_add,
		       product_snapshot, redis_stream_id, created_at, updated_at
		FROM cart_items
		WHERE cart_id = $1
	`

	rows, err := p.db.QueryContext(ctx, query, cartID)
	if err != nil {
		return nil, fmt.Errorf("failed to query cart items: %w", err)
	}
	defer rows.Close()

	var items []*port.CartItemRecord
	for rows.Next() {
		var item port.CartItemRecord
		var redisStreamID sql.NullString

		err := rows.Scan(
			&item.ItemID,
			&item.CartID,
			&item.ProductID,
			&item.SkuID,
			&item.Quantity,
			&item.UnitPriceAtAdd,
			&item.ProductSnapshot,
			&redisStreamID,
			&item.CreatedAt,
			&item.UpdatedAt,
		)
		if err != nil {
			return nil, fmt.Errorf("failed to scan cart item: %w", err)
		}

		if redisStreamID.Valid {
			item.RedisStreamID = redisStreamID.String
		}

		items = append(items, &item)
	}

	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("error iterating cart items: %w", err)
	}

	return items, nil
}

// UpsertCartItems upserts multiple cart items with stream_id guard
func (p *PostgresDB) UpsertCartItems(ctx context.Context, items []*port.CartItemRecord) error {
	if len(items) == 0 {
		return nil
	}

	tx, err := p.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("failed to begin transaction: %w", err)
	}
	defer tx.Rollback()

	// Upsert cart items
	for _, item := range items {
		query := `
			INSERT INTO cart_items (
				item_id, cart_id, product_id, sku_id, quantity, unit_price_at_add,
				product_snapshot, redis_stream_id, created_at, updated_at
			)
			VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
			ON CONFLICT (item_id)
			DO UPDATE SET
				quantity = EXCLUDED.quantity,
				product_snapshot = EXCLUDED.product_snapshot,
				redis_stream_id = EXCLUDED.redis_stream_id,
				updated_at = NOW()
			WHERE cart_items.redis_stream_id < EXCLUDED.redis_stream_id
		`

		_, err := tx.ExecContext(ctx, query,
			item.ItemID,
			item.CartID,
			item.ProductID,
			item.SkuID,
			item.Quantity,
			item.UnitPriceAtAdd,
			item.ProductSnapshot,
			item.RedisStreamID,
			item.CreatedAt,
			item.UpdatedAt,
		)
		if err != nil {
			return fmt.Errorf("failed to upsert cart item %s: %w", item.ItemID, err)
		}
	}

	if err := tx.Commit(); err != nil {
		return fmt.Errorf("failed to commit transaction: %w", err)
	}

	return nil
}

// DeleteCartItems deletes multiple cart items
func (p *PostgresDB) DeleteCartItems(ctx context.Context, itemIDs []string) error {
	if len(itemIDs) == 0 {
		return nil
	}

	// Build placeholders for IN clause
	placeholders := make([]string, len(itemIDs))
	args := make([]interface{}, len(itemIDs))
	for i, id := range itemIDs {
		placeholders[i] = fmt.Sprintf("$%d", i+1)
		args[i] = id
	}

	query := fmt.Sprintf(
		"DELETE FROM cart_items WHERE item_id IN (%s)",
		strings.Join(placeholders, ","),
	)

	_, err := p.db.ExecContext(ctx, query, args...)
	if err != nil {
		return fmt.Errorf("failed to delete cart items: %w", err)
	}

	return nil
}

// DeleteCartItemsByCartID deletes all items for a cart
func (p *PostgresDB) DeleteCartItemsByCartID(ctx context.Context, cartID string) error {
	query := `DELETE FROM cart_items WHERE cart_id = $1`

	_, err := p.db.ExecContext(ctx, query, cartID)
	if err != nil {
		return fmt.Errorf("failed to delete cart items: %w", err)
	}

	return nil
}

// UpdateCartStatus updates the status of a cart with stream_id guard
func (p *PostgresDB) UpdateCartStatus(ctx context.Context, cartID string, status string, streamID string) error {
	query := `
		UPDATE carts
		SET status = $1, last_event_stream_id = $2, updated_at = NOW()
		WHERE cart_id = $3 AND (last_event_stream_id IS NULL OR last_event_stream_id < $2)
	`

	result, err := p.db.ExecContext(ctx, query, status, streamID, cartID)
	if err != nil {
		return fmt.Errorf("failed to update cart status: %w", err)
	}

	rowsAffected, _ := result.RowsAffected()
	if rowsAffected == 0 {
		return nil // No update needed (older event)
	}

	return nil
}

// CreateCart creates a new cart record
func (p *PostgresDB) CreateCart(ctx context.Context, cart *port.CartRecord) error {
	query := `
		INSERT INTO carts (cart_id, user_id, status, last_event_stream_id, created_at, updated_at)
		VALUES ($1, $2, $3, $4, $5, $6)
		ON CONFLICT (cart_id) DO NOTHING
	`

	_, err := p.db.ExecContext(ctx, query,
		cart.CartID,
		cart.UserID,
		cart.Status,
		cart.LastEventStreamID,
		cart.CreatedAt,
		cart.UpdatedAt,
	)
	if err != nil {
		return fmt.Errorf("failed to create cart: %w", err)
	}

	return nil
}

// GetAbandonedCarts retrieves carts that are abandoned
func (p *PostgresDB) GetAbandonedCarts(ctx context.Context, thresholdDays int, limit int) ([]*port.CartRecord, error) {
	query := `
		SELECT cart_id, user_id, status, last_event_stream_id,
		       abandoned_event_published_at, created_at, updated_at
		FROM carts
		WHERE status = 'ACTIVE'
		  AND updated_at < NOW() - INTERVAL '%d days'
		ORDER BY updated_at ASC
		LIMIT $1
	`

	query = fmt.Sprintf(query, thresholdDays)

	rows, err := p.db.QueryContext(ctx, query, limit)
	if err != nil {
		return nil, fmt.Errorf("failed to query abandoned carts: %w", err)
	}
	defer rows.Close()

	var carts []*port.CartRecord
	for rows.Next() {
		var cart port.CartRecord
		var abandonedAt sql.NullTime
		var lastEventID sql.NullString

		err := rows.Scan(
			&cart.CartID,
			&cart.UserID,
			&cart.Status,
			&lastEventID,
			&abandonedAt,
			&cart.CreatedAt,
			&cart.UpdatedAt,
		)
		if err != nil {
			return nil, fmt.Errorf("failed to scan cart: %w", err)
		}

		if abandonedAt.Valid {
			cart.AbandonedEventPublishedAt = &abandonedAt.Time
		}
		if lastEventID.Valid {
			cart.LastEventStreamID = lastEventID.String
		}

		carts = append(carts, &cart)
	}

	return carts, nil
}

// GetExpiredCarts retrieves carts that should be archived
func (p *PostgresDB) GetExpiredCarts(ctx context.Context, status string, thresholdDays int, limit int) ([]*port.CartRecord, error) {
	query := `
		SELECT cart_id, user_id, status, last_event_stream_id,
		       abandoned_event_published_at, created_at, updated_at
		FROM carts
		WHERE status = $1
		  AND updated_at < NOW() - INTERVAL '%d days'
		ORDER BY updated_at ASC
		LIMIT $2
	`

	query = fmt.Sprintf(query, thresholdDays)

	rows, err := p.db.QueryContext(ctx, query, status, limit)
	if err != nil {
		return nil, fmt.Errorf("failed to query expired carts: %w", err)
	}
	defer rows.Close()

	var carts []*port.CartRecord
	for rows.Next() {
		var cart port.CartRecord
		var abandonedAt sql.NullTime
		var lastEventID sql.NullString

		err := rows.Scan(
			&cart.CartID,
			&cart.UserID,
			&cart.Status,
			&lastEventID,
			&abandonedAt,
			&cart.CreatedAt,
			&cart.UpdatedAt,
		)
		if err != nil {
			return nil, fmt.Errorf("failed to scan cart: %w", err)
		}

		if abandonedAt.Valid {
			cart.AbandonedEventPublishedAt = &abandonedAt.Time
		}
		if lastEventID.Valid {
			cart.LastEventStreamID = lastEventID.String
		}

		carts = append(carts, &cart)
	}

	return carts, nil
}

// DeleteCart deletes a cart and its items
func (p *PostgresDB) DeleteCart(ctx context.Context, cartID string) error {
	tx, err := p.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("failed to begin transaction: %w", err)
	}
	defer tx.Rollback()

	// Delete cart items first (no FK CASCADE)
	_, err = tx.ExecContext(ctx, "DELETE FROM cart_items WHERE cart_id = $1", cartID)
	if err != nil {
		return fmt.Errorf("failed to delete cart items: %w", err)
	}

	// Delete cart
	_, err = tx.ExecContext(ctx, "DELETE FROM carts WHERE cart_id = $1", cartID)
	if err != nil {
		return fmt.Errorf("failed to delete cart: %w", err)
	}

	if err := tx.Commit(); err != nil {
		return fmt.Errorf("failed to commit transaction: %w", err)
	}

	return nil
}

// UpdateCartAbandonedEventPublishedAt updates the abandoned_event_published_at field
func (p *PostgresDB) UpdateCartAbandonedEventPublishedAt(ctx context.Context, cartID string, publishedAt time.Time) error {
	query := `
		UPDATE carts
		SET abandoned_event_published_at = $1, updated_at = NOW()
		WHERE cart_id = $2
	`

	_, err := p.db.ExecContext(ctx, query, publishedAt, cartID)
	if err != nil {
		return fmt.Errorf("failed to update abandoned_event_published_at: %w", err)
	}

	return nil
}
