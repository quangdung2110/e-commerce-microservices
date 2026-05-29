package keybuilder

import (
	"fmt"
	"hash/crc32"
	"strconv"
	"strings"
)

const (
	KeyPrefixCart     = "{cart}"
	KeyPrefixNegative = "{nc}"
	KeyPrefixEvents   = "{cart}:events"
)

// KeyBuilder builds all Redis keys for the cart domain.
// Thread-safe (stateless, pure functions).
//
// Key format reference:
//   - Cart hash:           {cart}:{user_id}
//   - Negative cache:      {nc}:{user_id}
//   - Event stream shard:  {cart}:events:{shard}
type KeyBuilder struct {
	numShards uint32
}

// New creates a KeyBuilder with the configured number of stream shards.
func New(numShards uint32) *KeyBuilder {
	return &KeyBuilder{numShards: numShards}
}

// CartKey returns the Redis hash key for a user's cart.
// Uses Hash Tag {cart} so Redis Cluster can route by user_id.
func (kb *KeyBuilder) CartKey(userID string) string {
	return fmt.Sprintf("%s:%s", KeyPrefixCart, userID)
}

// NegativeCacheKey returns the negative cache key for a user.
// Negative cache short-circuits "cart not found" responses after DB confirmed absence.
func (kb *KeyBuilder) NegativeCacheKey(userID string) string {
	return fmt.Sprintf("%s:%s", KeyPrefixNegative, userID)
}

// StreamShard returns the event stream shard key for the given shard index.
// Streams are sharded by user_id (via CRC32) to parallelize relay workers.
func (kb *KeyBuilder) StreamShard(shard uint32) string {
	return fmt.Sprintf("%s:%d", KeyPrefixEvents, shard)
}

// StreamShardByUserID computes the shard index for a user using CRC32.
// Shard = crc32(userID) % numShards.
// This determines which stream shard receives events for a given user.
// The same formula must be used in outbox_relay workers to read from the right shard.
func (kb *KeyBuilder) StreamShardByUserID(userID string) uint32 {
	return crc32.ChecksumIEEE([]byte(userID)) % kb.numShards
}

// StreamShardKey returns the event stream shard key for a given user.
// Convenience method combining StreamShardByUserID + StreamShard.
func (kb *KeyBuilder) StreamShardKey(userID string) string {
	return kb.StreamShard(kb.StreamShardByUserID(userID))
}

// StreamShardKeyExplicit returns the stream shard key for a user using an explicit shard index.
// Use this when the shard is already known and you want to avoid recomputing CRC32.
func (kb *KeyBuilder) StreamShardKeyExplicit(userID string, shard uint32) string {
	return kb.StreamShard(shard)
}

// ParseCartKey extracts the user_id from a cart key.
// Inverse of CartKey. Returns empty string if key format is invalid.
func (kb *KeyBuilder) ParseCartKey(cartKey string) string {
	// Format: {cart}:{user_id}
	// We need to strip the leading "{cart}:" (7 chars) to get user_id.
	prefix := KeyPrefixCart + ":"
	if !strings.HasPrefix(cartKey, prefix) {
		return ""
	}
	return cartKey[len(prefix):]
}

// ShardCount returns the configured number of stream shards.
func (kb *KeyBuilder) ShardCount() uint32 {
	return kb.numShards
}

// RehydrateItemsJSON is a helper that builds the JSON array format expected by the rehydrate Lua script.
// items is a map of skuID -> CartItemValue JSON string.
// Returns a JSON array string: [{"sku_id":"...","value":"..."}, ...]
func RehydrateItemsJSON(items map[string]string) string {
	if len(items) == 0 {
		return "[]"
	}
	// Build JSON array manually to avoid allocating a struct and extra encoder.
	// Format: [{"sku_id":"...","value":"..."}]
	var b []byte
	b = append(b, '[')
	first := true
	for skuID, value := range items {
		if !first {
			b = append(b, ',')
		}
		first = false
		b = append(b, `{"sku_id":`...)
		b = append(b, strconv.AppendQuote(nil, skuID)...)
		b = append(b, `,"value":`...)
		b = append(b, strconv.AppendQuote(nil, value)...)
		b = append(b, `}`...)
	}
	b = append(b, ']')
	return string(b)
}

// SkuIDsJSON is a helper that encodes a list of SKU IDs into a JSON array.
// Returns: ["sku1","sku2",...]
func SkuIDsJSON(skuIDs []string) string {
	if len(skuIDs) == 0 {
		return "[]"
	}
	var b []byte
	b = append(b, '[')
	for i, sku := range skuIDs {
		if i > 0 {
			b = append(b, ',')
		}
		b = append(b, strconv.AppendQuote(nil, sku)...)
	}
	b = append(b, ']')
	return string(b)
}
