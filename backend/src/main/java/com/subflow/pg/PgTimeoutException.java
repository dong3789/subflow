package com.subflow.pg;

/**
 * PG 응답 타임아웃 — <b>승인됐는지 알 수 없는</b> 상태.
 *
 * <p>이 예외는 실패가 아니다. 결제를 실패로 확정하면 고객이 실제로 결제된 뒤에도 재청구될 수 있으므로,
 * 결제 원장은 PENDING(미확정)으로 두고 정합성 점검이 PG 실제 상태로 확정한다.
 */
public class PgTimeoutException extends RuntimeException {

    private final String orderId;
    private final String pgTxId;

    public PgTimeoutException(String orderId, String pgTxId, String message) {
        super(message);
        this.orderId = orderId;
        this.pgTxId = pgTxId;
    }

    public String getOrderId() {
        return orderId;
    }

    /** PG 가 상관관계 추적용으로 준 거래 ID(없을 수 있다). */
    public String getPgTxId() {
        return pgTxId;
    }
}
