package com.subflow.lock;

/** 락 획득 실패(대기 시간 초과). 중복 결제로 이어지지 않도록 요청을 거절한다. */
public class LockNotAcquiredException extends RuntimeException {

    private final String key;

    public LockNotAcquiredException(String key) {
        super("락을 획득하지 못했습니다: " + key);
        this.key = key;
    }

    public String getKey() {
        return key;
    }
}
