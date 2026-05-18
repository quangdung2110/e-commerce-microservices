package validator

import "regexp"

var (
	skuIDPattern = regexp.MustCompile(`^[a-zA-Z0-9_-]+$`)
	cartIDPattern = regexp.MustCompile(`^\{cart:user:[^{}]+\}$`)
)

// IsValidSkuID validates SKU ID format
func IsValidSkuID(skuID string) bool {
	return skuIDPattern.MatchString(skuID)
}

// IsValidCartID validates cart ID format
func IsValidCartID(cartID string) bool {
	return cartIDPattern.MatchString(cartID)
}
