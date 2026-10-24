package com.cloudprocure.identity.account;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** 在密码校验之前预占额度，使并发登录同样受限，且不查询账号是否存在。 */
public final class LoginAttemptLimiter {
    private final int accountLimit;
    private final int sourceLimit;
    private final long accountWindow;
    private final long sourceWindow;
    private final int maxEntries;
    private final LongSupplier ticker;
    // shortcut: 计数只在当前进程有效，多实例部署前改用共享存储原子计数。
    private final Map<String, Window> accounts = new HashMap<>();
    private final Map<String, Window> sources = new HashMap<>();

    public LoginAttemptLimiter(int accountLimit, Duration accountWindow,
                               int sourceLimit, Duration sourceWindow, int maxEntries) {
        this(accountLimit, accountWindow, sourceLimit, sourceWindow, maxEntries, System::nanoTime);
    }

    LoginAttemptLimiter(int accountLimit, Duration accountWindow,
                        int sourceLimit, Duration sourceWindow, int maxEntries, LongSupplier ticker) {
        if (accountLimit < 1 || sourceLimit < 1 || maxEntries < 1
                || accountWindow.isNegative() || accountWindow.isZero()
                || sourceWindow.isNegative() || sourceWindow.isZero()) {
            throw new IllegalArgumentException("登录限速额度、窗口和容量必须为正数");
        }
        this.accountLimit = accountLimit;
        this.sourceLimit = sourceLimit;
        this.accountWindow = accountWindow.toNanos();
        this.sourceWindow = sourceWindow.toNanos();
        this.maxEntries = maxEntries;
        this.ticker = ticker;
    }

    /** 返回 0 表示允许，否则为再次尝试前至少应等待的秒数。 */
    public synchronized long reserve(String loginName, String source) {
        long now = ticker.getAsLong();
        // 即使账号已经超限，也消耗来源额度，防止一个来源持续轰炸被限速的账号。
        long retry = reserve(sources, source, sourceLimit, sourceWindow, now);
        if (retry == 0) {
            // 与框架登录过滤器沿用 trim；摘要限制键大小，避免任意长用户名占满堆内存。
            retry = reserve(accounts, accountKey(loginName.trim()), accountLimit, accountWindow, now);
        }
        return retry == 0 ? 0 : TimeUnit.NANOSECONDS.toSeconds(retry - 1) + 1;
    }

    private long reserve(Map<String, Window> windows, String key, int limit, long duration, long now) {
        Window window = windows.get(key);
        if (window != null && now - window.startedAt >= duration) {
            windows.remove(key);
            window = null;
        }
        if (window == null) {
            if (windows.size() >= maxEntries) {
                windows.values().removeIf(value -> now - value.startedAt >= duration);
            }
            if (windows.size() >= maxEntries) {
                // 不淘汰仍有效的计数，否则攻击者可以通过大量新键清掉已有额度。
                return windows.values().stream()
                        .mapToLong(value -> duration - (now - value.startedAt)).min().orElse(duration);
            }
            windows.put(key, new Window(now));
            return 0;
        }
        if (window.attempts >= limit) {
            // 拒绝不延长窗口，避免连续失败造成永久锁定。
            return duration - (now - window.startedAt);
        }
        window.attempts++;
        return 0;
    }

    private static String accountKey(String loginName) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(loginName.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行时必须支持 SHA-256", exception);
        }
    }

    private static final class Window {
        private final long startedAt;
        private int attempts = 1;

        private Window(long startedAt) {
            this.startedAt = startedAt;
        }
    }
}
