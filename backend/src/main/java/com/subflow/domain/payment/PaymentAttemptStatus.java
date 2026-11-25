package com.subflow.domain.payment;

/** 개별 승인 시도의 결과. Dunning 이력으로 쓰인다. */
public enum PaymentAttemptStatus {

    /** 승인 성공. */
    SUCCESS,

    /** 승인 실패. {@code errorMessage}에 PG 사유가 들어간다. */
    FAILED;

    public boolean isSuccess() {
        return this == SUCCESS;
    }
}
