package redis

import (
	"fmt"
	"regexp"
	"strings"

	"github.com/ecommerce-platform/go/services/cart-service/internal/domain/port"
)

// CartKeyPattern validates cart key format with Hash Tag
var CartKeyPattern = regexp.MustCompile(`^\{cart:user:[^{}]+\}$`)

// SKUKeyPattern validates SKU ID format
var SKUKeyPattern = regexp.MustCompile(`^[a-zA-Z0-9_-]+$`)

// CartKeyBuilder builds Redis keys for cart operations
type CartKeyBuilder struct{}

// NewCartKeyBuilder creates a new CartKeyBuilder
func NewCartKeyBuilder() *CartKeyBuilder {
	return &CartKeyBuilder{}
}

// BuildCartKey builds a cart hash key with Hash Tag validation
// Format: {cart:user:{user_id}}
func (b *CartKeyBuilder) BuildCartKey(userID string) (string, error) {
	if userID == "" {
		return "", fmt.Errorf("user_id cannot be empty")
	}
	return fmt.Sprintf("{cart:user:%s}", userID), nil
}

// BuildOutboxStreamKey builds an outbox stream key for a cart
func (b *CartKeyBuilder) BuildOutboxStreamKey(userID string) (string, error) {
	cartKey, err := b.BuildCartKey(userID)
	if err != nil {
		return "", err
	}
	return cartKey + port.KeySuffixOutbox, nil
}

// BuildOutboxStreamKeyFromCartID builds an outbox stream key from cart ID
func (b *CartKeyBuilder) BuildOutboxStreamKeyFromCartID(cartID string) (string, error) {
	if !b.IsValidCartKey(cartID) {
		return "", fmt.Errorf("invalid cart_id format: %s", cartID)
	}
	return cartID + port.KeySuffixOutbox, nil
}

// BuildNegativeCacheKey builds a negative cache key for a cart
func (b *CartKeyBuilder) BuildNegativeCacheKey(userID string) (string, error) {
	cartKey, err := b.BuildCartKey(userID)
	if err != nil {
		return "", err
	}
	return cartKey + port.KeySuffixNotFound, nil
}

// BuildIdempotencyKey builds an idempotency key for ORDER_PAID
func (b *CartKeyBuilder) BuildIdempotencyKey(orderID string) string {
	return fmt.Sprintf("%s%s:%s", port.KeyPrefixIdempotency, orderID, port.IdempotencyStatusProcessing)
}

// BuildIdempotencyDoneKey builds an idempotency done key
func (b *CartKeyBuilder) BuildIdempotencyDoneKey(orderID string) string {
	return fmt.Sprintf("%s%s:%s", port.KeyPrefixIdempotency, orderID, port.IdempotencyStatusDone)
}

// ExtractUserIDFromCartID extracts user_id from a cart key
func (b *CartKeyBuilder) ExtractUserIDFromCartID(cartID string) (string, error) {
	if !b.IsValidCartKey(cartID) {
		return "", fmt.Errorf("invalid cart_id format: %s", cartID)
	}

	// Extract user_id from {cart:user:{user_id}}
	parts := strings.Split(cartID, ":")
	if len(parts) < 3 {
		return "", fmt.Errorf("cannot parse user_id from cart_id: %s", cartID)
	}

	userIDWithBrace := parts[2]
	userID := strings.TrimSuffix(userIDWithBrace, "}")
	return userID, nil
}

// IsValidCartKey validates if a cart key has correct Hash Tag format
func (b *CartKeyBuilder) IsValidCartKey(cartID string) bool {
	return CartKeyPattern.MatchString(cartID)
}

// IsValidSkuID validates if a SKU ID has correct format
func (b *CartKeyBuilder) IsValidSkuID(skuID string) bool {
	return SKUKeyPattern.MatchString(skuID)
}

// IsSameHashSlot checks if two keys share the same Hash Tag (for CROSSSLOT validation)
func (b *CartKeyBuilder) IsSameHashSlot(key1, key2 string) bool {
	hashTag1 := extractHashTag(key1)
	hashTag2 := extractHashTag(key2)
	return hashTag1 != "" && hashTag1 == hashTag2
}

// extractHashTag extracts the hash tag from a key
func extractHashTag(key string) string {
	start := strings.Index(key, "{")
	end := strings.Index(key, "}")
	if start == -1 || end == -1 || end <= start {
		return ""
	}
	return key[start : end+1]
}

// ValidateKeyPair validates that two keys have the same Hash Tag (for Lua script execution)
func (b *CartKeyBuilder) ValidateKeyPair(key1, key2 string) error {
	if !b.IsSameHashSlot(key1, key2) {
		return fmt.Errorf("CROSSSLOT error: keys %s and %s do not share the same Hash Tag", key1, key2)
	}
	return nil
}

// GetHashTag returns the Hash Tag from a key
func (b *CartKeyBuilder) GetHashTag(key string) string {
	return extractHashTag(key)
}
