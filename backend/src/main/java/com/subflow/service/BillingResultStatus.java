package com.subflow.service;

/** 결제 실행 결과 상태. */
public enum BillingResultStatus {

    /** 승인 성공(정기 갱신·연체 해소 포함). */
    SUCCEEDED,

    /** 승인 거절 — Dunning 정책에 따라 재시도/만료로 이어진다. */
    FAILED,

    /** 이번 실패로 재시도 한도를 넘어 구독이 만료됨. */
    EXPIRED,

    /** PG 응답 미확정(타임아웃). 원장은 PENDING 으로 남고 정합성 점검이 확정한다. */
    PENDING_CONFIRMATION,

    /** 같은 멱등키의 결제가 이미 있어 재실행하지 않음(중복 요청 방어). */
    REPLAYED,

    /** 청구일이 아직 오지 않음. */
    SKIPPED_NOT_DUE,

    /** 청구 가능한 상태가 아님(해지·일시정지·만료). */
    SKIPPED_NOT_BILLABLE,

    /** 기간 종료 해지가 예약된 구독이라 청구하지 않음. */
    SKIPPED_CANCEL_SCHEDULED,

    /** 보호 윈도우 안에서 다시 청구하려 해 건너뜀. */
    SKIPPED_DUPLICATE,

    /** 빌링키가 없어 청구하지 못함(체험 구독의 카드 미등록 등). */
    SKIPPED_NO_BILLING_KEY;

    public boolean isSucceeded() {
        return this == SUCCEEDED;
    }

    public boolean isSkipped() {
        return name().startsWith("SKIPPED_");
    }
}
