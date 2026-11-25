package com.subflow.service;

/** 결제 실행 사유. 멱등키 구성과 가드 정책이 사유에 따라 달라진다. */
public enum BillingReason {

    /** 배치가 만기 구독을 청구. */
    SCHEDULED,

    /** 연체(PAST_DUE) 구독의 Dunning 재시도. */
    DUNNING_RETRY,

    /** 고객/관리자의 당겨받기(즉시 갱신) — 청구일과 무관하게 실행. */
    MANUAL_RENEWAL;

    public boolean isManual() {
        return this == MANUAL_RENEWAL;
    }
}
