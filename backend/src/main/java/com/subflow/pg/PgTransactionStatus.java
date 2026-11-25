package com.subflow.pg;

/** PG 쪽 거래 상태. 내부 결제 상태({@code PaymentStatus})와는 별개다. */
public enum PgTransactionStatus {

    /** 승인 성공. */
    APPROVED,

    /** 승인 거절(한도 초과·잔액 부족·카드 정지 등). */
    DECLINED,

    /** 승인 후 취소/환불. */
    CANCELED,

    /** PG 가 아직 결과를 확정하지 않음. */
    PENDING,

    /** PG 에 해당 거래가 없음(대사 불일치 신호). */
    UNKNOWN;

    public boolean isPaid() {
        return this == APPROVED;
    }
}
