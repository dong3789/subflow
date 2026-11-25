package com.subflow.pg;

/**
 * PG 승인 결과.
 *
 * @param approved      승인 여부
 * @param pgTxId        PG 거래 ID(승인·거절 모두 발급될 수 있다)
 * @param failureCode   거절 코드(예: INSUFFICIENT_FUNDS)
 * @param failureReason 거절 사유 원문
 * @param status        PG 거래 상태
 */
public record ChargeResult(
        boolean approved,
        String pgTxId,
        String failureCode,
        String failureReason,
        PgTransactionStatus status
) {

    public static ChargeResult approved(String pgTxId) {
        return new ChargeResult(true, pgTxId, null, null, PgTransactionStatus.APPROVED);
    }

    public static ChargeResult declined(String pgTxId, String failureCode, String failureReason) {
        return new ChargeResult(false, pgTxId, failureCode, failureReason, PgTransactionStatus.DECLINED);
    }
}
