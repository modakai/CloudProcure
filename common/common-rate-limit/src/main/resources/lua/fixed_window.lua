-- 固定窗口限流脚本。
--
-- 原理：
--   一个窗口只对应一个整数计数器。键不存在时，用 SET key 1 PX window 原子地建立
--   “从此刻开始、window 毫秒后过期”的窗口；之后窗口内每获准一次就 INCR。
--   计数达到 limit 后拒绝，并且不 INCR、不刷新 TTL，所以连续被拒绝的请求不会把
--   窗口无限续期（否则攻击者可以让账号永久锁定）。
--
-- 代价：
--   固定窗口在边界处允许最多 2 倍突发：例如 limit=5/分钟，第 59 秒来 5 个、第 61 秒
--   再来 5 个，跨边界看瞬时通过了 10 个。需要严格平滑时选滑动窗口/令牌桶。
--
-- KEYS[1] 计数器键
-- ARGV[1] limit（正整数）
-- ARGV[2] window_ms（正整数）
-- 返回 {allowed, retry_after_ms, remaining}
--   allowed: 1 允许 / 0 拒绝
--   拒绝时 retry_after_ms 为当前键的剩余 TTL
--   数据损坏时用 error_reply 让客户端 fail-closed

local key = KEYS[1]
local limit = tonumber(ARGV[1])
local window_ms = tonumber(ARGV[2])

if limit == nil or limit < 1 or window_ms == nil or window_ms < 1 then
    return redis.error_reply('rate-limit: invalid arguments')
end

local value = redis.call('GET', key)
if value == false then
    -- 首次获准：写入 1 并设置 TTL。SET 与后续 INCR 都在同一个 Lua 脚本中原子执行，
    -- 不会出现两个实例同时看到“键不存在”而各自写入完整额度的竞态。
    redis.call('SET', key, 1, 'PX', window_ms)
    return {1, 0, limit - 1}
end

-- 只接受纯十进制整数。字符串 "1.5" 或 "abc" 能通过 tonumber，却会让 INCR 报错或
-- 被误读成有效计数；这类状态视为损坏，不能 fail-open。
if not string.match(value, '^%d+$') then
    return redis.error_reply('rate-limit: corrupted fixed-window counter')
end
local count = tonumber(value)
if count < 1 then
    return redis.error_reply('rate-limit: fixed-window counter is not positive')
end

local ttl = redis.call('PTTL', key)
if ttl == -2 then
    -- Lua 脚本执行期间没有其他命令插入；GET 成功而 PTTL=-2 说明键状态异常。
    return redis.error_reply('rate-limit: fixed-window counter disappeared')
end
if ttl < 0 then
    -- 没有 TTL 的计数器会永久占用额度；不能把它当成一个正常窗口继续使用。
    return redis.error_reply('rate-limit: fixed-window counter has no ttl')
end

if count >= limit then
    -- 拒绝路径不 INCR、不 PEXPIRE：既不放大计数，也不延长窗口。
    return {0, ttl, 0}
end

local new_count = redis.call('INCR', key)
return {1, 0, limit - new_count}
