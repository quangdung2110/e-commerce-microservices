-- Cart Service — purge the cart of a deleted account, Redis-only v2
--
-- Atomic boundary for the USER_DELETED privacy purge. All KEYS use the same
-- {bNN} hash tag. The script is idempotent: a second run, or a user who never
-- had a cart, deletes nothing and reports purged = false.
--
-- KEYS[1] items hash; KEYS[2] meta hash; KEYS[3] abandonment ZSET.
--
-- ARGV[1] user_token
--
-- Pending outbox entries are deliberately left alone: they carry only the
-- internal cart_id/user_id, are relayed within minutes, and Notification
-- drops a delivery for a deleted account (no_recipient).

local items_key = KEYS[1]
local meta_key = KEYS[2]
local index_key = KEYS[3]
local user_token = ARGV[1]

local removed = redis.call('DEL', items_key)
removed = removed + redis.call('DEL', meta_key)
removed = removed + redis.call('ZREM', index_key, user_token)

return cjson.encode({ purged = removed > 0 })
