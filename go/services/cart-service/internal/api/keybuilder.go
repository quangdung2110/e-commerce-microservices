package api

import (
	"fmt"
	"regexp"
	"strings"

	"github.com/yourusername/spring-event-sourcing/go/services/cart-service/internal/common/constants"
)

var (
	cartKeyPattern = regexp.MustCompile(`^\{cart:user:[^{}]+\}$`)
	skuIDPattern  = regexp.MustCompile(`^[a-zA-Z0-9_-]+$`)
)

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
	return cartKey + constants.KeySuffixOutbox, nil
}

// BuildNegativeCacheKey builds a negative cache key
func (b *CartKeyBuilder) BuildNegativeCacheKey(userID string) (string, error) {
	cartKey, err := b.BuildCartKey(userID)
	if err != nil {
		return "", err
	}
	return cartKey + constants.KeySuffixNotFound, nil
}

// ExtractUserIDFromCartID extracts user_id from cart key
func (b *CartKeyBuilder) ExtractUserIDFromCartID(cartID string) (string, error) {
	if !b.IsValidCartKey(cartID) {
		return "", fmt.Errorf("invalid cart_id format: %s", cartID)
	}

	parts := strings.Split(cartID, ":")
	if len(parts) < 3 {
		return "", fmt.Errorf("cannot parse user_id from cart_id: %s", cartID)
	}

	userIDWithBrace := parts[2]
	userID := strings.TrimSuffix(userIDWithBrace, "}")
	return userID, nil
}

// IsValidCartKey validates cart key format
func (b *CartKeyBuilder) IsValidCartKey(cartID string) bool {
	return cartKeyPattern.MatchString(cartID)
}

// IsValidSkuID validates SKU ID format
func (b *CartKeyBuilder) IsValidSkuID(skuID string) bool {
	return skuIDPattern.MatchString(skuID)
}
