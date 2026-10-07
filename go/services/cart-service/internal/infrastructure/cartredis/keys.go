// Package cartredis implements the Redis source of truth for Cart: the item
// and meta hashes, the abandonment scheduler ZSET, HTTP idempotency records
// and the 64 outbox streams.
package cartredis

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"

	"cart-service/internal/domain"
)

// keyPrefix is the namespaced root of every Cart key.
const keyPrefix = "cart:" + domain.Namespace

// Keys builds every Redis key Cart owns.
//
// Every key a single Lua invocation touches for one cart carries the same
// {bNN} hash tag, so the script stays inside one Redis Cluster slot. Getting
// this wrong surfaces as CROSSSLOT at runtime, which is why the key layout
// lives in one place and is covered by tests.
type Keys struct{}

// bucketRoot is the shared, slot-pinned prefix for one bucket.
func bucketRoot(bucket int) string {
	return fmt.Sprintf("%s:%s", keyPrefix, domain.BucketTag(bucket))
}

// Items is the hash holding one item JSON per SKU field.
func (Keys) Items(id domain.Identity) string {
	return fmt.Sprintf("%s:u:%s:items", bucketRoot(id.Bucket), id.UserToken)
}

// Meta is the hash holding cart identity and lifecycle metadata.
func (Keys) Meta(id domain.Identity) string {
	return fmt.Sprintf("%s:u:%s:meta", bucketRoot(id.Bucket), id.UserToken)
}

// Index is the bucket's abandonment scheduler ZSET. Members are user tokens
// and scores are abandonment deadlines in Unix milliseconds. It has no TTL.
func (Keys) Index(bucket int) string {
	return fmt.Sprintf("%s:abandonment-index", bucketRoot(bucket))
}

// Outbox is the bucket's durable outbox stream. There is exactly one per
// bucket, colocated with the cart state so mark_abandoned.lua can transition
// the cart and XADD in one atomic step.
func (Keys) Outbox(bucket int) string {
	return fmt.Sprintf("%s:outbox", bucketRoot(bucket))
}

// Idempotency is the record for one HTTP write. It is scoped to one user and
// operation as required by the public contract, while retaining the bucket tag
// needed to share a Redis Cluster slot with the cart state. Hashing the client
// supplied key keeps arbitrary header text out of the Redis keyspace.
func (Keys) Idempotency(id domain.Identity, operation, key string) string {
	digest := sha256.Sum256([]byte(key))
	return fmt.Sprintf("%s:u:%s:idemp:http:%s:%s",
		bucketRoot(id.Bucket), id.UserToken, operation, hex.EncodeToString(digest[:]))
}
