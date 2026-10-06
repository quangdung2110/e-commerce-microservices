// Package media turns the Product image reference stored on a cart line into
// the public URL the browser loads.
//
// Product gRPC Sku.image_url carries the object storage key of the
// representative image, not an absolute URL (product_service.proto). Cart
// stores that key unchanged and builds the CDN URL only when it renders a
// response, exactly like Search and the Product Detail BFF
// (docs/product-detail-bff ADR-005). Changing the CDN therefore never requires
// rewriting carts in Redis.
package media

import (
	"net/url"
	"strings"
)

// URLs builds public media URLs from storage keys.
type URLs struct {
	cdnBaseURL     string
	placeholderURL string
}

// NewURLs returns a builder. cdnBaseURL has no trailing slash; both values are
// validated by the configuration loader.
func NewURLs(cdnBaseURL, placeholderURL string) URLs {
	return URLs{cdnBaseURL: cdnBaseURL, placeholderURL: placeholderURL}
}

// Image returns the URL for a stored image reference.
//
// An empty reference means the SKU has no image and yields the configured
// placeholder. A reference that is already an absolute http(s) URL is a line
// written before Product switched to storage keys and is returned unchanged.
// Anything else is a storage key: the CDN base URL plus the key with every
// path segment percent-encoded, so an unusual key can never break the URL.
func (u URLs) Image(reference string) string {
	if reference == "" {
		return u.placeholderURL
	}
	if isAbsoluteHTTPURL(reference) {
		return reference
	}

	segments := strings.Split(strings.TrimLeft(reference, "/"), "/")
	for i, segment := range segments {
		segments[i] = url.PathEscape(segment)
	}
	return u.cdnBaseURL + "/" + strings.Join(segments, "/")
}

func isAbsoluteHTTPURL(value string) bool {
	parsed, err := url.Parse(value)
	if err != nil || parsed.Host == "" {
		return false
	}
	return parsed.Scheme == "http" || parsed.Scheme == "https"
}
