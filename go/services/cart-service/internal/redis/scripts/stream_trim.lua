-- Lua Script: Stream Trim
--
-- Atomically trims empty streams and removes them from tracking sets
-- Called by Stream Trimmer worker
--
-- KEYS[1]: stream_key - The outbox stream key to potentially delete
-- KEYS[2]: active_streams_key - ZSET containing active streams
--
-- ARGV[1]: stream_key - The stream key name (same as KEYS[1])
--
-- Returns: 1 if stream was deleted, 0 if skipped, -1 if stream has pending messages

local stream_key = ARGV[1]

-- Check if stream exists and has messages
local stream_len = redis.call('XLEN', stream_key)

if stream_len == 0 then
    -- Check if stream has consumer groups (pending messages might exist)
    local groups = redis.call('XINFO', 'GROUPS', stream_key)

    if #groups == 0 then
        -- No groups means no pending consumers, safe to delete
        redis.call('DEL', stream_key)
        redis.call('ZREM', KEYS[2], stream_key)
        return 1
    else
        -- Has groups, might have pending messages - skip
        return 0
    end
end

-- Stream has messages - check for pending
local groups = redis.call('XINFO', 'GROUPS', stream_key)
if #groups > 0 then
    -- Has groups but might be empty - let trimmer handle via MINID
    return 0
end

return 0
