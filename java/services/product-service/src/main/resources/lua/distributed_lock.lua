-- distributed_lock.lua
-- Distributed lock using Redis SETNX with TTL
-- KEYS[1] = lock key
-- ARGV[1] = lock value (unique identifier)
-- ARGV[2] = TTL in seconds
-- Returns: 1 if acquired, 0 if already held

local result = redis.call('SET', KEYS[1], ARGV[1], 'NX', 'EX', ARGV[2])
if result then
    return 1
else
    return 0
end
