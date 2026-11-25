package com.subflow.lock;

import com.subflow.config.SubFlowProperties;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Redisson 분산 락 — 운영용.
 *
 * <p>여러 인스턴스의 배치가 같은 구독을 동시에 청구하지 못하도록 구독 단위로 직렬화한다.
 * 락 임대 시간(lease time)은 작업 시간보다 길게 잡아, 작업 중 락이 풀려 중복 청구되는 일을 막는다.
 */
@Component
@ConditionalOnProperty(name = "subflow.lock.provider", havingValue = "redis", matchIfMissing = true)
public class RedissonLockProvider implements LockProvider {

    private final RedissonClient redissonClient;
    private final Duration waitTime;
    private final Duration leaseTime;

    public RedissonLockProvider(RedissonClient redissonClient, SubFlowProperties properties) {
        this.redissonClient = redissonClient;
        this.waitTime = properties.lock().waitTime();
        this.leaseTime = properties.lock().leaseTime();
    }

    @Override
    public <T> T withLock(String key, Supplier<T> action) {
        RLock lock = redissonClient.getLock(key);
        boolean acquired;
        try {
            acquired = lock.tryLock(waitTime.toMillis(), leaseTime.toMillis(), TimeUnit.MILLISECONDS);
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
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public String providerName() {
        return "redis";
    }
}
