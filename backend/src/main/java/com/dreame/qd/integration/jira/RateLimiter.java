package com.dreame.qd.integration.jira;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

/** 滑动窗口限流：任意 windowMillis 内最多 max 次请求（Jira 网关每分钟 100 次，本项目取 90，E5）。 */
public class RateLimiter {
    private final int max;
    private final long windowMillis;
    private final LongSupplier nowMillis;
    private final Sleeper sleeper;
    private final Deque<Long> stamps = new ArrayDeque<>();

    public interface Sleeper { void sleep(long millis) throws InterruptedException; }

    public RateLimiter(int max, long windowMillis) {
        this(max, windowMillis, System::currentTimeMillis, Thread::sleep);
    }

    public RateLimiter(int max, long windowMillis, LongSupplier nowMillis, Sleeper sleeper) {
        this.max = max;
        this.windowMillis = windowMillis;
        this.nowMillis = nowMillis;
        this.sleeper = sleeper;
    }

    /** 取得一个许可，必要时阻塞等待。 */
    public synchronized void acquire() {
        while (true) {
            long now = nowMillis.getAsLong();
            while (!stamps.isEmpty() && now - stamps.peekFirst() >= windowMillis) stamps.pollFirst();
            if (stamps.size() < max) {
                stamps.addLast(now);
                return;
            }
            long wait = windowMillis - (now - stamps.peekFirst());
            try {
                sleeper.sleep(Math.max(wait, 1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待 Jira 限流窗口时被中断", e);
            }
        }
    }
}
