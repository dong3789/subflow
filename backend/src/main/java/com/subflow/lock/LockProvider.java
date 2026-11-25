package com.subflow.lock;

import java.util.function.Supplier;

/**
 * 구독 단위 직렬화를 위한 락 추상화.
 *
 * <p>운영은 Redis(Redisson) 분산 락, 테스트·단일 인스턴스는 인메모리 락을 쓴다.
 * 서비스 코드는 이 인터페이스에만 의존하므로 Redis 없이도 결제 코어를 그대로 검증할 수 있다.
 *
 * <p>락은 마지막 방어선이 아니다. 락이 만료되거나 인스턴스가 늘어나도
 * {@code payments.idempotency_key} 유니크 제약과 비관적 락이 중복 결제를 최종 차단한다.
 */
public interface LockProvider {

    /**
     * 락을 잡고 작업을 실행한다.
     *
     * @param key    락 키(예: {@code subflow:lock:sub:42})
     * @param action 락 안에서 실행할 작업
     * @throws LockNotAcquiredException 대기 시간 안에 락을 얻지 못했을 때
     */
    <T> T withLock(String key, Supplier<T> action);

    /** 반환값이 없는 작업용. */
    default void withLock(String key, Runnable action) {
        withLock(key, () -> {
            action.run();
            return null;
        });
    }

    /** 구현 이름(로그·헬스 표시용). */
    String providerName();
}
