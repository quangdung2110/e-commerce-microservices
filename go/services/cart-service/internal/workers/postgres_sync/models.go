package postgres_sync

import (
	"time"

	"cart-service/internal/domain/entity"
)

// CartEventRecord represents a parsed cart event record for processing.
type CartEventRecord struct {
	CartID    string
	UserID    string
	SkuID     string
	EventType string
	StreamID  string
	Timestamp time.Time
	Quantity  int
	UnitPrice float64
	Status    entity.CartStatus
}

// CartItemRecord represents a cart item record for database persistence.
type CartItemRecord struct {
	ItemID         string
	CartID         string
	ProductID      string
	SkuID          string
	Quantity       int
	UnitPriceAtAdd float64
	StreamID       string
	CreatedAt      time.Time
	UpdatedAt      time.Time
}

// CartStatusRecord represents a cart status record for database persistence.
type CartStatusRecord struct {
	CartID        string
	UserID        string
	Status        entity.CartStatus
	StreamID      string
	AbandonedPubAt *time.Time
	CreatedAt     time.Time
	UpdatedAt     time.Time
}

// EventTypeCount holds counts per event type.
type EventTypeCount struct {
	Type  string
	Count int
}

// BatchResult holds the result of processing a batch.
type BatchResult struct {
	CartsProcessed int
	EventsTotal   int
	EventsFailed  int
	Errors        []error
}
