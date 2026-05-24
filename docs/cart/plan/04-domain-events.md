# Cart Service — Module 04: Domain Events

**Document:** 04 | **Module:** Domain Events — Type Definitions & Structures  
**Status:** `[x]` Đã hoàn thành | `[ ]` Chưa hoàn thành ✅ (2026-05-24)  
**Phụ thuộc:** Module 02 (domain models — uses CartItem, ProductSnapshot)

---

## 1. Mục tiêu Module

Định nghĩa tất cả event types và structures mà Cart Service phát đi hoặc tiêu thụ. Module này thuần domain — không biết gì về Redis, Kafka, hay PostgreSQL. Event structs được serialize/deserialize ở infrastructure layer.

---

## 2. Event Type Constants

### 2.1. Cart Event Types (`internal/domain/event/cart_event_types.go`)

```go
package event

// Cart event types — emitted by Lua scripts and forwarded to Kafka.
// Ref: cart_events.md
const (
    // CartItemEvent types
    EventTypeCartItemAdded   = "CART_ITEM_ADDED"
    EventTypeCartItemUpdated = "CART_ITEM_UPDATED"
    EventTypeCartItemDeleted = "CART_ITEM_DELETED"

    // Cart lifecycle event types
    EventTypeCartCleared    = "CART_CLEARED"
    EventTypeCartCheckedOut = "CART_CHECKED_OUT"
    EventTypeCartAbandoned  = "CART_ABANDONED"

    // Consumed event types (from other services)
    EventTypeOrderPaid = "ORDER_PAID"
)

// AggregateType is the type identifier for cart aggregate.
const AggregateTypeCart = "Cart"
```

---

## 3. Event Payload Structures

### 3.1. Cart Item Event Payload (`internal/domain/event/item_payload.go`)

Payload cho `CART_ITEM_ADDED` và `CART_ITEM_UPDATED`. Lua script bổ sung `cart_id`, `sku_id`, `user_id` vào JSON trước khi `XADD`.

```go
package event

// CartItemPayload is the payload for CART_ITEM_ADDED and CART_ITEM_UPDATED events.
// Ref: cart_events.md §2.1, §2.2
// Populated by Lua Script (ARGV) + Backend (item data)
type CartItemPayload struct {
    // These 3 fields are added by Lua script before XADD
    CartID    string `json:"cart_id"`
    SkuID     string `json:"sku_id"`
    UserID    string `json:"user_id"`

    // These fields come from the item JSON built by the backend
    ProductID      string  `json:"product_id"`
    Quantity       int     `json:"quantity"`
    UnitPriceAtAdd float64 `json:"unit_price_at_add"`
    ProductName    string  `json:"product_name"`
    VariantInfo    string  `json:"variant_info,omitempty"`
    ImageURL       string  `json:"image_url,omitempty"`
    IsAvailable    bool    `json:"is_available"`
    AddedAt        string  `json:"added_at"`    // Epoch ms as string
    UpdatedAt      string  `json:"updated_at"`  // Epoch ms as string
}
```

### 3.2. Cart Item Deleted Payload (`internal/domain/event/deleted_payload.go`)

Payload cho `CART_ITEM_DELETED`. Chỉ chứa `cart_id`, `sku_id`, `user_id`, `deleted_at` — không chứa `product_id`.

```go
package event

// CartItemDeletedPayload is the payload for CART_ITEM_DELETED event.
// Ref: cart_events.md §2.3
// Note: Does NOT contain product_id (only cart_id, sku_id, user_id needed for deletion).
type CartItemDeletedPayload struct {
    CartID    string `json:"cart_id"`
    SkuID     string `json:"sku_id"`
    UserID    string `json:"user_id"`
    DeletedAt string `json:"deleted_at"` // Epoch ms as string
}
```

### 3.3. Cart Cleared Payload (`internal/domain/event/cleared_payload.go`)

```go
package event

// CartClearedPayload is the payload for CART_CLEARED event.
// Ref: cart_events.md §2.4
// Emitted when user manually clears all items and cart becomes empty (HLEN == 0).
type CartClearedPayload struct {
    CartID    string `json:"cart_id"`
    UserID    string `json:"user_id"`
    ClearedAt string `json:"cleared_at"` // Epoch ms as string
}
```

### 3.4. Cart Checked Out Payload (`internal/domain/event/checked_out_payload.go`)

```go
package event

// CartCheckedOutPayload is the payload for CART_CHECKED_OUT event.
// Ref: cart_events.md §2.5
// Emitted when ORDER_PAID received and cart becomes empty (HLEN == 0).
type CartCheckedOutPayload struct {
    CartID        string `json:"cart_id"`
    UserID        string `json:"user_id"`
    CheckedOutAt  string `json:"checked_out_at"` // Epoch ms as string
}
```

### 3.5. Cart Abandoned Payload (`internal/domain/event/abandoned_payload.go`)

```go
package event

// CartAbandonedPayload is the payload for CART_ABANDONED event.
// Ref: cart_events.md §2.6
// Emitted by Abandoned Cart Marker cronjob.
type CartAbandonedPayload struct {
    CartID      string `json:"cart_id"`
    UserID      string `json:"user_id"`
    AbandonedAt string `json:"abandoned_at"` // Epoch ms as string
}
```

### 3.6. Order Paid Payload (Consumed) (`internal/domain/event/order_paid_payload.go`)

```go
package event

// OrderPaidPayload is the payload for ORDER_PAID event consumed from Kafka.
// Ref: cart_events.md §3.1
// Fields used by Cart Service: order_id, user_id, cart_id, sku_ids.
type OrderPaidPayload struct {
    OrderID            int64    `json:"order_id"`
    UserID             string   `json:"user_id"`
    CartID             string   `json:"cart_id"`
    Status             string   `json:"status"`
    SkuIDs             []string `json:"sku_ids"`
    CheckoutSubmittedAt string  `json:"checkout_submitted_at"`
    PaidAt             string   `json:"paid_at"`
}
```

---

## 4. Kafka Message Envelope

### 4.1. CartEventEnvelope (`internal/domain/event/envelope.go`)

Wrapper envelope cho Kafka message. Fields ngoài `payload` được bổ sung bởi Relay Worker khi forward từ Redis Stream sang Kafka.

```go
package event

import "time"

// CartEventEnvelope is the complete Kafka message structure.
// Ref: cart_events.md §1
// Header fields (event_id, aggregate_type, aggregate_id, correlation_id,
// timestamp, source, stream_id, stream_timestamp_ms, event_sequence) are
// added by the Outbox Relay Worker when forwarding to Kafka.
type CartEventEnvelope struct {
    EventID           string                 `json:"event_id"`
    EventType         string                 `json:"event_type"`
    AggregateType     string                 `json:"aggregate_type"`
    AggregateID       string                 `json:"aggregate_id"`
    CorrelationID     string                 `json:"correlation_id"`
    Timestamp         string                 `json:"timestamp"`     // ISO 8601 UTC
    Source            string                 `json:"source"`       // "cart-service"
    StreamID          string                 `json:"stream_id"`    // Redis Stream ID (ordering key)
    StreamTimestampMs string                 `json:"stream_timestamp_ms"`
    EventSequence     string                 `json:"event_sequence"`
    Payload           map[string]interface{} `json:"payload"`
}

// NewCartEventEnvelope creates a new envelope from a CartEvent.
func NewCartEventEnvelope(eventType, cartID, correlationID string, streamID, streamTsMs, sequence string, payload map[string]interface{}) *CartEventEnvelope {
    return &CartEventEnvelope{
        EventID:           "",  // Generated by Relay Worker deterministically
        EventType:         eventType,
        AggregateType:     AggregateTypeCart,
        AggregateID:       cartID,
        CorrelationID:     correlationID,
        Timestamp:         time.Now().UTC().Format(time.RFC3339),
        Source:            "cart-service",
        StreamID:          streamID,
        StreamTimestampMs: streamTsMs,
        EventSequence:     sequence,
        Payload:           payload,
    }
}

// ParseStreamID extracts timestamp_ms and sequence from Redis Stream ID.
// Redis Stream ID format: "1708616400456-0"
func ParseStreamID(streamID string) (timestampMs, sequence string) {
    // Split by '-' separator
    for i := len(streamID) - 1; i >= 0; i-- {
        if streamID[i] == '-' {
            return streamID[:i], streamID[i+1:]
        }
    }
    return streamID, "0"
}
```

---

## 5. Redis Stream Message (Raw)

### 5.1. Stream Message Structure (`internal/domain/event/stream_message.go`)

Format của message trong Redis Stream trước khi Relay Worker forward. Lua Script chỉ ghi 2 fields: `event_type` và `payload`.

```go
package event

// RedisStreamMessage represents the raw structure of a Redis Stream entry.
// This is the format used by Lua scripts for XADD.
// Ref: cart_outbox_pattern.md §2.2
//
// Lua script XADD format:
//   XADD {cart}:events:{shard} * event_type CART_ITEM_ADDED payload '<json>'
type RedisStreamMessage struct {
    EventType string `json:"event_type"` // Field name in stream
    Payload   string `json:"payload"`    // Field name in stream (JSON string)
}

// StreamFieldNames are the actual field names used in Redis Stream entries.
var StreamFieldNames = struct {
    EventType string
    Payload   string
}{
    EventType: "event_type",
    Payload:   "payload",
}
```

---

## 6. Event Factory Functions

### 6.1. Event Builder (`internal/domain/event/builder.go`)

Factory functions để tạo event payloads từ domain entities.

```go
package event

import (
    "encoding/json"
    "fmt"
    "time"

    "cart-service/internal/domain/entity"
)

// BuildItemPayload creates CartItemPayload from a CartItemValue.
func BuildItemPayload(cartID, userID, skuID string, item *entity.CartItemValue) *CartItemPayload {
    nowMs := fmt.Sprintf("%d", time.Now().UTC().UnixMilli())
    return &CartItemPayload{
        CartID:         cartID,
        SkuID:          skuID,
        UserID:         userID,
        ProductID:      item.ProductID,
        Quantity:       item.Quantity,
        UnitPriceAtAdd: item.UnitPriceAtAdd,
        ProductName:    item.ProductName,
        VariantInfo:    item.VariantInfo,
        ImageURL:       item.ImageURL,
        IsAvailable:    item.IsAvailable,
        AddedAt:        item.AddedAt,
        UpdatedAt:       nowMs,
    }
}

// ToJSON serializes an event payload to JSON string.
func PayloadToJSON(v interface{}) (string, error) {
    data, err := json.Marshal(v)
    if err != nil {
        return "", fmt.Errorf("marshal payload: %w", err)
    }
    return string(data), nil
}

// ParseItemPayload deserializes CartItemPayload from a JSON string.
func ParseItemPayload(jsonStr string) (*CartItemPayload, error) {
    var p CartItemPayload
    if err := json.Unmarshal([]byte(jsonStr), &p); err != nil {
        return nil, fmt.Errorf("unmarshal item payload: %w", err)
    }
    return &p, nil
}

// ParseDeletedPayload deserializes CartItemDeletedPayload from a JSON string.
func ParseDeletedPayload(jsonStr string) (*CartItemDeletedPayload, error) {
    var p CartItemDeletedPayload
    if err := json.Unmarshal([]byte(jsonStr), &p); err != nil {
        return nil, fmt.Errorf("unmarshal deleted payload: %w", err)
    }
    return &p, nil
}

// ParseOrderPaidPayload deserializes OrderPaidPayload from a JSON string.
func ParseOrderPaidPayload(jsonStr string) (*OrderPaidPayload, error) {
    var p OrderPaidPayload
    if err := json.Unmarshal([]byte(jsonStr), &p); err != nil {
        return nil, fmt.Errorf("unmarshal order paid payload: %w", err)
    }
    return &p, nil
}
```

---

## 7. File Locations

| File | Vị trí | Mô tả |
|------|---------|--------|
| `cart_event_types.go` | `internal/domain/event/cart_event_types.go` | Event type constants |
| `item_payload.go` | `internal/domain/event/item_payload.go` | ADDED/UPDATED payload |
| `deleted_payload.go` | `internal/domain/event/deleted_payload.go` | DELETED payload |
| `cleared_payload.go` | `internal/domain/event/cleared_payload.go` | CLEARED payload |
| `checked_out_payload.go` | `internal/domain/event/checked_out_payload.go` | CHECKED_OUT payload |
| `abandoned_payload.go` | `internal/domain/event/abandoned_payload.go` | ABANDONED payload |
| `order_paid_payload.go` | `internal/domain/event/order_paid_payload.go` | ORDER_PAID consumed event |
| `envelope.go` | `internal/domain/event/envelope.go` | Kafka message envelope + stream ID parser |
| `stream_message.go` | `internal/domain/event/stream_message.go` | Redis Stream raw message format |
| `builder.go` | `internal/domain/event/builder.go` | Event factory + JSON helpers |

---

## 8. Checklist triển khai Module 04

```markdown
## 04 - Domain Events
- [x] Tạo `internal/domain/event/cart_event_types.go` với tất cả event type constants ✅
- [x] Tạo `internal/domain/event/item_payload.go` cho ADDED/UPDATED events ✅
- [x] Tạo `internal/domain/event/deleted_payload.go` cho DELETED event ✅
- [x] Tạo `internal/domain/event/cleared_payload.go` cho CLEARED event ✅
- [x] Tạo `internal/domain/event/checked_out_payload.go` cho CHECKED_OUT event ✅
- [x] Tạo `internal/domain/event/abandoned_payload.go` cho ABANDONED event ✅
- [x] Tạo `internal/domain/event/order_paid_payload.go` cho ORDER_PAID consumed event ✅
- [x] Tạo `internal/domain/event/envelope.go` với Kafka envelope + stream ID parser ✅
- [x] Tạo `internal/domain/event/stream_message.go` với Redis Stream format constants ✅
- [x] Tạo `internal/domain/event/builder.go` với factory functions và JSON helpers ✅
- [x] Viết unit tests: JSON serialization round-trip cho mỗi payload type ✅
- [x] Viết unit tests: ParseStreamID function ✅
- [x] Verify `go build ./internal/domain/event` compile thành công ✅
```

---

## 9. Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| Payload dùng `map[string]interface{}` trong envelope | Để handle mọi event type đa hình mà không cần discriminator logic |
| Timestamps là string epoch ms | Nhất quán với Redis Hash value format và tránh precision loss |
| `product_id` không trong DELETED payload | Consumer chỉ cần `sku_id` để xóa; không cần product info |
| Event types là string constants | Type-safe hơn enum nhưng vẫn compile-time checked |

---

*Tiếp theo: [05-lua-scripts.md](05-lua-scripts.md) — Lua scripts for Redis atomic operations.*
