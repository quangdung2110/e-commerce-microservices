package postgres

import (
	"database/sql"
	"encoding/json"
	"time"

	"cart-service/internal/domain/entity"
)

// CartModel is the GORM model for the carts table.
type CartModel struct {
	CartID                     string         `gorm:"primaryKey;column:cart_id;type:varchar(255)"`
	UserID                     string         `gorm:"column:user_id;type:varchar(255);index"`
	Status                     string         `gorm:"column:status;type:varchar(50);index"`
	LastEventStreamID          sql.NullString `gorm:"column:last_event_stream_id;type:varchar(64)"`
	AbandonedEventPublishedAt  sql.NullTime   `gorm:"column:abandoned_event_published_at"`
	CreatedAt                  time.Time      `gorm:"column:created_at"`
	UpdatedAt                  time.Time      `gorm:"column:updated_at;index"`
}

// TableName specifies the table name.
func (CartModel) TableName() string { return "carts" }

// ToEntity converts the GORM model to a domain Cart entity.
func (m *CartModel) ToEntity() *entity.Cart {
	var abandonedPubAt *time.Time
	if m.AbandonedEventPublishedAt.Valid {
		t := m.AbandonedEventPublishedAt.Time
		abandonedPubAt = &t
	}
	return entity.RestoreCartWithAbandoned(
		m.CartID, m.UserID,
		entity.CartStatus(m.Status),
		m.LastEventStreamID.String,
		m.CreatedAt, m.UpdatedAt,
		abandonedPubAt,
	)
}

// CartItemModel is the GORM model for the cart_items table.
type CartItemModel struct {
	ItemID         string         `gorm:"primaryKey;column:item_id;type:varchar(255)"`
	CartID         string         `gorm:"column:cart_id;type:varchar(255);index"`
	ProductID      string         `gorm:"column:product_id;type:varchar(255);index"`
	SKUID          string         `gorm:"column:sku_id;type:varchar(255);index"`
	Quantity       int            `gorm:"column:quantity"`
	UnitPriceAtAdd float64        `gorm:"column:unit_price_at_add;type:decimal(15,2)"`
	ProductSnapshot []byte        `gorm:"column:product_snapshot;type:jsonb"`
	RedisStreamID  sql.NullString `gorm:"column:redis_stream_id;type:varchar(64);index"`
	CreatedAt      time.Time      `gorm:"column:created_at"`
	UpdatedAt      time.Time      `gorm:"column:updated_at"`
}

// TableName specifies the table name.
func (CartItemModel) TableName() string { return "cart_items" }

// ToEntity converts the GORM model to a domain CartItem entity.
func (m *CartItemModel) ToEntity() *entity.CartItem {
	var snapshot entity.ProductSnapshot
	if len(m.ProductSnapshot) > 0 {
		json.Unmarshal(m.ProductSnapshot, &snapshot)
	}
	return entity.RestoreCartItem(
		m.ItemID, m.CartID, m.ProductID, m.SKUID,
		m.Quantity, m.UnitPriceAtAdd,
		snapshot, m.RedisStreamID.String,
		m.CreatedAt, m.UpdatedAt,
	)
}

// ToCartItemModel converts a domain CartItem entity to a GORM model.
func ToCartItemModel(item *entity.CartItem) *CartItemModel {
	snapshotJSON, _ := json.Marshal(item.Snapshot())
	return &CartItemModel{
		ItemID:          item.ItemID(),
		CartID:          item.CartID(),
		ProductID:       item.ProductID(),
		SKUID:           item.SkuID(),
		Quantity:        item.Quantity(),
		UnitPriceAtAdd:  item.UnitPriceAtAdd(),
		ProductSnapshot: snapshotJSON,
		RedisStreamID:   toNullString(item.StreamID()),
		CreatedAt:       item.CreatedAt(),
		UpdatedAt:       item.UpdatedAt(),
	}
}

// toNullString converts a string to sql.NullString.
func toNullString(s string) sql.NullString {
	return sql.NullString{String: s, Valid: s != ""}
}
