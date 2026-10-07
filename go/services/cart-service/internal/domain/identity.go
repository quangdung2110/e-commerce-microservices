package domain

import (
	"crypto/sha256"
	"encoding/base64"
	"fmt"
	"strings"

	"github.com/zeebo/xxh3"
)

// BucketCount is the fixed number of cart buckets in the v2 namespace.
//
// The value, the routing function and the namespace form a data contract
// (docs/cart/data.md §3, ADR-008). Changing any of them requires a new
// namespace and a migration runbook, never a runtime config flip.
const BucketCount = 64

// Namespace is the Redis key namespace version owned by this key layout.
const Namespace = "v2"

// userTokenLength is the number of base64url characters kept from the
// SHA-256 digest of the canonical user id. 22 characters carry 132 bits,
// which is far past any collision concern for a cart keyspace.
const userTokenLength = 22

// Identity is the derived routing and naming information for one cart.
//
// Cart never accepts a cart_id from a public client: everything here is
// derived from the trusted user id the API Gateway injects, from the
// ORDER_PAID payload, or from a ZSET member the marker loop scans.
type Identity struct {
	// UserID is the trusted, canonical user identifier.
	UserID string
	// UserToken is the opaque, one-way token used in Redis keys, in the
	// abandonment ZSET and inside cart_id. It never reveals the user id.
	UserToken string
	// CartID is the logical cart identity, c1:<user_token>. It is stable
	// across Redis key layout changes.
	CartID string
	// Bucket is the cart bucket in [0, BucketCount).
	Bucket int
}

// NewIdentity derives the routing identity for a user id.
//
// canonicalUserID trims surrounding space and lowercases, so that the same
// user always lands in the same bucket regardless of how an upstream service
// happens to spell a UUID.
func NewIdentity(userID string) (Identity, error) {
	canonical := canonicalUserID(userID)
	if canonical == "" {
		return Identity{}, ErrUnauthenticated
	}

	token := UserToken(canonical)
	return Identity{
		UserID:    userID,
		UserToken: token,
		CartID:    CartIDFromToken(token),
		Bucket:    BucketOf(canonical),
	}, nil
}

// IdentityFromToken rebuilds the parts of an identity that are derivable from
// a user token alone. The marker loop reads tokens out of the abandonment
// ZSET and never sees a user id, so UserID is left empty; the Lua script
// reads the real user id out of the meta hash.
func IdentityFromToken(bucket int, token string) Identity {
	return Identity{
		UserToken: token,
		CartID:    CartIDFromToken(token),
		Bucket:    bucket,
	}
}

// UserToken derives the opaque cart token for a canonical user id.
func UserToken(canonicalUserID string) string {
	sum := sha256.Sum256([]byte(canonicalUserID))
	return base64.RawURLEncoding.EncodeToString(sum[:])[:userTokenLength]
}

// CartIDFromToken formats the logical cart id.
func CartIDFromToken(token string) string {
	return "c1:" + token
}

// BucketOf routes a canonical user id to one of the BucketCount buckets.
func BucketOf(canonicalUserID string) int {
	return int(xxh3.HashString(canonicalUserID) % BucketCount)
}

// BucketTag formats the Redis Cluster hash tag for a bucket, e.g. {b07}.
// Every key a Lua script touches for one cart carries this same tag.
func BucketTag(bucket int) string {
	return fmt.Sprintf("{b%02d}", bucket)
}

func canonicalUserID(userID string) string {
	return strings.ToLower(strings.TrimSpace(userID))
}
