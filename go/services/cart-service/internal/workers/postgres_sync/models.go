package postgres_sync

import (
	"fmt"
	"strings"
	"time"

	"gorm.io/gorm"

	"github.com/ecommerce-platform/go/services/cart-service/internal/common/constants"
)

// CartStatus represents the status of a cart
type CartStatus string

const (
	CartStatusActive     CartStatus = "ACTIVE"
	CartStatusAbandoned CartStatus = "ABANDONED"
	CartStatusDeleted   CartStatus = "DELETED"
	CartStatusCheckedOut CartStatus = "CHECKED_OUT"
)

// Cart represents the carts table in PostgreSQL
type Cart struct {
	CartID                    string     `gorm:"column:cart_id;primaryKey;type:varchar(255)" json:"cart_id"`
	UserID                    string     `gorm:"column:user_id;type:varchar(255);index;not null" json:"user_id"`
	Status                    CartStatus `gorm:"column:status;type:varchar(50);default:'ACTIVE';index" json:"status"`
	LastEventStreamID         *string    `gorm:"column:last_event_stream_id;type:varchar(64)" json:"last_event_stream_id,omitempty"`
	AbandonedEventPublishedAt *time.Time `gorm:"column:abandoned_event_published_at" json:"abandoned_event_published_at,omitempty"`
	CreatedAt                 time.Time  `gorm:"column:created_at;not null;default:now()" json:"created_at"`
	UpdatedAt                 time.Time  `gorm:"column:updated_at;not null;default:now();index" json:"updated_at"`
}

// TableName returns the table name for Cart
func (Cart) TableName() string {
	return "carts"
}

// CartItem represents the cart_items table in PostgreSQL
type CartItem struct {
	ItemID         string         `gorm:"column:item_id;primaryKey;type:varchar(255)" json:"item_id"`
	CartID         string         `gorm:"column:cart_id;type:varchar(255);index;not null" json:"cart_id"`
	ProductID      string         `gorm:"column:product_id;type:varchar(255);index" json:"product_id"`
	SkuID          string         `gorm:"column:sku_id;type:varchar(255);index" json:"sku_id"`
	Quantity       int            `gorm:"column:quantity;not null;default:0" json:"quantity"`
	UnitPriceAtAdd float64       `gorm:"column:unit_price_at_add;type:decimal(15,2);not null;default:0.00" json:"unit_price_at_add"`
	ProductSnapshot string        `gorm:"column:product_snapshot;type:jsonb;not null" json:"product_snapshot"`
	RedisStreamID  *string        `gorm:"column:redis_stream_id;type:varchar(64)" json:"redis_stream_id,omitempty"`
	CreatedAt      time.Time      `gorm:"column:created_at;not null;default:now()" json:"created_at"`
	UpdatedAt      time.Time      `gorm:"column:updated_at;not null;default:now()" json:"updated_at"`
}

// TableName returns the table name for CartItem
func (CartItem) TableName() string {
	return "cart_items"
}

// BuildItemID builds an item_id from cart_id and sku_id
func BuildItemID(cartID, skuID string) string {
	return cartID + ":" + skuID
}

// ParseItemID parses item_id to extract cart_id and sku_id
func ParseItemID(itemID string) (cartID, skuID string, err error) {
	parts := strings.SplitN(itemID, ":", 2)
	if len(parts) != 2 {
		return "", "", fmt.Errorf("invalid item_id format: %s", itemID)
	}
	return parts[0], parts[1], nil
}

// Migrate runs database migrations for cart tables
func Migrate(db *gorm.DB) error {
	// Create carts table
	if err := db.Exec(`
		CREATE TABLE IF NOT EXISTS carts (
			cart_id VARCHAR(255) PRIMARY KEY,
			user_id VARCHAR(255) NOT NULL,
			status VARCHAR(50) DEFAULT 'ACTIVE',
			last_event_stream_id VARCHAR(64),
			abandoned_event_published_at TIMESTAMPTZ,
			created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
			updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
		)
	`).Error; err != nil {
		return fmt.Errorf("failed to create carts table: %w", err)
	}

	// Create indexes for carts
	cartIndexes := []string{
		`CREATE INDEX IF NOT EXISTS idx_carts_user_id ON carts(user_id)`,
		`CREATE INDEX IF NOT EXISTS idx_carts_status ON carts(status)`,
		`CREATE INDEX IF NOT EXISTS idx_carts_updated_at ON carts(updated_at)`,
	}

	for _, idx := range cartIndexes {
		if err := db.Exec(idx).Error; err != nil {
			return fmt.Errorf("failed to create cart index: %w", err)
		}
	}

	// Create cart_items table
	if err := db.Exec(`
		CREATE TABLE IF NOT EXISTS cart_items (
			item_id VARCHAR(255) PRIMARY KEY,
			cart_id VARCHAR(255) NOT NULL,
			product_id VARCHAR(255) NOT NULL,
			sku_id VARCHAR(255) NOT NULL,
			quantity INT NOT NULL DEFAULT 0,
			unit_price_at_add DECIMAL(15,2) NOT NULL DEFAULT 0.00,
			product_snapshot JSONB NOT NULL,
			redis_stream_id VARCHAR(64),
			created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
			updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
		)
	`).Error; err != nil {
		return fmt.Errorf("failed to create cart_items table: %w", err)
	}

	// Create indexes for cart_items
	itemIndexes := []string{
		`CREATE INDEX IF NOT EXISTS idx_cart_items_cart_id ON cart_items(cart_id)`,
		`CREATE INDEX IF NOT EXISTS idx_cart_items_product_id ON cart_items(product_id)`,
		`CREATE INDEX IF NOT EXISTS idx_cart_items_sku_id ON cart_items(sku_id)`,
	}

	for _, idx := range itemIndexes {
		if err := db.Exec(idx).Error; err != nil {
			return fmt.Errorf("failed to create cart_items index: %w", err)
		}
	}

	return nil
}

// UpsertCartWithGuard upserts a cart record with stream_id guard
func UpsertCartWithGuard(db *gorm.DB, cart *Cart) error {
	sql := `
		INSERT INTO carts (cart_id, user_id, status, last_event_stream_id, created_at, updated_at)
		VALUES (?, ?, ?, ?, NOW(), NOW())
		ON CONFLICT (cart_id) DO UPDATE SET
			status = EXCLUDED.status,
			last_event_stream_id = EXCLUDED.last_event_stream_id,
			updated_at = NOW()
		WHERE carts.last_event_stream_id < EXCLUDED.last_event_stream_id
			OR carts.last_event_stream_id IS NULL
	`

	result := db.Exec(sql,
		cart.CartID,
		cart.UserID,
		cart.Status,
		cart.LastEventStreamID,
	)

	if result.Error != nil {
		return fmt.Errorf("failed to upsert cart: %w", result.Error)
	}

	return nil
}

// UpsertCartActive upserts a cart with ACTIVE status (for ADD/UPDATE events)
func UpsertCartActive(db *gorm.DB, cartID, userID, streamID string) error {
	sql := `
		INSERT INTO carts (cart_id, user_id, status, last_event_stream_id, created_at, updated_at)
		VALUES (?, ?, 'ACTIVE', ?, NOW(), NOW())
		ON CONFLICT (cart_id) DO UPDATE SET
			status = 'ACTIVE',
			last_event_stream_id = EXCLUDED.last_event_stream_id,
			updated_at = NOW()
		WHERE carts.last_event_stream_id < EXCLUDED.last_event_stream_id
			OR carts.last_event_stream_id IS NULL
	`

	result := db.Exec(sql, cartID, userID, streamID)
	if result.Error != nil {
		return fmt.Errorf("failed to upsert cart active: %w", result.Error)
	}

	return nil
}

// UpdateCartStatus updates cart status with stream_id guard
func UpdateCartStatus(db *gorm.DB, cartID, status, streamID string) error {
	sql := `
		UPDATE carts
		SET status = ?,
			last_event_stream_id = ?,
			updated_at = NOW()
		WHERE cart_id = ?
			AND (last_event_stream_id < ? OR last_event_stream_id IS NULL)
	`

	result := db.Exec(sql, status, streamID, cartID, streamID)
	if result.Error != nil {
		return fmt.Errorf("failed to update cart status: %w", result.Error)
	}

	return nil
}

// UpsertCartItemWithGuard upserts a cart item with stream_id guard
func UpsertCartItemWithGuard(db *gorm.DB, item *CartItem) error {
	sql := `
		INSERT INTO cart_items (
			item_id, cart_id, product_id, sku_id,
			quantity, unit_price_at_add, product_snapshot, redis_stream_id,
			created_at, updated_at
		)
		VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, NOW(), NOW())
		ON CONFLICT (item_id) DO UPDATE SET
			quantity = EXCLUDED.quantity,
			product_snapshot = EXCLUDED.product_snapshot,
			redis_stream_id = EXCLUDED.redis_stream_id,
			updated_at = NOW()
		WHERE cart_items.redis_stream_id < EXCLUDED.redis_stream_id
			OR cart_items.redis_stream_id IS NULL
	`

	result := db.Exec(sql,
		item.ItemID,
		item.CartID,
		item.ProductID,
		item.SkuID,
		item.Quantity,
		item.UnitPriceAtAdd,
		item.ProductSnapshot,
		item.RedisStreamID,
	)

	if result.Error != nil {
		return fmt.Errorf("failed to upsert cart item: %w", result.Error)
	}

	return nil
}

// BatchUpsertCartItems performs batch upsert for cart items
func BatchUpsertCartItems(db *gorm.DB, items []*CartItem) error {
	if len(items) == 0 {
		return nil
	}

	// Build batch insert values
	valueStrings := make([]string, 0, len(items))
	valueArgs := make([]interface{}, 0, len(items)*9)

	for _, item := range items {
		valueStrings = append(valueStrings, "(?, ?, ?, ?, ?, ?, ?::jsonb, ?, NOW(), NOW())")
		valueArgs = append(valueArgs,
			item.ItemID,
			item.CartID,
			item.ProductID,
			item.SkuID,
			item.Quantity,
			item.UnitPriceAtAdd,
			item.ProductSnapshot,
			item.RedisStreamID,
		)
	}

	sql := fmt.Sprintf(`
		INSERT INTO cart_items (
			item_id, cart_id, product_id, sku_id,
			quantity, unit_price_at_add, product_snapshot, redis_stream_id,
			created_at, updated_at
		)
		VALUES %s
		ON CONFLICT (item_id) DO UPDATE SET
			quantity = EXCLUDED.quantity,
			product_snapshot = EXCLUDED.product_snapshot,
			redis_stream_id = EXCLUDED.redis_stream_id,
			updated_at = NOW()
		WHERE cart_items.redis_stream_id < EXCLUDED.redis_stream_id
			OR cart_items.redis_stream_id IS NULL
	`, strings.Join(valueStrings, ","))

	result := db.Exec(sql, valueArgs...)
	if result.Error != nil {
		return fmt.Errorf("failed to batch upsert cart items: %w", result.Error)
	}

	return nil
}

// HardDeleteCartItems deletes cart items with stream_id guard
func HardDeleteCartItems(db *gorm.DB, itemIDs []string, streamID string) error {
	if len(itemIDs) == 0 {
		return nil
	}

	// Build placeholders
	placeholders := make([]string, len(itemIDs))
	args := make([]interface{}, len(itemIDs)+1)

	for i, itemID := range itemIDs {
		placeholders[i] = "?"
		args[i] = itemID
	}
	args[len(itemIDs)] = streamID

	sql := fmt.Sprintf(`
		DELETE FROM cart_items
		WHERE item_id IN (%s)
			AND (redis_stream_id < ? OR redis_stream_id IS NULL)
	`, strings.Join(placeholders, ","))

	result := db.Exec(sql, args...)
	if result.Error != nil {
		return fmt.Errorf("failed to hard delete cart items: %w", result.Error)
	}

	return nil
}

// DeleteCartWithItems deletes a cart and all its items in a transaction
func DeleteCartWithItems(db *gorm.DB, cartID string) error {
	return db.Transaction(func(tx *gorm.DB) error {
		// Delete cart_items first (no FK constraint, manual order required)
		if err := tx.Exec("DELETE FROM cart_items WHERE cart_id = ?", cartID).Error; err != nil {
			return fmt.Errorf("failed to delete cart items: %w", err)
		}

		// Delete cart
		if err := tx.Exec("DELETE FROM carts WHERE cart_id = ?", cartID).Error; err != nil {
			return fmt.Errorf("failed to delete cart: %w", err)
		}

		return nil
	})
}

// GetCart retrieves a cart by ID
func GetCart(db *gorm.DB, cartID string) (*Cart, error) {
	var cart Cart
	result := db.Where("cart_id = ?", cartID).First(&cart)
	if result.Error != nil {
		if result.Error == gorm.ErrRecordNotFound {
			return nil, nil
		}
		return nil, fmt.Errorf("failed to get cart: %w", result.Error)
	}
	return &cart, nil
}

// GetCartItems retrieves all items for a cart
func GetCartItems(db *gorm.DB, cartID string) ([]*CartItem, error) {
	var items []*CartItem
	result := db.Where("cart_id = ?", cartID).Find(&items)
	if result.Error != nil {
		return nil, fmt.Errorf("failed to get cart items: %w", result.Error)
	}
	return items, nil
}

// ArchiveCarts archives carts based on status and age
func ArchiveCarts(db *gorm.DB, statuses []string, thresholdDays int, limit int) ([]string, error) {
	var cartIDs []string

	// Build placeholders for statuses
	statusPlaceholders := make([]string, len(statuses))
	statusArgs := make([]interface{}, len(statuses))
	for i, s := range statuses {
		statusPlaceholders[i] = "?"
		statusArgs[i] = s
	}

	// Get carts to delete
	query := fmt.Sprintf(`
		SELECT cart_id FROM carts
		WHERE status IN (%s)
			AND updated_at < NOW() - INTERVAL '%d days'
		ORDER BY updated_at ASC
		LIMIT ?
	`, strings.Join(statusPlaceholders, ","), thresholdDays)

	statusArgs = append(statusArgs, limit)

	result := db.Raw(query, statusArgs...).Scan(&cartIDs)
	if result.Error != nil {
		return nil, fmt.Errorf("failed to get carts to archive: %w", result.Error)
	}

	return cartIDs, nil
}

// GetAbandonedCarts retrieves carts that are abandoned and need event publishing
func GetAbandonedCarts(db *gorm.DB, thresholdDays, limit int) ([]*Cart, error) {
	var carts []*Cart
	result := db.Where("status = ? AND abandoned_event_published_at IS NULL AND updated_at < NOW() - INTERVAL '24 hours'",
		constants.CartStatusAbandoned).
		Order("updated_at ASC").
		Limit(limit).
		Find(&carts)

	if result.Error != nil {
		return nil, fmt.Errorf("failed to get abandoned carts: %w", result.Error)
	}

	return carts, nil
}

// UpdateAbandonedEventPublished updates the abandoned_event_published_at timestamp
func UpdateAbandonedEventPublished(db *gorm.DB, cartID string) error {
	result := db.Exec("UPDATE carts SET abandoned_event_published_at = NOW() WHERE cart_id = ?", cartID)
	if result.Error != nil {
		return fmt.Errorf("failed to update abandoned_event_published_at: %w", result.Error)
	}
	return nil
}
