package com.subflow.lock;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 인메모리 락 — 테스트·단일 인스턴스용.
 *
 * <p>같은 JVM 안에서만 유효하다. 인스턴스가 여러 대면 {@link RedissonLockProvider} 를 쓴다.
 * 재진입 가능한 락을 키별로 두고, 대기 시간 안에 얻지 못하면 {@link LockNotAcquiredException} 을 던진다.
 */
@Component
@ConditionalOnProperty(name = "subflow.lock.provider", havingValue = "in-memory")
public class InMemoryLockProvider implements LockProvider {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private final Duration waitTime;

    public InMemoryLockProvider(com.subflow.config.SubFlowProperties properties) {
        this.waitTime = properties.lock().waitTime();
    }

    @Override
    public <T> T withLock(String key, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        boolean acquired = false;
        try {
            acquired = lock.tryLock(waitTime.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockNotAcquiredException(key);
        }
        if (!acquired) {
            throw new LockNotAcquiredException(key);
        }
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String providerName() {
        return "in-memory";
    }
}
