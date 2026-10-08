package cartredis

import (
	_ "embed"

	"github.com/redis/go-redis/v9"
)

// The four scripts are the only writers of cart state. They are copied
// verbatim from docs/cart/scripts, which stays the canonical source, and are
// embedded so the container image carries them.
//
//go:embed scripts/upsert_item.lua
var upsertItemLua string

//go:embed scripts/remove_items.lua
var removeItemsLua string

//go:embed scripts/mark_abandoned.lua
var markAbandonedLua string

//go:embed scripts/purge_cart.lua
var purgeCartLua string

// redis.Script runs EVALSHA first and falls back to EVAL on NOSCRIPT, which
// keeps a script working across a Redis restart or a failover to a replica
// that never saw the SCRIPT LOAD.
var (
	upsertItemScript    = redis.NewScript(upsertItemLua)
	removeItemsScript   = redis.NewScript(removeItemsLua)
	markAbandonedScript = redis.NewScript(markAbandonedLua)
	purgeCartScript     = redis.NewScript(purgeCartLua)
)
