-- 漏桶限流脚本。
--
-- 原理：
--   把桶想象成容量为 limit 的队列，请求进入队列（level + 1），队列按
--   limit/window 的速率均匀泄漏。level + 1 <= capacity 时允许，否则拒绝。
--   状态保存在 HASH 中：
--     level = 当前队列占用量（浮点，因为泄漏是连续的）
--     ts    = 上次泄漏的 Redis 服务器时间（毫秒）
--   每次先按经过时间扣减 level（不低于 0），再判断是否还能放入一个请求：
--     - 能放入：level + 1，允许；
--     - 不能放入：等待 (level + 1 - capacity) / rate 毫秒后可腾出一个位置。
--
-- 与令牌桶的关系：
--   在“只做准入、不排队延迟执行”的模型下，两者是同一组约束的对偶表达：
--   令牌桶数“还剩多少可借”，漏桶数“已经积压多少”。真正让请求排队、按固定速率
--   放行属于流量整形，本模块不实现；这里的漏桶是准入计量器。
--
-- TTL：
--   键 TTL 设为 window。空闲一个完整 window 后 level 必然泄漏到 0，删键等价于保留；
--   拒绝路径不刷新 TTL，避免连续失败延长键生命周期。
--
-- KEYS[1] HASH 键
-- ARGV[1] capacity（正整数）
-- ARGV[2] window_ms（从满桶泄漏到 0 的时间，正整数）
-- 返回 {allowed, retry_after_ms, remaining}

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local window_ms = tonumber(ARGV[2])

if capacity == nil or capacity < 1 or window_ms == nil or window_ms < 1 then
    return redis.error_reply('rate-limit: invalid arguments')
end

local leak_per_ms = capacity / window_ms
local now_parts = redis.call('TIME')
local now_ms = tonumber(now_parts[1]) * 1000 + math.floor(tonumber(now_parts[2]) / 1000)

local values = redis.call('HMGET', key, 'level', 'ts')
local level
local last_leak

if values[1] == false then
    level = 0
    last_leak = now_ms
else
    level = tonumber(values[1])
    last_leak = tonumber(values[2])
    if level == nil or last_leak == nil then
        return redis.error_reply('rate-limit: corrupted leaky bucket')
    end
    if level < 0 or level > capacity + 0.000001 or last_leak < 0 then
        return redis.error_reply('rate-limit: leaky bucket state out of range')
    end
    local ttl = redis.call('PTTL', key)
    if ttl < 0 then
        -- -1 表示没有 TTL，-2 表示键已消失；HMGET 已读到值却出现 -2 属于状态不一致。
        return redis.error_reply('rate-limit: leaky bucket has no ttl')
    end
end

if last_leak > now_ms then
    return redis.error_reply('rate-limit: leaky bucket timestamp is in the future')
end

local elapsed_ms = now_ms - last_leak
level = math.max(0, level - elapsed_ms * leak_per_ms)

local allowed = 0
local retry_ms = 0
if level + 1 <= capacity + 0.000001 then
    level = level + 1
    allowed = 1
else
    retry_ms = math.ceil((level + 1 - capacity) / leak_per_ms)
    if retry_ms < 1 then
        retry_ms = 1
    end
end

redis.call('HSET', key, 'level', string.format('%.17g', level), 'ts', now_ms)
if allowed == 1 then
    redis.call('PEXPIRE', key, window_ms)
end

return {allowed, retry_ms, math.floor(capacity - level + 0.000001)}
