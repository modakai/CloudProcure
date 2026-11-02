-- 令牌桶限流脚本。
--
-- 原理：
--   桶容量为 limit，按 limit/window 的速率补充令牌；每个请求取走 1 个令牌。
--   状态保存在一个 HASH 中：
--     tokens = 当前令牌数（浮点，允许小数补充）
--     ts     = 上次补充的 Redis 服务器时间（毫秒）
--   每次请求先按经过时间补充 tokens（不超过容量），再判断是否 >= 1：
--     - 有令牌：扣 1，允许；
--     - 没有令牌：拒绝，等待 (1 - tokens) / rate 毫秒后可获得下一个令牌。
--   补充与扣减都在同一个 Lua 脚本中完成，多实例共享同一份状态。
--
-- 与固定/滑动窗口的区别：
--   允许最多 limit 个请求的瞬时突发，同时把长期平均速率限制在 limit/window。
--   这是“桶容量 + 补充速率”的经典 token bucket，而不是每个窗口独立计数。
--
-- TTL：
--   键 TTL 设为 window。空闲一个完整 window 后桶必然补满，此时删掉键与保留键等价；
--   拒绝路径不刷新 TTL，避免连续失败把键永久保留。
--
-- KEYS[1] HASH 键
-- ARGV[1] limit（桶容量，正整数）
-- ARGV[2] window_ms（补满整桶的时间，正整数）
-- 返回 {allowed, retry_after_ms, remaining}

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local window_ms = tonumber(ARGV[2])

if capacity == nil or capacity < 1 or window_ms == nil or window_ms < 1 then
    return redis.error_reply('rate-limit: invalid arguments')
end

-- 每秒/每毫秒补充速率。浮点误差在后续比较中使用小容差。
local rate_per_ms = capacity / window_ms
local now_parts = redis.call('TIME')
local now_ms = tonumber(now_parts[1]) * 1000 + math.floor(tonumber(now_parts[2]) / 1000)

local values = redis.call('HMGET', key, 'tokens', 'ts')
local tokens
local last_refill

if values[1] == false then
    -- 首次访问等价于一个满桶；随后本次请求立刻取走 1 个。
    tokens = capacity
    last_refill = now_ms
else
    tokens = tonumber(values[1])
    last_refill = tonumber(values[2])
    if tokens == nil or last_refill == nil then
        return redis.error_reply('rate-limit: corrupted token bucket')
    end
    -- 允许极小的浮点误差，但不允许明显超出容量或负数。
    if tokens < 0 or tokens > capacity + 0.000001 or last_refill < 0 then
        return redis.error_reply('rate-limit: token bucket state out of range')
    end
    local ttl = redis.call('PTTL', key)
    if ttl < 0 then
        -- -1 表示没有 TTL，-2 表示键已消失；HMGET 已读到值却出现 -2 属于状态不一致。
        return redis.error_reply('rate-limit: token bucket has no ttl')
    end
end

if last_refill > now_ms then
    -- Redis TIME 不应回退；未来时间戳会让“经过时间”变成负数，按损坏状态处理。
    return redis.error_reply('rate-limit: token bucket timestamp is in the future')
end

local elapsed_ms = now_ms - last_refill
tokens = math.min(capacity, tokens + elapsed_ms * rate_per_ms)

local allowed = 0
local retry_ms = 0
if tokens >= 1 then
    tokens = tokens - 1
    allowed = 1
else
    -- 需要补足到 1 个令牌；向上取整到毫秒，保证 Retry-After 为正。
    retry_ms = math.ceil((1 - tokens) / rate_per_ms)
    if retry_ms < 1 then
        retry_ms = 1
    end
end

-- %.17g 能无损往返 double；直接 tostring 在极端值上可能丢精度。
redis.call('HSET', key, 'tokens', string.format('%.17g', tokens), 'ts', now_ms)
if allowed == 1 then
    redis.call('PEXPIRE', key, window_ms)
end

return {allowed, retry_ms, math.floor(tokens + 0.000001)}
