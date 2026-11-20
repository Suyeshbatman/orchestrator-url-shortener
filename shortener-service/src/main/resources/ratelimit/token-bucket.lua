-- Token bucket, atomic on the Redis server (no locks, no read-modify-write race across instances).
-- Same algorithm as TokenBucket.java; keep the two in step.
-- KEYS[1] = rl:{scope}:{subject}
-- ARGV    = capacity, tokens per ms, now (ms, caller's clock), key TTL (ms)
-- Returns {allowed ("1"/"0"), tokens left} as strings so fractions survive the reply.
local capacity = tonumber(ARGV[1])
local rate = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local ttl = tonumber(ARGV[4])

local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts = tonumber(state[2])
if tokens == nil or ts == nil then
  tokens = capacity
  ts = now
end

-- Instances' clocks may differ slightly: never refill on, or store, an older timestamp.
if now > ts then
  tokens = math.min(capacity, tokens + (now - ts) * rate)
  ts = now
end

local allowed = 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
end

redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(ts))
redis.call('PEXPIRE', KEYS[1], ttl)
return {tostring(allowed), tostring(tokens)}
