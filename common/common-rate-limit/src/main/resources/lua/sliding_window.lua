-- 滑动窗口限流脚本。
--
-- 原理：
--   用 Redis ZSET 保存“最近一个窗口内获准请求”的时间戳（score 使用 Redis 服务器时间，
--   单位微秒，避免应用机器时钟漂移）。每次请求先 ZREMRANGEBYSCORE 删除早于
--   now-window 的成员，再 ZCARD 统计当前窗口内的获准数：
--     - 达到 limit：拒绝，并按“最老成员 + window - now”计算 Retry-After；
--     - 未达 limit：ZADD 当前时间戳，再刷新键的 TTL。
--   ZREMRANGEBYSCORE、ZCARD、ZADD 在同一个 Lua 脚本中执行，所以并发实例看到的是
--   同一份状态，不会各自读到“还差一个”而同时放行。
--
-- 与固定窗口的区别：
--   任意长度为 window 的滚动区间都最多 limit 次，没有“临界点双倍突发”。
--
-- 代价：
--   ZSET 最多保留 limit 个成员，内存随额度线性增长；适合登录、验证码等中小额度场景，
--   不适合每秒百万级、limit 很大的全站限流。
--
-- KEYS[1] ZSET 键
-- ARGV[1] limit（正整数）
-- ARGV[2] window_ms（正整数）
-- 返回 {allowed, retry_after_ms, remaining}

local key = KEYS[1]
local limit = tonumber(ARGV[1])
local window_ms = tonumber(ARGV[2])

if limit == nil or limit < 1 or window_ms == nil or window_ms < 1 then
    return redis.error_reply('rate-limit: invalid arguments')
end

-- 用 Redis 的 TIME 而不是应用时间：多实例时钟不一致时仍按同一时间轴统计。
local now_parts = redis.call('TIME')
local now_us = tonumber(now_parts[1]) * 1000000 + tonumber(now_parts[2])
local window_us = window_ms * 1000
local cutoff = now_us - window_us

-- 删除 score <= cutoff 的成员：正好在窗口边界上的请求已经不再占用额度。
redis.call('ZREMRANGEBYSCORE', key, '-inf', cutoff)
local count = redis.call('ZCARD', key)

-- 已有键必须带 TTL。没有 TTL 的 ZSET 会永久保留历史成员，最终把额度卡死。
local ttl = redis.call('PTTL', key)
if ttl == -1 then
    return redis.error_reply('rate-limit: sliding-window key has no ttl')
end

if count >= limit then
    local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
    if #oldest < 2 then
        return redis.error_reply('rate-limit: sliding-window state is inconsistent')
    end
    local oldest_score = tonumber(oldest[2])
    if oldest_score == nil then
        return redis.error_reply('rate-limit: sliding-window score is not numeric')
    end
    -- 等最老成员滑出窗口后才能再放行一个；向上取整到毫秒，至少 1ms。
    local retry_us = oldest_score + window_us - now_us
    if retry_us < 1000 then
        retry_us = 1000
    end
    return {0, math.ceil(retry_us / 1000), 0}
end

-- member 必须唯一：同一微秒内的连续脚本调用可能得到相同 score。
-- 以微秒时间戳为前缀，冲突时追加序号重试；ZADD 返回 1 表示确实新增成功。
local base_member = string.format('%.0f', now_us)
local suffix = 0
local added = 0
while added == 0 do
    added = redis.call('ZADD', key, now_us, base_member .. ':' .. suffix)
    if added == 0 then
        suffix = suffix + 1
        if suffix > 1000 then
            return redis.error_reply('rate-limit: cannot allocate sliding-window member')
        end
    end
end

-- TTL 只需覆盖“最后一个成员滑出窗口”的时间；允许时刷新，拒绝时不刷新。
redis.call('PEXPIRE', key, window_ms + 1)
return {1, 0, limit - count - 1}
