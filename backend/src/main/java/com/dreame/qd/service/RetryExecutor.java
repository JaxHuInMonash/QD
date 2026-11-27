package com.dreame.qd.service;

import com.dreame.qd.model.QdException;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * 04 1.6 重试约定：仅对可重试错误（超时 / 网络、5xx、429）自动重试 1 次，间隔默认 2 秒；
 * 401 / 403 / 权限不足等不重试（重试只会加重 Jira 账号锁定风险）。
 */
public class RetryExecutor {
    public interface Sleeper { void sleep(long millis) throws InterruptedException; }

    /** @param retried 实际重试次数（0 或 1）；成功时 failure 为空，失败时 value 为空 */
    public record Outcome<T>(T value, int retried, QdException failure) {
        public boolean success() { return failure == null; }
    }

    private final Duration interval;
    private final Sleeper sleeper;

    public RetryExecutor(Duration interval) { this(interval, Thread::sleep); }

    public RetryExecutor(Duration interval, Sleeper sleeper) {
        this.interval = interval;
        this.sleeper = sleeper;
    }

    public <T> Outcome<T> run(Supplier<T> action) {
        try {
            return new Outcome<>(action.get(), 0, null);
        } catch (QdException first) {
            if (!first.retryable()) return new Outcome<>(null, 0, first);
            try {
                sleeper.sleep(interval.toMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return new Outcome<>(null, 0, first);
            }
            try {
                return new Outcome<>(action.get(), 1, null);
            } catch (QdException second) {
                return new Outcome<>(null, 1, second);
            }
        }
    }
}
