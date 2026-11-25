package com.subflow.domain.subscription;

/** 구독 변경 종류. 변경 이력({@link SubscriptionChangeHistory})의 분류다. */
public enum ChangeType {

    /** 플랜 변경(가격·상품). 같은 구독 안에서 회차를 유지한다. */
    PLAN_CHANGE,

    /** 주기 변경(주 ↔ 월 등). 기준일(anchor day)을 다시 계산한다. */
    CYCLE_CHANGE,

    /** 수량 변경(좌석 수 등). */
    QUANTITY_CHANGE,

    /** 결제일(기준일) 변경. */
    BILLING_DATE_CHANGE,

    /** 해지(사유 포함). */
    CANCEL,

    /** 관리자 복구. */
    RESTORE
}
