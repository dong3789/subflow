package com.subflow.domain.outbox;

/** Outbox 이벤트 발행 상태. */
public enum OutboxStatus {

    /** 발행 대기(또는 재시도 대기). */
    PENDING,

    /** 브로커/수신측에 전달 완료. */
    PUBLISHED,

    /** 최대 재시도 초과. 수동 개입 필요. */
    FAILED;

    public boolean isTerminal() {
        return this == PUBLISHED || this == FAILED;
    }
}
